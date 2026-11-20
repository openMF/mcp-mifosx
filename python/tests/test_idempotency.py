# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Durability, concurrency and reconciliation tests for money-moving tool calls.

These tests exercise the real on-disk ledger (a pytest ``tmp_path``), not an
in-memory stand-in, so the restart and "two processes" cases read the files
back the same way a new process would.
"""

from __future__ import annotations

import json
import os
import threading
import time
from pathlib import Path
from typing import Any, Dict, List, Optional
from unittest.mock import patch

import pytest

from core import idempotency as idem
from tools.domains import loans as loans_domain
from tools.idempotent_money import (
    NewRecordReconciler,
    classify_post,
    loan_disbursal_reconciler,
    post_money,
    savings_interest_reconciler,
)
from tools.mcp_adapter import fineract_client

OPERATION = "test.money.move"
PAYLOAD = {"account_id": 7, "amount": 500, "payment_type_id": 1}


@pytest.fixture
def ledger(tmp_path: Path, monkeypatch: pytest.MonkeyPatch):
    """Point the default ledger at an isolated directory for one test."""
    monkeypatch.setenv(idem.KEY_ENV, str(tmp_path / "ledger"))
    monkeypatch.delenv(idem.REQUIRED_ENV, raising=False)
    monkeypatch.setenv(idem.LOCK_WAIT_ENV, "10")
    idem.reset_default_ledger()
    try:
        yield tmp_path / "ledger"
    finally:
        idem.reset_default_ledger()


def _request(payload: Optional[Dict[str, Any]] = None) -> idem.OperationRequest:
    return idem.OperationRequest(
        operation=OPERATION,
        resource="savings:7",
        payload=payload or PAYLOAD,
        reconciliation={"account_id": 7, "amount": 500},
    )


def _success(resource_id: int = 1):
    return lambda backend_key: idem.ExecutionResult(
        idem.Outcome.SUCCESS, result={"resourceId": resource_id}
    )


def _run(key, executor=None, reconciler=None, payload=None):
    return idem.execute_money_operation(
        _request(payload), key, executor or _success(), reconciler
    )


# ── basics ───────────────────────────────────────────────────────────


def test_first_execution_succeeds(ledger: Path) -> None:
    assert _run("client-key-0001") == {"resourceId": 1}
    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None
    assert record.state == idem.State.SUCCEEDED.value
    assert record.attempt == 1
    assert record.fingerprint == idem.request_fingerprint(OPERATION, PAYLOAD)


def test_duplicate_returns_stored_result_without_re_executing(ledger: Path) -> None:
    calls = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": len(calls)})

    assert _run("client-key-0001", executor) == {"resourceId": 1}
    assert _run("client-key-0001", executor) == {"resourceId": 1}
    assert len(calls) == 1
    assert calls[0] is not None


def test_key_reused_with_a_different_payload_is_rejected(ledger: Path) -> None:
    _run("client-key-0001")
    with pytest.raises(idem.IdempotencyKeyReused) as caught:
        _run("client-key-0001", payload={"account_id": 7, "amount": 501, "payment_type_id": 1})
    assert caught.value.code == "idempotency_key_reused"
    assert caught.value.correlation_id


def test_keys_are_scoped_by_operation(ledger: Path) -> None:
    calls = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": len(calls)})

    _run("client-key-0001", executor)
    other = idem.OperationRequest(operation="test.other", resource="savings:7", payload=PAYLOAD)
    assert idem.execute_money_operation(other, "client-key-0001", executor) == {"resourceId": 2}
    assert len(calls) == 2


def test_invalid_key_is_rejected_before_execution(ledger: Path) -> None:
    called = []
    with pytest.raises(idem.InvalidIdempotencyKey) as caught:
        _run("short", lambda key: called.append(key))
    assert called == []
    assert caught.value.http_status == 400


@pytest.mark.parametrize("key", ["has space-key", "with|pipe-key", "-leading-dash", "x" * 129])
def test_invalid_key_shapes_are_rejected(ledger: Path, key: str) -> None:
    with pytest.raises(idem.InvalidIdempotencyKey):
        _run(key)


def test_missing_key_is_allowed_by_default(ledger: Path) -> None:
    assert _run(None) == {"resourceId": 1}


def test_missing_key_can_be_made_strict(ledger: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(idem.REQUIRED_ENV, "true")
    with pytest.raises(idem.MissingIdempotencyKey):
        _run(None)


def test_absence_of_a_key_does_not_claim_an_unknown_failure(ledger: Path) -> None:
    def executor(_key):
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    result = _run(None, executor)
    assert result["error_code"] == "outcome_unknown"
    assert result["safe_to_retry"] is False


# ── durability and concurrency ───────────────────────────────────────


def test_restart_preserves_idempotency_state(ledger: Path) -> None:
    _run("client-key-0001", _success(11))
    # A new process has no in-memory state: it must read the record from disk.
    idem.reset_default_ledger()

    def must_not_run(_key):
        raise AssertionError("the operation was executed again after a restart")

    assert _run("client-key-0001", must_not_run) == {"resourceId": 11}


def test_concurrent_duplicates_execute_only_once(ledger: Path) -> None:
    calls: List[Optional[str]] = []
    results: List[Dict[str, Any]] = []
    errors: List[BaseException] = []

    def executor(backend_key):
        calls.append(backend_key)
        time.sleep(0.2)  # keep the claim open while the duplicate waits
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1})

    def worker(key: str) -> None:
        try:
            results.append(_run(key, executor))
        except BaseException as exc:  # pragma: no cover - reported below
            errors.append(exc)

    threads = [threading.Thread(target=worker, args=("client-key-0001",)) for _ in range(2)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)

    assert errors == []
    assert len(calls) == 1
    assert results == [{"resourceId": 1}, {"resourceId": 1}]


def test_concurrent_different_payloads_cannot_both_execute(ledger: Path) -> None:
    calls: List[Optional[str]] = []
    outcomes: List[str] = []

    def executor(backend_key):
        calls.append(backend_key)
        time.sleep(0.2)
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1})

    def worker(payload: Dict[str, Any]) -> None:
        try:
            _run("client-key-0001", executor, payload=payload)
            outcomes.append("executed")
        except idem.IdempotencyError as exc:
            outcomes.append(exc.code)

    payloads = [PAYLOAD, {**PAYLOAD, "amount": 501}]
    threads = [threading.Thread(target=worker, args=(p,)) for p in payloads]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join(timeout=10)

    assert len(calls) == 1
    assert "idempotency_key_reused" in outcomes


def test_in_progress_is_reported_without_executing(ledger: Path) -> None:
    current = idem.default_ledger()
    current.lock_wait_seconds = 0.2
    digest = idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    called = []
    with current.claim(digest, "holder"):
        with pytest.raises(idem.OperationInProgress) as caught:
            _run("client-key-0001", lambda key: called.append(key))
    assert called == []
    assert caught.value.idempotency_state == idem.State.IN_PROGRESS.value


def test_ledger_failure_prevents_execution(ledger: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    blocker = ledger.parent / "not-a-directory"
    blocker.write_text("x", encoding="utf-8")
    monkeypatch.setenv(idem.KEY_ENV, str(blocker / "ledger"))
    idem.reset_default_ledger()
    called = []
    with pytest.raises(idem.LedgerUnavailable):
        _run("client-key-0001", lambda key: called.append(key))
    assert called == []


def test_abandoned_lock_file_is_reacquired_without_deletion(ledger: Path) -> None:
    """A lock file left by a dead process is acquired via the OS lock.

    The file is never deleted: deleting a lock file is exactly what let a
    former owner remove a successor's claim. The OS releases the dead owner's
    lock, and the next owner overwrites the metadata with a fresh token.
    """
    current = idem.default_ledger()
    current.ensure_ready()
    digest = idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    lock = current.lock_path(digest)
    lock.write_text(
        json.dumps(
            {
                "claim_token": "dead-owner",
                "owner_pid": 1,
                "owner_host": "gone",
                "acquired_at": "2000-01-01T00:00:00.000000Z",
                "lease_expires_at": "2000-01-01T00:00:00.000000Z",
            }
        ),
        encoding="utf-8",
    )
    assert _run("client-key-0001") == {"resourceId": 1}
    assert lock.exists(), "the lock file must never be deleted"
    claim = json.loads(lock.read_text(encoding="utf-8"))
    assert claim["claim_token"] != "dead-owner"


@pytest.mark.parametrize("ttl", ["0", "-1", "not-a-number"])
def test_unsafe_lock_ttl_is_rejected(ledger: Path, monkeypatch: pytest.MonkeyPatch, ttl: str) -> None:
    monkeypatch.setenv(idem.LOCK_TTL_ENV, ttl)
    idem.reset_default_ledger()
    with pytest.raises(idem.LedgerUnavailable):
        _run("client-key-0001", lambda key: (_ for _ in ()).throw(AssertionError("executed")))


def test_claim_token_changes_on_each_acquisition(ledger: Path) -> None:
    current = idem.default_ledger()
    current.ensure_ready()
    digest = idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    path = current.lock_path(digest)
    with current.claim(digest, "corr-1") as first:
        first_token = first.token
        assert first._read_claim()["claim_token"] == first_token
    with current.claim(digest, "corr-2") as second:
        assert second.token != first_token
        assert second._read_claim()["claim_token"] == second.token
    # The lock file is stable: it is never deleted or renamed between owners.
    assert path.exists()


def test_release_leaves_a_successor_claim_untouched(ledger: Path) -> None:
    current = idem.default_ledger()
    current.ensure_ready()
    digest = idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    path = current.lock_path(digest)
    successor = {"claim_token": "successor-token", "owner_pid": 4242}
    with current.claim(digest, "corr-1") as handle:
        # Simulate a successor's claim body replacing the first owner's (the
        # held OS lock is the only thing that can make this happen safely).
        data = json.dumps(successor).encode("utf-8")
        os.lseek(handle.fd, 0, os.SEEK_SET)
        os.ftruncate(handle.fd, 0)
        os.write(handle.fd, data)
        os.fsync(handle.fd)
    assert path.exists(), "release must never delete the lock file"
    assert json.loads(path.read_text(encoding="utf-8")) == successor


def test_a_live_owner_is_never_preempted_by_lease_expiry(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A lease is a health signal, not a takeover mechanism.

    The OS lock is authoritative, so an execution that runs longer than the
    lease is not preempted and no second attempt can start behind it.
    """
    monkeypatch.setenv(idem.LOCK_TTL_ENV, "0.2")
    monkeypatch.setenv(idem.LOCK_WAIT_ENV, "0.3")
    idem.reset_default_ledger()
    calls: List[Optional[str]] = []
    outcomes: List[Any] = []

    def slow(backend_key):
        calls.append(backend_key)
        time.sleep(0.6)  # longer than the lease
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1})

    def worker(target):
        try:
            target()
        except idem.IdempotencyError as exc:
            outcomes.append(exc.code)

    first = threading.Thread(target=worker, args=(lambda: outcomes.append(_run("client-key-0001", slow)),))
    first.start()
    time.sleep(0.15)
    second = threading.Thread(target=worker, args=(lambda: outcomes.append(_run("client-key-0001", slow)),))
    second.start()
    first.join(timeout=10)
    second.join(timeout=10)

    assert len(calls) == 1, "the live owner was preempted and executed twice"
    assert "operation_in_progress" in outcomes


