# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Postgres storage of opportunities, their documents and their audit trail.

Every change locks the opportunity's row, applies the rules in rules.py and
records an event in the same transaction. Events name the fields that
changed, never their values, so personal data lives in one place only.
"""

import hashlib
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
_kinds = ", ".join(f"'{kind}'" for kind in rules.DOCUMENT_KINDS)
_results = ", ".join(f"'{result}'" for result in rules.CHECK_RESULTS)
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
    # Each upload is kept; the latest check of a document replaces the earlier one.
    f"""
    CREATE TABLE IF NOT EXISTS opportunity_documents (
        id uuid PRIMARY KEY,
        opportunity_id uuid NOT NULL REFERENCES opportunities (id),
        kind text NOT NULL CHECK (kind IN ({_kinds})),
        content_type text NOT NULL,
        size integer NOT NULL CHECK (size > 0),
        sha256 text NOT NULL,
        content bytea NOT NULL,
        uploaded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
        uploaded_by text NOT NULL,
        checked_at timestamptz,
        check_result text CHECK (check_result IN ({_results})),
        check_reasons jsonb,
        extracted jsonb,
        CHECK ((checked_at IS NULL) = (check_result IS NULL))
    )
    """,
    "CREATE INDEX IF NOT EXISTS opportunity_documents_opportunity ON opportunity_documents (opportunity_id, uploaded_at)",
)
# A document without its image, for listings and replies.
DOCUMENT_COLUMNS = ("id, opportunity_id, kind, content_type, size, sha256, uploaded_at, uploaded_by,"
                    " checked_at, check_result, check_reasons, extracted")


class NotFound(Exception):
    """No opportunity, or no document of it, with that id (HTTP 404)."""


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


def change_stage(con, opportunity_id, target: str, reason_code: str | None, reason_note: str | None, actor: str,
                 may_move=None) -> dict:
    """`may_move(current, target)` raises to refuse a move the caller may not make."""
    with _transaction(con):
        current = _locked(con, opportunity_id)
        if may_move:
            may_move(current["stage"], target)
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


def register(con, opportunity_id, client_id: int, loan_id: int, actor: str, compared: dict | None = None) -> dict:
    """Records the Fineract client and loan application of a qualified opportunity.
    Repeating it with the same ids changes nothing. `compared` holds the fields the
    loan application was made from; the registration is refused if any changed."""
    with _transaction(con):
        row = _locked(con, opportunity_id)
        if row["stage"] == rules.REGISTERED:
            if (row["fineract_client_id"], row["fineract_loan_id"]) == (client_id, loan_id):
                return row
            raise rules.Conflict("the opportunity is already registered with other Fineract ids")
        rules.check_registration(row["stage"])
        changed = sorted(field for field, value in (compared or {}).items() if row[field] != value)
        if changed:
            raise rules.Conflict(f"the opportunity's {' and '.join(changed)} changed since the registration read them")
        row = _set(con, opportunity_id, {"stage": rules.REGISTERED, "fineract_client_id": client_id, "fineract_loan_id": loan_id})
        _event(con, opportunity_id, actor, "registered", {"fineract_client_id": client_id, "fineract_loan_id": loan_id},
               from_stage="qualified", to_stage=rules.REGISTERED)
    return row


def add_document(con, opportunity_id, kind: str, content_type: str, content: bytes, actor: str) -> dict:
    """Keeps a document of an open opportunity; the event names its kind and size only."""
    document_id = uuid.uuid4()
    with _transaction(con):
        row = _locked(con, opportunity_id)
        rules.check_open(row["stage"])
        document = _rows(
            con,
            "INSERT INTO opportunity_documents (id, opportunity_id, kind, content_type, size, sha256, content, uploaded_by)"
            f" VALUES (:id, :opportunity_id, :kind, :content_type, :size, :sha256, :content, :actor) RETURNING {DOCUMENT_COLUMNS}",
            id=document_id, opportunity_id=opportunity_id, kind=kind, content_type=content_type, size=len(content),
            sha256=hashlib.sha256(content).hexdigest(), content=content, actor=actor,
        )[0]
        _event(con, opportunity_id, actor, "document_added", {"document_id": str(document_id), "kind": kind, "size": len(content)})
    return document


def documents(con: pg8000.native.Connection, opportunity_id: uuid.UUID) -> list[dict]:
    """The opportunity's documents without their images, newest first."""
    get(con, opportunity_id)
    return _rows(con, f"SELECT {DOCUMENT_COLUMNS} FROM opportunity_documents WHERE opportunity_id = :id"
                      " ORDER BY uploaded_at DESC, id", id=opportunity_id)


