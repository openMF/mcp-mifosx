# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Postgres storage of opportunities and their audit trail.

Every change locks the opportunity's row, applies the rules in rules.py and
records an event in the same transaction. Events name the fields that
changed, never their values, so personal data lives in one place only.
"""

import json
import os
import uuid
from contextlib import contextmanager

import pg8000.native
from pg8000.exceptions import DatabaseError

import rules

# Applicant and loan fields; the only ones PATCH may change.
EDITABLE = ("full_name", "phone", "email", "national_id", "requested_amount", "currency", "term_months", "productive_use")

_stages = ", ".join(f"'{stage}'" for stage in rules.STAGES)
SCHEMA = (
    f"""
    CREATE TABLE IF NOT EXISTS opportunities (
        id uuid PRIMARY KEY,
        external_id text NOT NULL UNIQUE,
        created_at timestamptz NOT NULL DEFAULT now(),
        updated_at timestamptz NOT NULL DEFAULT now(),
        channel text NOT NULL,
        stage text NOT NULL DEFAULT 'new' CHECK (stage IN ({_stages})),
        full_name text,
        phone text,
        email text,
        national_id text,
        requested_amount numeric(14, 2) CHECK (requested_amount > 0),
        currency text CHECK (currency ~ '^[A-Z]{{3}}$'),
        term_months integer CHECK (term_months BETWEEN 1 AND 600),
        productive_use text,
        bureau_consent_at timestamptz,
        sat_consent_at timestamptz,
        contact_consent_at timestamptz,
        opted_out boolean NOT NULL DEFAULT false,
        reason_code text,
        reason_note text,
        fineract_client_id bigint,
        fineract_loan_id bigint,
        CHECK (requested_amount IS NULL OR currency IS NOT NULL),
        CHECK ((fineract_client_id IS NULL) = (fineract_loan_id IS NULL)),
        CHECK ((stage = 'registered') = (fineract_loan_id IS NOT NULL))
    )
    """,
    "CREATE INDEX IF NOT EXISTS opportunities_phone ON opportunities (phone)",
    "CREATE INDEX IF NOT EXISTS opportunities_national_id ON opportunities (national_id)",
    "CREATE INDEX IF NOT EXISTS opportunities_stage ON opportunities (stage, created_at)",
    """
    CREATE TABLE IF NOT EXISTS opportunity_events (
        id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
        opportunity_id uuid NOT NULL REFERENCES opportunities (id),
        at timestamptz NOT NULL DEFAULT now(),
        actor text NOT NULL,
        action text NOT NULL,
        from_stage text,
        to_stage text,
        detail jsonb NOT NULL DEFAULT '{}'
    )
    """,
    "CREATE INDEX IF NOT EXISTS opportunity_events_opportunity ON opportunity_events (opportunity_id, id)",
)


class NotFound(Exception):
    """No opportunity with that id (HTTP 404)."""


def connect() -> pg8000.native.Connection:
    return pg8000.native.Connection(
        host=os.getenv("LOS_DB_HOST", "los-db"),
        port=int(os.getenv("LOS_DB_PORT", "5432")),
        database=os.getenv("LOS_DB_NAME", "los"),
        user=os.getenv("LOS_DB_USER", "los"),
        password=os.environ["LOS_DB_PASSWORD"],
    )


def create_schema(con: pg8000.native.Connection) -> None:
    for statement in SCHEMA:
        con.run(statement)


def _rows(con: pg8000.native.Connection, sql: str, **params) -> list[dict]:
    result = con.run(sql, **params)
    names = [column["name"] for column in con.columns or []]
    return [dict(zip(names, row)) for row in result or []]


@contextmanager
def _transaction(con: pg8000.native.Connection):
    con.run("START TRANSACTION")
    try:
        yield
    except BaseException:
        con.run("ROLLBACK")
        raise
    con.run("COMMIT")


def _locked(con: pg8000.native.Connection, opportunity_id: uuid.UUID) -> dict:
    found = _rows(con, "SELECT * FROM opportunities WHERE id = :id FOR UPDATE", id=opportunity_id)
    if not found:
        raise NotFound(f"no opportunity {opportunity_id}")
    return found[0]


def _event(con, opportunity_id, actor, action, detail=None, from_stage=None, to_stage=None) -> None:
    con.run(
        "INSERT INTO opportunity_events (opportunity_id, actor, action, from_stage, to_stage, detail)"
        " VALUES (:id, :actor, :action, :from_stage, :to_stage, CAST(:detail AS jsonb))",
        id=opportunity_id, actor=actor, action=action, from_stage=from_stage, to_stage=to_stage,
        detail=json.dumps(detail or {}),
    )


def _set(con, opportunity_id, values: dict) -> dict:
    # Column names come from this module, never from the request.
    assignments = ", ".join(f"{column} = :{column}" for column in values)
    return _rows(con, f"UPDATE opportunities SET {assignments}, updated_at = now() WHERE id = :id RETURNING *",
                 id=opportunity_id, **values)[0]


def create(con: pg8000.native.Connection, fields: dict, actor: str) -> dict:
    """Creates an opportunity in stage `new`; `external_id` defaults to opp-<id>."""
    opportunity_id = uuid.uuid4()
    values = {**fields, "external_id": fields.get("external_id") or f"opp-{opportunity_id}"}
    columns = ", ".join(["id", *values])
    placeholders = ", ".join([":id", *(f":{column}" for column in values)])
    with _transaction(con):
        try:
            row = _rows(con, f"INSERT INTO opportunities ({columns}) VALUES ({placeholders}) RETURNING *",
                        id=opportunity_id, **values)[0]
        except DatabaseError as e:
            if e.args and isinstance(e.args[0], dict) and e.args[0].get("C") == "23505":
                raise rules.Conflict(f"an opportunity with external_id {values['external_id']} already exists") from None
            raise
        _event(con, opportunity_id, actor, "created", {"fields": sorted(fields)}, to_stage=row["stage"])
    return row


def get(con: pg8000.native.Connection, opportunity_id: uuid.UUID) -> dict:
    found = _rows(con, "SELECT * FROM opportunities WHERE id = :id", id=opportunity_id)
    if not found:
        raise NotFound(f"no opportunity {opportunity_id}")
    return found[0]


def search(con: pg8000.native.Connection, filters: dict, limit: int) -> list[dict]:
    """Newest first; `filters` maps column names to exact values."""
    where = " AND ".join(f"{column} = :{column}" for column in filters)
    sql = "SELECT * FROM opportunities" + (f" WHERE {where}" if where else "") + " ORDER BY created_at DESC, id LIMIT :limit"
    return _rows(con, sql, limit=limit, **filters)


def update(con: pg8000.native.Connection, opportunity_id: uuid.UUID, changes: dict, actor: str) -> dict:
    """Changes applicant and loan fields; `None` clears a field."""
    with _transaction(con):
        row = _locked(con, opportunity_id)
        rules.check_open(row["stage"])
        changed = {column: value for column, value in changes.items() if row[column] != value}
        if not changed:
            return row
        merged = {**row, **changed}
        if merged["requested_amount"] is not None and merged["currency"] is None:
            raise rules.Invalid("a requested_amount needs a currency")
        row = _set(con, opportunity_id, changed)
        _event(con, opportunity_id, actor, "updated", {"fields": sorted(changed)})
    return row


def change_stage(con, opportunity_id, target: str, reason_code: str | None, reason_note: str | None, actor: str) -> dict:
    with _transaction(con):
        current = _locked(con, opportunity_id)
        rules.check_stage_change(current["stage"], target, reason_code, reason_note, current["opted_out"])
        # Paused and discarded keep their reason; any other stage clears it.
        row = _set(con, opportunity_id, {"stage": target, "reason_code": reason_code, "reason_note": reason_note})
        detail = {"reason_code": reason_code} if reason_code else {}
        _event(con, opportunity_id, actor, "stage", detail, from_stage=current["stage"], to_stage=target)
    return row


def record_consent(con, opportunity_id, kind: str, actor: str) -> dict:
    """Sets <kind>_consent_at to now; a consent already recorded keeps its time.
    Contact consent also lifts an opt-out."""
    column = f"{kind}_consent_at"
    with _transaction(con):
        row = _locked(con, opportunity_id)
        rules.check_open(row["stage"])
        values = {} if row[column] else {column: _now(con)}
        if kind == "contact" and row["opted_out"]:
            values["opted_out"] = False
        if not values:
            return row
        row = _set(con, opportunity_id, values)
        _event(con, opportunity_id, actor, "consent", {"consent": kind})
    return row


def opt_out(con, opportunity_id, actor: str) -> dict:
    """Stops contact in any stage, including registered and discarded."""
    with _transaction(con):
        row = _locked(con, opportunity_id)
        if row["opted_out"]:
            return row
        row = _set(con, opportunity_id, {"opted_out": True, "contact_consent_at": None})
        _event(con, opportunity_id, actor, "opt_out")
    return row


def register(con, opportunity_id, client_id: int, loan_id: int, actor: str) -> dict:
    """Records the Fineract client and loan application of a qualified opportunity.
    Repeating it with the same ids changes nothing."""
    with _transaction(con):
        row = _locked(con, opportunity_id)
        if row["stage"] == rules.REGISTERED:
            if (row["fineract_client_id"], row["fineract_loan_id"]) == (client_id, loan_id):
                return row
            raise rules.Conflict("the opportunity is already registered with other Fineract ids")
        rules.check_registration(row["stage"])
        row = _set(con, opportunity_id, {"stage": rules.REGISTERED, "fineract_client_id": client_id, "fineract_loan_id": loan_id})
        _event(con, opportunity_id, actor, "registered", {"fineract_client_id": client_id, "fineract_loan_id": loan_id},
               from_stage="qualified", to_stage=rules.REGISTERED)
    return row


def events(con: pg8000.native.Connection, opportunity_id: uuid.UUID) -> list[dict]:
    get(con, opportunity_id)
    return _rows(con, "SELECT * FROM opportunity_events WHERE opportunity_id = :id ORDER BY id", id=opportunity_id)


def _now(con: pg8000.native.Connection):
    # The database clock, the same one that sets created_at and updated_at.
    return con.run("SELECT now()")[0][0]