# ── reconciliation ───────────────────────────────────────────────────


class _Reconciler:
    def __init__(self, status: idem.ReconcileStatus, result: Optional[Dict[str, Any]] = None, detail: str = ""):
        self.status = status
        self.result = result
        self.detail = detail
        self.calls = 0

    def snapshot(self, request):
        return []

    def reconcile(self, record):
        self.calls += 1
        return idem.ReconcileResult(self.status, result=self.result, detail=self.detail)


def _ambiguous(_key):
    return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")


def test_ambiguous_outcome_is_reconciled_and_persisted(ledger: Path) -> None:
    confirmed = _Reconciler(idem.ReconcileStatus.CONFIRMED, result={"resourceId": 99, "reconciled": True})
    assert _run("client-key-0001", _ambiguous, confirmed) == {"resourceId": 99, "reconciled": True}
    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None and record.state == idem.State.SUCCEEDED.value
    # A later retry replays the persisted result without another backend call.
    assert _run("client-key-0001", lambda _k: (_ for _ in ()).throw(AssertionError("re-executed"))) == {
        "resourceId": 99,
        "reconciled": True,
    }


def test_unconfirmed_outcome_stays_unknown_and_is_never_repeated(ledger: Path) -> None:
    unknown = _Reconciler(idem.ReconcileStatus.UNKNOWN, detail="nothing to see")
    calls = []

    def executor(key):
        calls.append(key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    with pytest.raises(idem.ReconciliationRequired) as caught:
        _run("client-key-0001", executor, unknown)
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value

    # The retry reconciles again and still refuses to execute.
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, unknown)
    assert len(calls) == 1

    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None and record.state == idem.State.UNKNOWN.value


