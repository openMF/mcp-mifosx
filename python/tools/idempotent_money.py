# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Fineract-specific glue for the durable idempotency ledger.

``core.idempotency`` owns the ledger, the key/fingerprint rules and the state
machine. This module adds the two things only Fineract can answer:

* how a failed POST is classified (conclusive rejection vs. a transport
  failure whose outcome is genuinely unknown), and
* how to look for an ambiguous attempt's effect with the read endpoints this
  repository already uses.

Automatic reconciliation is only claimed where a verified read exists. When it
does not (savings charges, manual journal entries) the reconciler is ``None``
and an ambiguous outcome stays ``UNKNOWN`` for a human to check; the backend
``Idempotency-Key`` header still protects those calls when the Fineract
deployment honours it.
"""

from __future__ import annotations

import logging
import os
from datetime import date, datetime, timezone
from decimal import Decimal, InvalidOperation
from typing import Any, Callable, Dict, List, Mapping, Optional, Sequence

from core.idempotency import (
    ABSENCE_READS_ENV,
    DEFAULT_ABSENCE_READS,
    DEFAULT_MARKER_FIELDS,
    ExecutionResult,
    LedgerRecord,
    OperationRequest,
    Outcome,
    Reconciler,
    ReconcileResult,
    ReconcileStatus,
    absence_safety_window_seconds,
    backend_header_name,
    execute_money_operation,
)
from tools.mcp_adapter import fineract_client

logger = logging.getLogger(__name__)

# HTTP statuses that do not prove the request was rejected. 409 is included
# because Fineract uses it for an idempotency conflict (the first request may
# still be running) as well as for state conflicts; guessing "failed" there is
# exactly the mistake this module exists to prevent.
_AMBIGUOUS_STATUSES = frozenset({408, 409, 425, 429})

_MONTHS = {
    name.lower(): index
    for index, name in enumerate(
        (
            "January",
            "February",
            "March",
            "April",
            "May",
            "June",
            "July",
            "August",
            "September",
            "October",
            "November",
            "December",
        ),
        start=1,
    )
}


# ── result classification ────────────────────────────────────────────


def classify_post(body: Any) -> ExecutionResult:
    """Classify what ``FineractAdapter.execute_post`` returned.

    A 4xx (other than the statuses that leave the effect open) is a
    conclusive rejection: Fineract validated the request and refused it, so a
    retry is safe. A 5xx, a timeout or a dropped connection may have happened
    *after* the transaction committed, so the outcome is ambiguous.
    """
    if not isinstance(body, Mapping) or "error" not in body:
        return ExecutionResult(Outcome.SUCCESS, result=dict(body) if isinstance(body, Mapping) else {"result": body})

    message = str(body.get("error") or "The backend did not confirm the operation.")
    status = body.get("status_code")
    if (
        isinstance(status, int)
        and not isinstance(status, bool)
        and 400 <= status < 500
        and status not in _AMBIGUOUS_STATUSES
    ):
        return ExecutionResult(Outcome.FAILURE, error=message, raw=dict(body))
    if isinstance(status, int) and not isinstance(status, bool):
        detail = f"Fineract returned HTTP {status}."
    elif _looks_like_transport_failure(message):
        detail = "The connection failed before an HTTP status was received."
    else:
        # A domain/mock result with no status that reads like a validation error
        # is a conclusive refusal: Fineract validated the request and rejected it.
        return ExecutionResult(Outcome.FAILURE, error=message, raw=dict(body))
    return ExecutionResult(Outcome.AMBIGUOUS, error=message, detail=detail)


def _looks_like_transport_failure(message: str) -> bool:
    lowered = message.lower()
    return (
        lowered.startswith("connection failed")
        or "timed out" in lowered
        or "timeout" in lowered
        or "connection reset" in lowered
        or "connection aborted" in lowered
    )


def post_money(
    *,
    operation: str,
    resource: str,
    payload: Mapping[str, Any],
    endpoint: str,
    body: Mapping[str, Any],
    idempotency_key: Optional[str],
    reconciler: Optional[Reconciler] = None,
    reconciliation: Optional[Mapping[str, Any]] = None,
) -> Dict[str, Any]:
    """Run one money-moving Fineract POST at most once per idempotency key.

    ``payload`` is the client-visible request used for the fingerprint (no
    server-derived date, so a retry on a later day still matches). ``body`` is
    the exact Fineract request body built by the domain layer.
    """
    request = OperationRequest(
        operation=operation,
        resource=resource,
        payload=payload,
        reconciliation=reconciliation or {},
    )

    def executor(backend_key: Optional[str]) -> ExecutionResult:
        extra = {backend_header_name(): backend_key} if backend_key else None
        if extra is None:
            result = fineract_client.execute_post(endpoint, body)
        else:
            result = fineract_client.execute_post(endpoint, body, extra_headers=extra)
        return classify_post(result)

    return execute_money_operation(request, idempotency_key, executor, reconciler)


# ── read helpers ─────────────────────────────────────────────────────


class _LookupFailed(Exception):
    """A reconciliation read did not return usable data."""


def _require_dict(value: Any) -> Mapping[str, Any]:
    if not isinstance(value, Mapping) or "error" in value:
        reason = value.get("error") if isinstance(value, Mapping) else "unexpected response"
        raise _LookupFailed(f"reconciliation read failed: {reason}")
    return value


def _list_of(
    payload: Any, key: str, *, expect_object: bool = True
) -> List[Mapping[str, Any]]:
    """Strictly read a collection. Anything unexpected means "unknown".

    A 200 response that simply omits the expected collection (proxy stripping,
    serializer change, permission filtering) is not an empty collection: it is
    a read that proved nothing, so it must never become absence evidence.

    ``expect_object`` describes the endpoint's contract. Fineract's loan and
    savings detail reads return an object containing the collection, so a bare
    JSON list is malformed for them and is rejected here exactly as the Go
    implementation rejects it. The few endpoints that genuinely return a bare
    array must opt out explicitly with ``expect_object=False``.
    """
    if expect_object:
        container = _require_dict(payload)
        if key not in container:
            raise _LookupFailed(
                f"reconciliation response has no '{key}' collection; the outcome is unknown"
            )
        items = container.get(key)
        if not isinstance(items, list):
            raise _LookupFailed(f"reconciliation response field '{key}' is not a list")
    else:
        if not isinstance(payload, list):
            raise _LookupFailed(
                f"reconciliation response is not a '{key}' collection; the outcome is unknown"
            )
        items = payload
    result: List[Mapping[str, Any]] = []
    for item in items:
        if not isinstance(item, Mapping):
            raise _LookupFailed(f"reconciliation response has a malformed '{key}' entry")
        result.append(item)
    return result


def _decimal(value: Any) -> Optional[Decimal]:
    if isinstance(value, bool) or value is None:
        return None
    try:
        parsed = Decimal(str(value))
    except (InvalidOperation, ValueError):
        return None
    return parsed if parsed.is_finite() else None


def _same_amount(left: Any, right: Any) -> bool:
    a, b = _decimal(left), _decimal(right)
    return a is not None and b is not None and a == b


def _parse_date(value: Any) -> Optional[date]:
    if isinstance(value, (list, tuple)) and len(value) == 3:
        try:
            return date(int(value[0]), int(value[1]), int(value[2]))
        except (TypeError, ValueError):
            return None
    if isinstance(value, str):
        parts = value.strip().split()
        if len(parts) == 3:
            month = _MONTHS.get(parts[1].lower())
            if month is not None:
                try:
                    return date(int(parts[2]), month, int(parts[0]))
                except ValueError:
                    return None
    return None


def _record_id(record: Mapping[str, Any]) -> Optional[str]:
    for key in ("id", "transactionId"):
        value = record.get(key)
        if value is not None:
            return f"{key}:{value}"
    return None


def _record_key(record: Mapping[str, Any], extra: Sequence[str] = ()) -> Optional[str]:
    """Stable identity for a record that may not expose a top-level id."""
    direct = _record_id(record)
    if direct is not None:
        return direct
    parts = [f"{name}:{record.get(name)}" for name in extra]
    return "|".join(parts) if parts else None


def _transaction_type(record: Mapping[str, Any]) -> tuple[str, str]:
    for field in ("transactionType", "type"):
        value = record.get(field)
        if isinstance(value, Mapping):
            return str(value.get("code") or ""), str(value.get("value") or "")
        if isinstance(value, str):
            return "", value
    return "", ""


def _kind_matches(record: Mapping[str, Any], needles: Sequence[str], suffixes: Sequence[str]) -> bool:
    code, label = _transaction_type(record)
    if any(code.endswith(suffix) for suffix in suffixes):
        return True
    lowered = label.lower()
    return any(needle in lowered for needle in needles)


def _absence_reads() -> int:
    raw = os.getenv(ABSENCE_READS_ENV)
    if raw is None or raw.strip() == "":
        return DEFAULT_ABSENCE_READS
    try:
        value = int(raw)
    except ValueError:
        return DEFAULT_ABSENCE_READS
    return value if value >= 1 else DEFAULT_ABSENCE_READS


_TIME_FORMAT = "%Y-%m-%dT%H:%M:%S.%fZ"


def _timestamp(value: Any) -> Optional[datetime]:
    if not isinstance(value, str) or not value:
        return None
    try:
        return datetime.strptime(value, _TIME_FORMAT).replace(tzinfo=timezone.utc)
    except ValueError:
        return None


def _absence_verdict(record: LedgerRecord, params: Mapping[str, Any]) -> tuple[bool, str]:
    """Attempt-scoped, time-windowed absence evidence.

    Absence is only reported after the attempt's own end timestamp, a
    configurable safety window, and at least ``ABSENCE_CONFIRMATIONS`` valid
    reads each spaced by that window. The window is a mitigation, not proof of
    backend read consistency; the state machine still refuses an automatic
    retry unless backend deduplication is explicitly verified.
    """
    required = _absence_reads()
    window = absence_safety_window_seconds()
    now = datetime.now(timezone.utc)
    ended = _timestamp(record.attempt_ended_at) or _timestamp(params.get("attempt_ended_at"))
    if ended is None:
        return False, "The attempt's end time is unknown, so absence cannot be proven."

    attempt_number = int(record.attempt or 0)
    if int(params.get("absence_attempt") or 0) != attempt_number:
        # New attempt: earlier empty reads say nothing about this one.
        params["absence_attempt"] = attempt_number
        params["absence_reads"] = 0
        params.pop("absence_last_read_at", None)

    if (now - ended).total_seconds() < window:
        return False, (
            "The safety window since the attempt ended has not elapsed; "
            "absence is not confirmed."
        )

    last = _timestamp(params.get("absence_last_read_at"))
    if last is not None and (now - last).total_seconds() < window:
        return False, (
            "Not enough time has passed since the previous empty read; "
            "absence is not confirmed."
        )

    reads = int(params.get("absence_reads") or 0) + 1
    params["absence_reads"] = reads
    params["absence_last_read_at"] = now.strftime(_TIME_FORMAT)
    if reads < required:
        return False, "No matching record is visible yet; absence is not confirmed."
    return True, "No record of this operation exists in Fineract."


def _marker_for(params: Mapping[str, Any]) -> str:
    marker = params.get("marker")
    return marker if isinstance(marker, str) else ""


def _marker_fields_for(params: Mapping[str, Any]) -> Sequence[str]:
    fields = params.get("marker_fields")
    if isinstance(fields, (list, tuple)) and all(isinstance(field, str) for field in fields):
        return list(fields)
    return DEFAULT_MARKER_FIELDS


def _record_marker(item: Mapping[str, Any], params: Mapping[str, Any]) -> str:
    """The unique per-key marker a backend record exposes, if any."""
    marker = _marker_for(params)
    if not marker:
        return ""
    for field in _marker_fields_for(params):
        value = item.get(field)
        if isinstance(value, str) and value == marker:
            return value
    return ""


# ── generic new-record reconciler ────────────────────────────────────


class NewRecordReconciler(Reconciler):
    """Confirm an ambiguous attempt by finding exactly one new backend record.

    The pre-execution snapshot is a list of record identities. On reconcile,
    only records that did not exist before the attempt are considered, so a
    lookalike created earlier can never be mistaken for this attempt. Zero new
    matches are only called ``ABSENT`` after ``MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS``
    reads; until then the result stays ``UNKNOWN``.
    """

    def __init__(
        self,
        *,
        operation: str,
        read: Callable[[Mapping[str, Any]], Any],
        collection_key: str,
        matches: Callable[[Mapping[str, Any], Mapping[str, Any]], bool],
        build_result: Callable[[Mapping[str, Any], Mapping[str, Any]], Dict[str, Any]],
        identity: Sequence[str] = (),
        expect_object: bool = True,
    ) -> None:
        self.operation = operation
        self._read = read
        self._collection_key = collection_key
        self._matches = matches
        self._build_result = build_result
        self._identity = tuple(identity)
        self._expect_object = expect_object

    def _records(self, params: Mapping[str, Any]) -> List[Mapping[str, Any]]:
        return _list_of(
            self._read(params), self._collection_key, expect_object=self._expect_object
        )

    def snapshot(self, request: OperationRequest) -> Any:
        params = request.reconciliation
        records = self._records(params)
        return [key for key in (self._record_key(item) for item in records) if key is not None]

    def reconcile(self, record: LedgerRecord) -> ReconcileResult:
        snapshot = record.reconcile.get("snapshot")
        if not isinstance(snapshot, list):
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail="No pre-execution snapshot exists, so a new record cannot be told apart "
                "from an older one. Check the operation in Fineract.",
            )
        params = record.reconcile
        try:
            records = self._records(params)
        except _LookupFailed as e:
            return ReconcileResult(ReconcileStatus.UNKNOWN, detail=str(e))

        seen = {str(key) for key in snapshot}
        new_records = []
        for item in records:
            key = self._record_key(item)
            if key is not None and key not in seen:
                new_records.append(item)
        if not new_records:
            confirmed, detail = _absence_verdict(record, params)
            if not confirmed:
                return ReconcileResult(ReconcileStatus.UNKNOWN, detail=detail)
            return ReconcileResult(ReconcileStatus.ABSENT, detail=detail)

        # A new record with the right type and amount is not enough: another
        # channel may have created an identical transaction. Only a record
        # that carries this request's unique marker can be attributed to it.
        marked = [item for item in new_records if _record_marker(item, params)]
        if len(marked) == 1:
            item = marked[0]
            return ReconcileResult(
                ReconcileStatus.CONFIRMED,
                result=self._build_result(item, params),
                backend_ref={"record_id": _record_id(item) or self._record_key(item)},
                detail="Matched exactly one new Fineract record carrying this request's marker.",
            )
        if len(marked) > 1:
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail="More than one new record carries this request's marker, so its effect cannot be isolated.",
            )

        matching = [item for item in new_records if self._matches(item, params)]
        if matching:
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail="New records match this operation's type and amount, but none carries a "
                "unique request marker, so the outcome cannot be attributed safely. Check it in Fineract.",
            )
        # Something changed, but not in the shape this operation creates.
        # Guessing here is unsafe: another client's write may be the cause.
        return ReconcileResult(
            ReconcileStatus.UNKNOWN,
            detail="New records appeared, but none matches this operation. Check it in Fineract.",
        )

    def _record_key(self, item: Mapping[str, Any]) -> Optional[str]:
        return _record_key(item, self._identity)


# ── Fineract reads and reconcilers ───────────────────────────────────


def _loan_transactions(params: Mapping[str, Any]) -> Any:
    return fineract_client.execute_get(
        f"loans/{params['loan_id']}?associations=transactions"
    )


def _loan_charges(params: Mapping[str, Any]) -> Any:
    return fineract_client.execute_get(f"loans/{params['loan_id']}?associations=charges")


def _savings_transactions(params: Mapping[str, Any]) -> Any:
    return fineract_client.execute_get(
        f"savingsaccounts/{params['account_id']}?associations=transactions"
    )


def _client_charges(params: Mapping[str, Any]) -> Any:
    return fineract_client.execute_get(f"clients/{params['client_id']}/charges")


def _loan_transaction_result(operation: str) -> Callable[[Mapping[str, Any], Mapping[str, Any]], Dict[str, Any]]:
    def build(item: Mapping[str, Any], params: Mapping[str, Any]) -> Dict[str, Any]:
        _, label = _transaction_type(item)
        return {
            "loanId": params.get("loan_id"),
            "transactionId": item.get("id"),
            "resourceId": item.get("id"),
            "type": label or None,
            "amount": item.get("amount"),
            "date": item.get("date"),
            "reconciled": True,
            "operation": operation,
        }

    return build


def _savings_transaction_result(
    operation: str,
) -> Callable[[Mapping[str, Any], Mapping[str, Any]], Dict[str, Any]]:
    def build(item: Mapping[str, Any], params: Mapping[str, Any]) -> Dict[str, Any]:
        _, label = _transaction_type(item)
        return {
            "savingsId": params.get("account_id"),
            "transactionId": item.get("id"),
            "resourceId": item.get("id"),
            "type": label or None,
            "amount": item.get("amount"),
            "date": item.get("date"),
            "reconciled": True,
            "operation": operation,
        }

    return build


def _charge_result(
    owner: str, operation: str
) -> Callable[[Mapping[str, Any], Mapping[str, Any]], Dict[str, Any]]:
    def build(item: Mapping[str, Any], params: Mapping[str, Any]) -> Dict[str, Any]:
        return {
            owner: params.get("owner_id"),
            "chargeId": item.get("chargeId"),
            "appliedChargeId": item.get("id"),
            "resourceId": item.get("id"),
            "amount": item.get("amount"),
            "dueDate": item.get("dueDate"),
            "reconciled": True,
            "operation": operation,
        }

    return build


def _amount_and_charge_match(item: Mapping[str, Any], params: Mapping[str, Any]) -> bool:
    return item.get("chargeId") == params.get("charge_id") and _same_amount(
        item.get("amount"), params.get("amount")
    )


def _transaction_match(
    needles: Sequence[str], suffixes: Sequence[str]
) -> Callable[[Mapping[str, Any], Mapping[str, Any]], bool]:
    def matches(item: Mapping[str, Any], params: Mapping[str, Any]) -> bool:
        if item.get("reversed"):
            return False
        if not _kind_matches(item, needles, suffixes):
            return False
        return _same_amount(item.get("amount"), params.get("amount"))

    return matches


def _interest_match(item: Mapping[str, Any], params: Mapping[str, Any]) -> bool:
    return not item.get("reversed") and _kind_matches(item, ("interest",), (".interestposting",))


def loan_repayment_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="loan.repayment",
        read=_loan_transactions,
        collection_key="transactions",
        matches=_transaction_match(("repay",), (".repayment",)),
        build_result=_loan_transaction_result("loan.repayment"),
    )


def loan_waive_interest_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="loan.waive_interest",
        read=_loan_transactions,
        collection_key="transactions",
        matches=_transaction_match(("waive",), (".waiveinterest",)),
        build_result=_loan_transaction_result("loan.waive_interest"),
    )


def loan_charge_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="loan.charge",
        read=_loan_charges,
        collection_key="charges",
        matches=_amount_and_charge_match,
        build_result=_charge_result("loanId", "loan.charge"),
        identity=("chargeId", "amount", "dueDate"),
    )


def savings_charge_reconciler() -> Optional[Reconciler]:
    """Savings charge application has no verified read endpoint.

    Fineract's savings-account associations used by this repository expose
    transactions, not applied charges, and a charge does not become a
    transaction until it is due/paid. Claiming reconciliation here would be
    inventing a capability, so an ambiguous outcome stays UNKNOWN.
    """
    return None


def savings_deposit_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="savings.deposit",
        read=_savings_transactions,
        collection_key="transactions",
        matches=_transaction_match(("deposit",), (".deposit",)),
        build_result=_savings_transaction_result("savings.deposit"),
    )


def savings_withdrawal_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="savings.withdrawal",
        read=_savings_transactions,
        collection_key="transactions",
        matches=_transaction_match(("withdraw",), (".withdrawal",)),
        build_result=_savings_transaction_result("savings.withdrawal"),
    )


def savings_interest_reconciler() -> Reconciler:
    return NewRecordReconciler(
        operation="savings.post_interest",
        read=_savings_transactions,
        collection_key="transactions",
        matches=_interest_match,
        build_result=_savings_transaction_result("savings.post_interest"),
    )


def client_charge_reconciler() -> Reconciler:
    # Fineract's client-charge list endpoint returns a bare JSON array, unlike
    # the loan/savings detail reads, so this reconciler opts out of the
    # object-with-collection contract explicitly.
    return NewRecordReconciler(
        operation="client.charge",
        read=_client_charges,
        collection_key="charges",
        matches=_amount_and_charge_match,
        build_result=_charge_result("clientId", "client.charge"),
        identity=("chargeId", "amount", "dueDate"),
        expect_object=False,
    )


# The Fineract loan status labels this reconciler understands. A status outside
# this set is an unusable read, never evidence: this set is the entire
# vocabulary that may participate in a disbursal decision. It mirrors
# knownLoanStatuses in go/tools/money.go so both servers decide identically.
_KNOWN_LOAN_STATUSES = frozenset(
    {
        "submitted and pending approval",
        "approved",
        "active",
        "rejected",
        "withdrawn by applicant",
        "closed (obligations met)",
        "closed (written off)",
        "closed (rescheduled)",
        "overpaid",
        "transfer in progress",
        "transfer on hold",
    }
)

# Statuses that cannot be observed after a successful disbursal. Only these may
# support an absence verdict: "Closed (obligations met)" proves the loan existed,
# not that no disbursement happened.
_PRE_DISBURSAL_LOAN_STATUSES = frozenset(
    {
        "submitted and pending approval",
        "approved",
        "rejected",
        "withdrawn by applicant",
    }
)


def _normalize_loan_status(value: Any) -> Optional[str]:
    """Return the status label only when it is one this server understands.

    Missing, ``None``, numeric, object-valued and unknown string statuses are
    all rejected. A rejected status is an unusable read, which the caller must
    treat as UNKNOWN rather than as absence.
    """
    if not isinstance(value, str):
        return None
    label = value.strip()
    if not label or label.lower() not in _KNOWN_LOAN_STATUSES:
        return None
    return label


def _loan_status_from(payload: Any) -> Optional[str]:
    """Extract a usable status from a ``GET loans/{id}`` response."""
    if not isinstance(payload, Mapping) or "error" in payload:
        return None
    status = payload.get("status")
    if not isinstance(status, Mapping):
        return None
    return _normalize_loan_status(status.get("value"))


def _is_pre_disbursal_status(label: str) -> bool:
    return label.strip().lower() in _PRE_DISBURSAL_LOAN_STATUSES


class LoanDisbursalReconciler(Reconciler):
    """Confirm a disbursal from the loan's own status transition.

    Only a recognised Fineract loan status may participate. A response with a
    missing, null, numeric, object-valued or unrecognised status is an
    unusable read and therefore UNKNOWN; it is never absence evidence. An
    unusable read must also not be stored as a snapshot: ``{"status": None}``
    is indistinguishable from "the loan was not Active", which would turn an
    already-Active loan into a false confirmation.
    """

    operation = "loan.disburse"

    def snapshot(self, request: OperationRequest) -> Any:
        payload = fineract_client.execute_get(f"loans/{request.reconciliation['loan_id']}")
        status = _loan_status_from(payload)
        if status is None:
            return None
        return {"status": status}

    def _status(self, params: Mapping[str, Any]) -> Optional[str]:
        payload = fineract_client.execute_get(f"loans/{params['loan_id']}")
        return _loan_status_from(payload)

    def reconcile(self, record: LedgerRecord) -> ReconcileResult:
        params = record.reconcile
        before = record.reconcile.get("snapshot")
        if not isinstance(before, Mapping):
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail="No pre-execution loan status was captured, so the disbursal cannot be told apart.",
            )
        before_status = _normalize_loan_status(before.get("status"))
        if before_status is None:
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail=(
                    "The pre-execution loan status was not usable, so the disbursal "
                    "cannot be told apart."
                ),
            )
        current = self._status(params)
        if current is None:
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail=(
                    "The loan status response was not usable, so the disbursal "
                    "cannot be told apart."
                ),
            )
        if current.lower() == "active":
            if before_status.lower() == "active":
                return ReconcileResult(
                    ReconcileStatus.UNKNOWN,
                    detail=(
                        "The loan was already Active before the attempt, so this "
                        "disbursal cannot be isolated."
                    ),
                )
            return ReconcileResult(
                ReconcileStatus.CONFIRMED,
                result={
                    "loanId": params.get("loan_id"),
                    "status": current,
                    "reconciled": True,
                    "operation": self.operation,
                    "note": "The disbursement is visible as the loan's Active status.",
                },
                backend_ref={"loan_status": current},
                detail="Loan transitioned to Active.",
            )
        if not _is_pre_disbursal_status(before_status) or not _is_pre_disbursal_status(current):
            return ReconcileResult(
                ReconcileStatus.UNKNOWN,
                detail=(
                    f"The loan status '{current}' cannot prove that no disbursement happened."
                ),
            )
        confirmed, detail = _absence_verdict(record, params)
        if not confirmed:
            return ReconcileResult(ReconcileStatus.UNKNOWN, detail=detail)
        return ReconcileResult(
            ReconcileStatus.ABSENT,
            detail=f"The loan is still '{current}', so it was not disbursed.",
        )


def loan_disbursal_reconciler() -> Reconciler:
    return LoanDisbursalReconciler()


__all__ = [
    "NewRecordReconciler",
    "LoanDisbursalReconciler",
    "classify_post",
    "post_money",
    "loan_repayment_reconciler",
    "loan_waive_interest_reconciler",
    "loan_charge_reconciler",
    "loan_disbursal_reconciler",
    "savings_charge_reconciler",
    "savings_deposit_reconciler",
    "savings_withdrawal_reconciler",
    "savings_interest_reconciler",
    "client_charge_reconciler",
]