def document_content(con, opportunity_id: uuid.UUID, document_id: uuid.UUID) -> dict:
    """A document's content type and image."""
    found = _rows(con, "SELECT content_type, content FROM opportunity_documents WHERE id = :id AND opportunity_id = :opportunity_id",
                  id=document_id, opportunity_id=opportunity_id)
    if not found:
        raise NotFound(f"no document {document_id} of opportunity {opportunity_id}")
    return found[0]


def record_check(con, opportunity_id, document_id, result: str, reasons: list, extracted: dict, compared: dict, actor: str,
                 may_move=None) -> tuple[dict, dict]:
    """Records a document's check and the move its result calls for (rules.CHECK_MOVES) in one
    transaction, only while the check still applies: the opportunity in documents, the document
    the newest of its kind, and the applicant's fields in `compared` (CURP, name) the ones the
    check compared with it. The same check again, once applied, changes nothing, so an
    interrupted run can be retried. Returns the document and the opportunity."""
    target = rules.CHECK_MOVES.get(result)
    with _transaction(con):
        row = _locked(con, opportunity_id)
        found = _rows(con, f"SELECT {DOCUMENT_COLUMNS} FROM opportunity_documents WHERE id = :id AND opportunity_id = :opportunity_id",
                      id=document_id, opportunity_id=opportunity_id)
        if not found:
            raise NotFound(f"no document {document_id} of opportunity {opportunity_id}")
        document = found[0]
        if (document["check_result"], document["check_reasons"]) == (result, reasons) and row["stage"] == (target or "documents"):
            return document, row
        rules.check_open(row["stage"])
        if row["stage"] != "documents":
            raise rules.Conflict(f"the opportunity is {row['stage']}, not documents: the check no longer applies")
        newest = _rows(con, "SELECT id FROM opportunity_documents WHERE opportunity_id = :opportunity_id AND kind = :kind"
                            " ORDER BY uploaded_at DESC, id LIMIT 1", opportunity_id=opportunity_id, kind=document["kind"])[0]
        if newest["id"] != document["id"]:
            raise rules.Conflict("a newer document of this kind was uploaded after this one: check that one")
        changed = sorted(field for field, value in compared.items() if row[field] != value)
        if changed:
            raise rules.Conflict(f"the opportunity's {' and '.join(changed)} changed since the check compared them")
        if target and may_move:
            may_move(row["stage"], target)
        document = _rows(
            con,
            "UPDATE opportunity_documents SET checked_at = now(), check_result = :result,"
            " check_reasons = CAST(:reasons AS jsonb), extracted = CAST(:extracted AS jsonb)"
            f" WHERE id = :id RETURNING {DOCUMENT_COLUMNS}",
            id=document_id, result=result, reasons=json.dumps(reasons), extracted=json.dumps(extracted),
        )[0]
        _event(con, opportunity_id, actor, "document_checked", {"document_id": str(document_id), "result": result})
        if target:
            # A paused opportunity keeps why: the reasons the check gave.
            reason_code, reason_note = (result, "; ".join(r["detail"] for r in reasons)[:1000] or None) if target == rules.PAUSED else (None, None)
            rules.check_stage_change(row["stage"], target, reason_code, reason_note, row["opted_out"])
            row = _set(con, opportunity_id, {"stage": target, "reason_code": reason_code, "reason_note": reason_note})
            _event(con, opportunity_id, actor, "stage", {"reason_code": reason_code} if reason_code else {},
                   from_stage="documents", to_stage=target)
    return document, row


def events(con: pg8000.native.Connection, opportunity_id: uuid.UUID) -> list[dict]:
    get(con, opportunity_id)
    return _rows(con, "SELECT * FROM opportunity_events WHERE opportunity_id = :id ORDER BY id", id=opportunity_id)


def _now(con: pg8000.native.Connection):
    # The database clock, the same one that sets created_at and updated_at.
    return con.run("SELECT now()")[0][0]