def test_confirmed_absence_without_verified_dedup_stays_unknown(ledger: Path) -> None:
    """Absence alone is never a failure and never authorises a retry.

    The default deployment has not proven that the backend deduplicates on
    the idempotency key, so a confirmed absence must stay UNKNOWN.
    """
    absent = _Reconciler(idem.ReconcileStatus.ABSENT, detail="no record")
    with pytest.raises(idem.ReconciliationRequired) as caught:
        _run("client-key-0001", _ambiguous, absent)
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None and record.state == idem.State.UNKNOWN.value


def test_conclusively_absent_attempt_permits_a_controlled_retry(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    absent = _Reconciler(idem.ReconcileStatus.ABSENT, detail="no record")
    first = _run("client-key-0001", _ambiguous, absent)
    assert first["error_code"] == "safe_retry_permitted"
    assert first["safe_to_retry"] is True
    assert first["idempotency_state"] == idem.State.RETRYABLE_ABSENT.value
    # The next request re-reconciles before executing, so a delayed write
    # cannot be repeated blindly.
    assert _run("client-key-0001", _success(7), absent) == {"resourceId": 7}
    assert absent.calls == 2


def test_conclusive_failure_is_retryable_with_the_same_key(ledger: Path) -> None:
    def rejected(_key):
        return idem.ExecutionResult(idem.Outcome.FAILURE, error="Validation Error: amount too large")

    first = _run("client-key-0001", rejected)
    assert first["idempotency_state"] == idem.State.FAILED.value
    assert first["safe_to_retry"] is True
    assert _run("client-key-0001", _success(3)) == {"resourceId": 3}


def test_new_record_reconciler_confirms_exactly_one_match(ledger: Path) -> None:
    marker = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0001")
    reads: List[Dict[str, Any]] = [
        {"items": [{"id": 1, "amount": 10}]},
        {"items": [{"id": 1, "amount": 10}, {"id": 2, "amount": 500, "externalId": marker}]},
    ]
    read_calls: List[int] = []

    def tracking_read(_params):
        value = reads[len(read_calls)]
        read_calls.append(1)
        return value

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=tracking_read,
        collection_key="items",
        matches=lambda item, params: item.get("amount") == params.get("amount"),
        build_result=lambda item, params: {"resourceId": item["id"], "reconciled": True},
    )
    assert _run("client-key-0001", _ambiguous, reconciler) == {"resourceId": 2, "reconciled": True}


def test_new_record_reconciler_does_not_confirm_from_amount_and_type_alone(ledger: Path) -> None:
    """A lookalike transaction without this request's marker is not proof."""
    reads: List[Dict[str, Any]] = [
        {"items": [{"id": 1, "amount": 10}]},
        {"items": [{"id": 1, "amount": 10}, {"id": 2, "amount": 500}]},
    ]
    read_calls: List[int] = []

    def tracking_read(_params):
        value = reads[len(read_calls)]
        read_calls.append(1)
        return value

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=tracking_read,
        collection_key="items",
        matches=lambda item, params: item.get("amount") == params.get("amount"),
        build_result=lambda item, params: {"resourceId": item["id"], "reconciled": True},
    )
    with pytest.raises(idem.ReconciliationRequired) as caught:
        _run("client-key-0001", _ambiguous, reconciler)
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value


def test_new_record_reconciler_waits_for_confirmed_absence(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "2")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: {"items": [{"id": 1, "amount": 10}]},
        collection_key="items",
        matches=lambda item, params: False,
        build_result=lambda item, params: {},
    )
    # One empty read is not proof: it stays UNKNOWN.
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", _ambiguous, reconciler)
    # The second spaced read confirms absence, but without verified backend
    # dedup the operation still must not be repeated automatically.
    with pytest.raises(idem.ReconciliationRequired) as caught:
        _run("client-key-0001", _ambiguous, reconciler)
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None and record.state == idem.State.UNKNOWN.value


def test_new_record_reconciler_without_snapshot_is_unknown(ledger: Path) -> None:
    base = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: {"items": []},
        collection_key="items",
        matches=lambda item, params: False,
        build_result=lambda item, params: {},
    )

    class BrokenSnapshot:
        def snapshot(self, request):
            raise RuntimeError("snapshot unavailable")

        def reconcile(self, record):
            return base.reconcile(record)

    # The engine catches the snapshot failure, then the reconciler refuses to
    # guess whether a record it cannot distinguish from an older one is new.
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", _ambiguous, BrokenSnapshot())


# ── attempt-scoped absence evidence (F1) ─────────────────────────────


def test_empty_reads_never_execute_a_second_attempt(ledger: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "2")
    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: {"items": []},
        collection_key="items",
        matches=lambda item, params: False,
        build_result=lambda item, params: {},
    )
    for _ in range(4):
        with pytest.raises(idem.ReconciliationRequired):
            _run("client-key-0001", executor, reconciler)
    assert len(calls) == 1, "empty reads must never authorise a second attempt"


def test_new_attempt_resets_absence_evidence_and_records_timestamps(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "2")
    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: {"items": []},
        collection_key="items",
        matches=lambda item, params: False,
        build_result=lambda item, params: {},
    )
    # Attempt 1: first empty read cannot prove absence.
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", _ambiguous, reconciler)
    # Attempt 1: second empty read confirms absence, but no execution yet.
    retryable = _run("client-key-0001", _ambiguous, reconciler)
    assert retryable["idempotency_state"] == idem.State.RETRYABLE_ABSENT.value
    # Attempt 2: re-reconciles, then resets the evidence and executes.
    assert _run("client-key-0001", _success(7), reconciler) == {"resourceId": 7}

    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None
    assert record.attempt == 2
    assert record.attempt_started_at
    assert record.attempt_ended_at
    assert "absence_reads" not in record.reconcile, "a new attempt must start with no absence evidence"
    assert "absence_attempt" not in record.reconcile


def test_ambiguous_outcome_survives_a_restart(ledger: Path) -> None:
    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    unknown = _Reconciler(idem.ReconcileStatus.UNKNOWN, detail="cannot tell")
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, unknown)

    # A new process reads the same record from disk: it reconciles again and
    # still refuses to repeat the operation.
    idem.reset_default_ledger()
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, unknown)
    assert len(calls) == 1

    record = idem.default_ledger().read(
        idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    )
    assert record is not None
    assert record.state == idem.State.UNKNOWN.value
    assert record.attempt_started_at and record.attempt_ended_at


def test_legacy_absence_failure_is_reconciled_not_retried(ledger: Path) -> None:
    """A ``FAILED`` record derived from absence must never be retried blind."""
    directory = idem.default_ledger()
    directory.ensure_ready()
    calls: List[Optional[str]] = []

    def write_legacy(key: str) -> None:
        digest = idem.key_hash(idem.default_scope(), OPERATION, key)
        directory.write(
            idem.LedgerRecord(
                key_hash=digest,
                operation=OPERATION,
                scope=idem.default_scope(),
                fingerprint=idem.request_fingerprint(OPERATION, PAYLOAD),
                state=idem.State.FAILED.value,
                attempt=1,
                created_at=idem._now(),
                updated_at=idem._now(),
                correlation_id="legacy",
                attempt_started_at=idem._now(),
                attempt_ended_at=idem._now(),
                request={"resource": "savings:7"},
                reconcile={"absence_reads": 2, "account_id": 7, "amount": 500},
                error={
                    "code": "safe_retry_permitted",
                    "message": "The backend has no record of this operation.",
                    "retryable": True,
                },
            )
        )

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1})

    # UNKNOWN: refuse and never execute.
    unknown = _Reconciler(idem.ReconcileStatus.UNKNOWN, detail="still cannot tell")
    write_legacy("client-key-0001")
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, unknown)
    assert calls == []

    # CONFIRMED: adopt the reconciled result instead of executing.
    confirmed = _Reconciler(
        idem.ReconcileStatus.CONFIRMED, result={"resourceId": 5, "reconciled": True}
    )
    write_legacy("client-key-0002")
    assert _run("client-key-0002", executor, confirmed) == {
        "resourceId": 5,
        "reconciled": True,
    }
    assert calls == []


# ── unusable reconciliation reads (F3) ───────────────────────────────


@pytest.mark.parametrize(
    "bad_payload",
    [
        {},  # collection omitted entirely
        {"items": "not-a-list"},
        {"items": [1, 2]},
        {"items": [{"id": 1}, "malformed"]},
        {"error": "Internal Server Error", "status_code": 500},
        {"error": "Read timed out."},
    ],
)
def test_unusable_reconciliation_read_is_unknown(ledger: Path, bad_payload: Dict[str, Any]) -> None:
    reads: List[Any] = [{"items": []}, bad_payload]
    calls: List[int] = []

    def read(_params):
        value = reads[min(len(calls), len(reads) - 1)]
        calls.append(1)
        return value

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=read,
        collection_key="items",
        matches=lambda item, params: item.get("amount") == params.get("amount"),
        build_result=lambda item, params: {"resourceId": item["id"], "reconciled": True},
    )
    with pytest.raises(idem.ReconciliationRequired) as caught:
        _run("client-key-0001", _ambiguous, reconciler)
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value


def test_delayed_visibility_confirms_after_a_late_record(ledger: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "2")
    marker = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0001")
    reads: List[Any] = [
        {"items": []},
        {"items": []},
        {"items": []},
        {"items": [{"id": 2, "amount": 500, "externalId": marker}]},
    ]
    calls: List[int] = []

    def read(_params):
        value = reads[min(len(calls), len(reads) - 1)]
        calls.append(1)
        return value

    def executor(_key):
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=read,
        collection_key="items",
        matches=lambda item, params: item.get("amount") == params.get("amount"),
        build_result=lambda item, params: {"resourceId": item["id"], "reconciled": True},
    )
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, reconciler)
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", executor, reconciler)
    # The late record appears; a subsequent request confirms it without a
    # second execution.
    assert _run("client-key-0001", executor, reconciler) == {"resourceId": 2, "reconciled": True}


def test_multiple_matching_markers_are_ambiguous(ledger: Path) -> None:
    marker = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0001")
    reads: List[Any] = [
        {"items": []},
        {
            "items": [
                {"id": 2, "amount": 500, "externalId": marker},
                {"id": 3, "amount": 500, "externalId": marker},
            ]
        },
    ]
    calls: List[int] = []

    def read(_params):
        value = reads[min(len(calls), len(reads) - 1)]
        calls.append(1)
        return value

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=read,
        collection_key="items",
        matches=lambda item, params: item.get("amount") == params.get("amount"),
        build_result=lambda item, params: {"resourceId": item["id"]},
    )
    with pytest.raises(idem.ReconciliationRequired):
        _run("client-key-0001", _ambiguous, reconciler)


def test_disbursal_snapshot_failure_is_not_treated_as_confirmed(ledger: Path) -> None:
    request = idem.OperationRequest(
        operation="loan.disburse",
        resource="loan:8",
        payload={"loan_id": 8},
        reconciliation={"loan_id": 8},
    )
    with patch.object(
        fineract_client,
        "execute_get",
        side_effect=[{"error": "backend down"}, {"status": {"value": "Active"}}],
    ):
        with pytest.raises(idem.ReconciliationRequired) as caught:
            idem.execute_money_operation(
                request,
                "disburse-key-0001",
                lambda _k: idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out."),
                loan_disbursal_reconciler(),
            )
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value


def test_savings_interest_without_marker_stays_unknown(ledger: Path) -> None:
    request = idem.OperationRequest(
        operation="savings.post_interest",
        resource="savings:7",
        payload={"account_id": 7},
        reconciliation={"account_id": 7, "amount": 12.5},
    )
    before = {"transactions": []}
    after = {
        "transactions": [
            {
                "id": 9,
                "amount": 12.5,
                "type": {
                    "code": "savingsTransactionType.interestPosting",
                    "value": "Interest Posting",
                },
            }
        ]
    }
    with patch.object(fineract_client, "execute_get", side_effect=[before, after]):
        with pytest.raises(idem.ReconciliationRequired) as caught:
            idem.execute_money_operation(
                request,
                "interest-key-0001",
                lambda _k: idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out."),
                savings_interest_reconciler(),
            )
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value


# ── malformed collections (F2) ───────────────────────────────────────


def test_bare_list_is_rejected_where_an_object_is_required(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A bare JSON array must not stand in for ``{"items": [...]}``."""
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    calls: List[str] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    # The endpoint contract for loan/savings detail reads is an object that
    # contains the collection. An empty bare list would otherwise look exactly
    # like a confirmed absence and authorise the retry below.
    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: [],
        collection_key="items",
        matches=lambda item, params: True,
        build_result=lambda item, params: {"resourceId": 1},
    )
    for _ in range(2):
        with pytest.raises(idem.ReconciliationRequired) as caught:
            _run("client-key-0001", executor, reconciler)
        assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    assert len(calls) == 1, "a bare list must never authorise a retry"


def test_array_endpoint_can_opt_out_of_the_object_contract(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """client-charge reads return a bare array and must keep working."""
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    calls: List[str] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    reconciler = NewRecordReconciler(
        operation=OPERATION,
        read=lambda _params: [],
        collection_key="charges",
        matches=lambda item, params: False,
        build_result=lambda item, params: {},
        expect_object=False,
    )
    first = _run("client-key-0001", executor, reconciler)
    assert first["error_code"] == "safe_retry_permitted"
    assert _run("client-key-0001", _success(4), reconciler) == {"resourceId": 4}
    assert len(calls) == 1


# ── legacy records with unusable persisted fields (F3) ───────────────


@pytest.mark.parametrize(
    "error",
    [
        "a legacy string error",
        None,
        {"message": "no classification"},
        {"code": 7},
        {"code": "an_unknown_classification"},
    ],
)
def test_legacy_failed_record_with_unusable_error_fails_closed(
    ledger: Path, error: Any
) -> None:
    directory = idem.default_ledger()
    directory.ensure_ready()
    directory.write(
        idem.LedgerRecord(
            key_hash=idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001"),
            operation=OPERATION,
            scope=idem.default_scope(),
            fingerprint=idem.request_fingerprint(OPERATION, PAYLOAD),
            state=idem.State.FAILED.value,
            attempt=1,
            created_at=idem._now(),
            updated_at=idem._now(),
            correlation_id="legacy",
            attempt_started_at=idem._now(),
            attempt_ended_at=idem._now(),
            request={"resource": "savings:7"},
            reconcile={"account_id": 7, "amount": 500},
            error=error,
        )
    )
    calls: List[str] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1})

    with pytest.raises(idem.LedgerUnavailable) as caught:
        _run("client-key-0001", executor, _Reconciler(idem.ReconcileStatus.UNKNOWN))
    assert caught.value.idempotency_state == idem.State.FAILED.value
    assert calls == [], "a corrupt legacy record must never start another attempt"


# ── invalid reconciliation statuses (F7) ─────────────────────────────


class _BogusReconciler:
    """Returns whatever a buggy reconciler might return."""

    def __init__(self, result: Any) -> None:
        self.result = result

    def snapshot(self, request):
        return None

    def reconcile(self, record):
        return self.result


@pytest.mark.parametrize(
    "result",
    [
        idem.ReconcileResult("MAYBE"),
        idem.ReconcileResult("ABSENT"),
        "ABSENT",
        None,
    ],
)
def test_invalid_reconciliation_status_fails_closed(
    ledger: Path, monkeypatch: pytest.MonkeyPatch, result: Any
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    calls: List[str] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    reconciler = _BogusReconciler(result)
    for _ in range(2):
        with pytest.raises(idem.ReconciliationRequired) as caught:
            _run("client-key-0001", executor, reconciler)
        assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    assert len(calls) == 1, "an unrecognised status must never authorise a retry"


# ── disbursal status vocabulary (F1 parity) ──────────────────────────


@pytest.mark.parametrize(
    "payload",
    [
        {"id": 8},
        {"id": 8, "status": None},
        {"id": 8, "status": "Active"},
        {"id": 8, "status": 7},
        {"id": 8, "status": {"value": None}},
        {"id": 8, "status": {"value": 7}},
        {"id": 8, "status": {"value": {"id": 1}}},
        {"id": 8, "status": {"value": "Teleported"}},
        {"id": 8, "status": {}},
        [],
    ],
)
def test_disbursal_unusable_status_stays_unknown(
    ledger: Path, monkeypatch: pytest.MonkeyPatch, payload: Any
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    request = idem.OperationRequest(
        operation="loan.disburse",
        resource="loan:8",
        payload={"loan_id": 8},
        reconciliation={"loan_id": 8},
    )
    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    with patch.object(fineract_client, "execute_get", return_value=payload):
        for _ in range(3):
            with pytest.raises(idem.ReconciliationRequired) as caught:
                idem.execute_money_operation(
                    request, "disburse-key-0001", executor, loan_disbursal_reconciler()
                )
            assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    assert len(calls) == 1, "an unusable status must never authorise a disbursal retry"


def test_disbursal_failed_status_read_stays_unknown(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    request = idem.OperationRequest(
        operation="loan.disburse",
        resource="loan:8",
        payload={"loan_id": 8},
        reconciliation={"loan_id": 8},
    )
    responses = iter([{"status": {"value": "Approved"}}])

    def read(_endpoint):
        return next(responses, {"error": "backend down"})

    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    with patch.object(fineract_client, "execute_get", side_effect=read):
        for _ in range(2):
            with pytest.raises(idem.ReconciliationRequired) as caught:
                idem.execute_money_operation(
                    request, "disburse-key-0001", executor, loan_disbursal_reconciler()
                )
            assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    assert len(calls) == 1


def test_disbursal_closed_loan_is_not_absence(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    request = idem.OperationRequest(
        operation="loan.disburse",
        resource="loan:8",
        payload={"loan_id": 8},
        reconciliation={"loan_id": 8},
    )
    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    with patch.object(
        fineract_client,
        "execute_get",
        side_effect=[
            {"status": {"value": "Approved"}},
            {"status": {"value": "Closed (obligations met)"}},
        ],
    ):
        with pytest.raises(idem.ReconciliationRequired) as caught:
            idem.execute_money_operation(
                request, "disburse-key-0001", executor, loan_disbursal_reconciler()
            )
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value
    assert len(calls) == 1


def test_disbursal_pre_disbursement_absence_allows_controlled_retry(
    ledger: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The absence path stays reachable when both statuses are pre-disbursal."""
    monkeypatch.setenv(idem.BACKEND_DEDUP_ENV, "true")
    monkeypatch.setenv(idem.ABSENCE_WINDOW_ENV, "0")
    monkeypatch.setenv(idem.ABSENCE_READS_ENV, "1")
    request = idem.OperationRequest(
        operation="loan.disburse",
        resource="loan:8",
        payload={"loan_id": 8},
        reconciliation={"loan_id": 8},
    )
    calls: List[Optional[str]] = []

    def executor(backend_key):
        calls.append(backend_key)
        return idem.ExecutionResult(idem.Outcome.AMBIGUOUS, error="Read timed out.")

    with patch.object(
        fineract_client,
        "execute_get",
        return_value={"status": {"value": "Approved"}},
    ):
        first = idem.execute_money_operation(
            request, "disburse-key-0001", executor, loan_disbursal_reconciler()
        )
        assert first["error_code"] == "safe_retry_permitted"
        assert first["idempotency_state"] == idem.State.RETRYABLE_ABSENT.value
        assert idem.execute_money_operation(
            request, "disburse-key-0001", _success(8), loan_disbursal_reconciler()
        ) == {"resourceId": 8}
    assert len(calls) == 1


# ── heartbeat/release synchronisation (F5) ───────────────────────────


def test_release_never_closes_the_descriptor_during_a_heartbeat_write(
    ledger: Path,
) -> None:
    directory = idem.default_ledger()
    digest = idem.key_hash(idem.default_scope(), OPERATION, "client-key-0001")
    with directory.claim(digest, "correlation") as handle:
        entered = threading.Event()
        finish = threading.Event()
        original_fsync = os.fsync

        def slow_fsync(fd):
            if fd == handle.fd:
                entered.set()
                assert finish.wait(5), "the test never allowed the write to finish"
            return original_fsync(fd)

        def heartbeat_write() -> None:
            with patch.object(idem.os, "fsync", side_effect=slow_fsync):
                try:
                    handle.write_claim()
                except idem.LedgerUnavailable:
                    pass

        writer = threading.Thread(target=heartbeat_write)
        writer.start()
        assert entered.wait(5), "the heartbeat write never started"

        released = threading.Event()

        def do_release() -> None:
            handle.release()
            released.set()

        releaser = threading.Thread(target=do_release)
        releaser.start()
        # The descriptor must stay open while the write is in flight.
        assert not released.wait(0.2), "release closed the descriptor during a write"
        finish.set()
        writer.join(5)
        releaser.join(5)
        assert released.is_set(), "release never completed after the write finished"

    with pytest.raises(OSError):
        os.fstat(handle.fd)
    with pytest.raises(idem.LedgerUnavailable):
        handle.write_claim()


# ── fingerprints and shared-format guarantees ────────────────────────


def test_fingerprint_is_order_and_number_type_independent() -> None:
    left = idem.request_fingerprint("op", {"b": 2, "a": 1.0, "items": [1, True, None]})
    right = idem.request_fingerprint("op", {"items": [1.0, True, None], "a": 1, "b": 2})
    assert left == right


def test_fingerprint_changes_with_the_payload() -> None:
    assert idem.request_fingerprint("op", {"a": 1}) != idem.request_fingerprint("op", {"a": 2})


def test_backend_key_is_deterministic_and_hides_the_client_key(ledger: Path) -> None:
    first = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0001")
    second = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0001")
    other = idem.backend_idempotency_key(idem.default_scope(), OPERATION, "client-key-0002")
    assert first == second
    assert first != other
    assert first.startswith("mcp-")
    assert "client-key-0001" not in first


def test_scope_separates_authenticated_actors(ledger: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    calls = []
    _run("client-key-0001", lambda key: calls.append(key) or idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 1}))
    monkeypatch.setenv(idem.SCOPE_ACTOR_ENV, "another-user")
    _run("client-key-0001", lambda key: calls.append(key) or idem.ExecutionResult(idem.Outcome.SUCCESS, result={"resourceId": 2}))
    assert len(calls) == 2


# ── operation classification ─────────────────────────────────────────


@pytest.mark.parametrize(
    ("body", "outcome"),
    [
        ({"resourceId": 1}, idem.Outcome.SUCCESS),
        ({"error": "Validation Error: nope", "status_code": 400}, idem.Outcome.FAILURE),
        ({"error": "not found", "status_code": 404}, idem.Outcome.FAILURE),
        ({"error": "server exploded", "status_code": 500}, idem.Outcome.AMBIGUOUS),
        ({"error": "conflict", "status_code": 409}, idem.Outcome.AMBIGUOUS),
        ({"error": "slow down", "status_code": 429}, idem.Outcome.AMBIGUOUS),
        ({"error": "Read timed out."}, idem.Outcome.AMBIGUOUS),
        ({"error": "Connection failed: connection refused"}, idem.Outcome.AMBIGUOUS),
        ({"error": "Validation Error: bad date"}, idem.Outcome.FAILURE),
    ],
)
def test_classify_post(body: Dict[str, Any], outcome: idem.Outcome) -> None:
    assert classify_post(body).outcome is outcome


# ── domain integration: a real repayment ─────────────────────────────

LOAN_BEFORE = {"transactions": [{"id": 1, "amount": 150, "type": {"value": "Repayment"}}]}
LOAN_AFTER = {
    "transactions": [
        {"id": 1, "amount": 150, "type": {"value": "Repayment"}},
        {"id": 2, "amount": 150, "type": {"value": "Repayment"}},
    ]
}


def _repay(key: str):
    with patch("tools.domains.loans.get_fineract_today", return_value="5 November 2026"):
        return loans_domain.make_loan_repayment.func(8, 150, 4, idempotency_key=key)


def test_repayment_duplicate_replays_without_a_second_post(ledger: Path) -> None:
    with patch.object(fineract_client, "execute_get", return_value=LOAN_BEFORE), patch.object(
        fineract_client, "execute_post", return_value={"resourceId": 21, "transactionId": 21}
    ) as post:
        first = _repay("repay-key-0001")
        second = _repay("repay-key-0001")
    assert first == {"resourceId": 21, "transactionId": 21}
    assert second == first
    assert post.call_count == 1


def test_repayment_timeout_after_success_is_not_confirmed_from_amount_alone(ledger: Path) -> None:
    with patch.object(
        fineract_client, "execute_get", side_effect=[LOAN_BEFORE, LOAN_AFTER]
    ) as get, patch.object(
        fineract_client, "execute_post", return_value={"error": "Read timed out."}
    ) as post:
        with pytest.raises(idem.ReconciliationRequired) as caught:
            _repay("repay-key-0001")
    assert post.call_count == 1
    assert get.call_count == 2
    assert caught.value.idempotency_state == idem.State.UNKNOWN.value


def test_repayment_timeout_is_reconciled_via_unique_marker(ledger: Path) -> None:
    marker = idem.backend_idempotency_key(
        idem.default_scope(), "loan.repayment", "repay-key-0001"
    )
    after = {
        "transactions": [
            {"id": 1, "amount": 150, "type": {"value": "Repayment"}},
            {"id": 2, "amount": 150, "type": {"value": "Repayment"}, "externalId": marker},
        ]
    }
    with patch.object(
        fineract_client, "execute_get", side_effect=[LOAN_BEFORE, after]
    ) as get, patch.object(
        fineract_client, "execute_post", return_value={"error": "Read timed out."}
    ) as post:
        result = _repay("repay-key-0001")
    assert post.call_count == 1
    assert get.call_count == 2
    assert result["transactionId"] == 2
    assert result["reconciled"] is True


def test_repayment_unknown_outcome_is_not_repeated(ledger: Path) -> None:
    with patch.object(
        fineract_client, "execute_get", side_effect=[LOAN_BEFORE, {"error": "backend down"}]
    ), patch.object(fineract_client, "execute_post", return_value={"error": "Read timed out."}) as post:
        with pytest.raises(idem.ReconciliationRequired):
            _repay("repay-key-0001")
    assert post.call_count == 1


def test_post_money_sends_the_backend_idempotency_header(ledger: Path) -> None:
    seen: List[Dict[str, str]] = []

    def fake_post(endpoint, body, extra_headers=None):
        seen.append(extra_headers or {})
        return {"resourceId": 5}

    with patch.object(fineract_client, "execute_post", side_effect=fake_post):
        result = post_money(
            operation=OPERATION,
            resource="savings:7",
            payload=PAYLOAD,
            endpoint="savingsaccounts/7/transactions?command=deposit",
            body={"transactionAmount": 500},
            idempotency_key="client-key-0001",
        )
    assert result == {"resourceId": 5}
    assert list(seen[0]) == ["Idempotency-Key"]
    assert seen[0]["Idempotency-Key"].startswith("mcp-")
    assert "client-key-0001" not in seen[0]["Idempotency-Key"]


# ── cross-language contract with the Go server ───────────────────────

VECTORS_PATH = (
    Path(__file__).resolve().parents[2]
    / "go"
    / "idempotency"
    / "testdata"
    / "idempotency_vectors.json"
)


@pytest.fixture(scope="module")
def vectors() -> Dict[str, Any]:
    """The fixture the Go suite asserts against, generated by this module.

    If the two implementations ever disagree about canonical JSON, key hashes
    or the record schema, one of the two suites fails.
    """
    if not VECTORS_PATH.is_file():
        pytest.skip(f"shared vectors not found at {VECTORS_PATH}")
    data = json.loads(VECTORS_PATH.read_text(encoding="utf-8"))
    assert data["version"] == idem.LEDGER_VERSION
    return data


def test_shared_fingerprint_vectors_match(vectors: Dict[str, Any]) -> None:
    assert vectors["fingerprints"], "the shared fixture has no fingerprint vectors"
    for vector in vectors["fingerprints"]:
        operation, payload = vector["operation"], vector["payload"]
        assert idem._canonical({"operation": operation, "payload": payload}) == vector["canonical"]
        assert idem.request_fingerprint(operation, payload) == vector["fingerprint"]


def test_shared_key_vectors_match(vectors: Dict[str, Any]) -> None:
    assert vectors["keys"], "the shared fixture has no key vectors"
    for vector in vectors["keys"]:
        scope, operation, key = vector["scope"], vector["operation"], vector["key"]
        assert idem.key_hash(scope, operation, key) == vector["key_hash"]
        assert idem.backend_idempotency_key(scope, operation, key) == vector["backend_key"]
        assert key not in vector["backend_key"]


def test_shared_scope_vector_matches(
    vectors: Dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    for name, value in vectors["scope_env"].items():
        monkeypatch.setenv(name, value)
    assert idem.default_scope() == vectors["scope"]


def test_records_written_by_go_are_readable(
    vectors: Dict[str, Any], ledger: Path
) -> None:
    """The Go server's records use the same schema and can be replayed here."""
    directory = idem.default_ledger()
    directory.ensure_ready()
    for vector in vectors["records"]:
        record = idem.LedgerRecord.from_dict(vector["record"])
        path = directory.record_path(record.key_hash)
        path.write_text(json.dumps(vector["record"]), encoding="utf-8")
        reread = directory.read(record.key_hash)
        assert reread is not None
        assert reread.to_dict() == vector["record"]


def test_go_success_record_is_replayed_without_executing(
    vectors: Dict[str, Any], ledger: Path
) -> None:
    vector = next(item for item in vectors["records"] if item["name"] == "succeeded")
    directory = idem.default_ledger()
    directory.ensure_ready()
    record = idem.LedgerRecord.from_dict(vector["record"])
    directory.record_path(record.key_hash).write_text(
        json.dumps(vector["record"]), encoding="utf-8"
    )

    def must_not_run(_backend_key):
        raise AssertionError("a Go-written success must be replayed, not executed")

    result = idem.execute_money_operation(
        idem.OperationRequest(
            operation=vector["operation"],
            resource="loans/8/transactions?command=repayment",
            payload=vector["payload"],
        ),
        vector["key"],
        must_not_run,
        None,
        scope=vector["scope"],
    )
    assert result == vector["record"]["result"]
