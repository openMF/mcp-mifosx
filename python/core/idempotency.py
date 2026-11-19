# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Durable idempotency and ambiguous-outcome reconciliation.

Money-moving calls are not safe to repeat on a timeout. This module gives the
MCP server and the REST bridge a shared, on-disk ledger keyed by a
client-supplied idempotency key. It records an explicit state for every
operation, so a retry can replay a stored success, reconcile an ambiguous
outcome against Fineract, or refuse to run when the outcome is unknown.

The ledger is deliberately a file store, not an in-memory map:

* every record is written to a temporary file and atomically renamed, so a
  crash cannot leave a half-written record;
* each key is guarded by an exclusive lock file, so two concurrent requests
  cannot execute the same operation;
* the record schema and the key/fingerprint algorithms are mirrored by
  ``go/idempotency`` so both implementations can share one ledger directory.

Layout (all file names are SHA-256 hashes, never raw client data)::

    <dir>/keys/<key_hash>.json    the record
    <dir>/keys/<key_hash>.lock    the in-flight claim

Limitations are documented in ``python/README.md``. In particular this is a
single-host ledger: point ``MCP_IDEMPOTENCY_DIR`` at a shared volume when more
than one process can serve the same tenant, or replace the ledger with a
transactional store.
"""

from __future__ import annotations

import errno
import hashlib
import json
import logging
import math
import os
import re
import socket
import threading
import time
import uuid
from contextlib import contextmanager
from dataclasses import dataclass, field
from datetime import datetime, timedelta, timezone
from enum import Enum
from pathlib import Path
from typing import Any, Callable, Dict, Iterator, List, Mapping, Optional, Protocol

if os.name == "nt":  # pragma: no cover - platform selection
    import msvcrt
else:  # pragma: no cover - platform selection
    import fcntl  # type: ignore[import-not-found]

logger = logging.getLogger(__name__)

LEDGER_VERSION = 1

KEY_ENV = "MCP_IDEMPOTENCY_DIR"
REQUIRED_ENV = "MCP_IDEMPOTENCY_REQUIRED"
LOCK_TTL_ENV = "MCP_IDEMPOTENCY_LOCK_TTL_SECONDS"
LOCK_WAIT_ENV = "MCP_IDEMPOTENCY_LOCK_WAIT_SECONDS"
ABSENCE_READS_ENV = "MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS"
ABSENCE_WINDOW_ENV = "MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS"
BACKEND_DEDUP_ENV = "MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED"
BACKEND_HEADER_ENV = "MIFOSX_IDEMPOTENCY_HEADER"
SCOPE_ACTOR_ENV = "MCP_IDEMPOTENCY_ACTOR"

DEFAULT_LOCK_TTL_SECONDS = 900.0
DEFAULT_LOCK_WAIT_SECONDS = 5.0
DEFAULT_ABSENCE_READS = 2
DEFAULT_ABSENCE_WINDOW_SECONDS = 30.0
DEFAULT_BACKEND_HEADER = "Idempotency-Key"

# 8-128 characters, starts with an alphanumeric. "|" and whitespace are
# excluded because the key is part of a delimited hash input.
KEY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")


# ── states and outcomes ──────────────────────────────────────────────


class State(str, Enum):
    """Persisted operation states.

    ``FAILED`` means the backend conclusively rejected the request, so a new
    attempt is safe. ``RETRYABLE_ABSENT`` is only reachable when backend
    deduplication has been explicitly verified: the attempt was observed
    absent after the safety window, and one more retry may execute. It still
    reconciles before that retry, so repeated requests never bypass
    reconciliation.
    """

    PENDING = "PENDING"
    IN_PROGRESS = "IN_PROGRESS"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"
    UNKNOWN = "UNKNOWN"
    RETRYABLE_ABSENT = "RETRYABLE_ABSENT"


class Outcome(str, Enum):
    """How a backend call ended, from the caller's point of view."""

    SUCCESS = "SUCCESS"
    FAILURE = "FAILURE"
    AMBIGUOUS = "AMBIGUOUS"


class ReconcileStatus(str, Enum):
    """What a reconciliation read proved."""

    CONFIRMED = "CONFIRMED"
    ABSENT = "ABSENT"
    UNKNOWN = "UNKNOWN"


# ── structured errors ────────────────────────────────────────────────


class IdempotencyError(Exception):
    """A typed idempotency failure safe to return to a caller."""

    code = "idempotency_error"
    http_status = 409
    retryable = False

    def __init__(
        self,
        message: str,
        *,
        correlation_id: Optional[str] = None,
        idempotency_state: Optional[str] = None,
        details: Optional[Mapping[str, Any]] = None,
    ) -> None:
        super().__init__(message)
        self.correlation_id = correlation_id
        self.idempotency_state = idempotency_state
        self.details = dict(details or {})

    def to_dict(self) -> Dict[str, Any]:
        """JSON-safe error body. Never includes request data or credentials."""
        body: Dict[str, Any] = {
            "error": str(self),
            "error_code": self.code,
            "correlation_id": self.correlation_id,
            "retryable": self.retryable,
        }
        if self.idempotency_state is not None:
            body["idempotency_state"] = self.idempotency_state
        if self.details:
            body["details"] = self.details
        return body

    def user_message(self) -> str:
        """Single-line message for MCP, which carries only text."""
        suffix = f" (correlation_id={self.correlation_id})" if self.correlation_id else ""
        return f"{self.code}: {self}{suffix}"


class MissingIdempotencyKey(IdempotencyError):
    code = "missing_idempotency_key"
    http_status = 400
    retryable = True


class InvalidIdempotencyKey(IdempotencyError):
    code = "invalid_idempotency_key"
    http_status = 400
    retryable = True


class IdempotencyKeyReused(IdempotencyError):
    code = "idempotency_key_reused"
    http_status = 409
    retryable = False


class OperationInProgress(IdempotencyError):
    code = "operation_in_progress"
    http_status = 409
    retryable = True


class OutcomeUnknown(IdempotencyError):
    code = "outcome_unknown"
    http_status = 409
    retryable = False


class ReconciliationRequired(IdempotencyError):
    code = "reconciliation_required"
    http_status = 409
    retryable = False


class LedgerUnavailable(IdempotencyError):
    code = "ledger_unavailable"
    http_status = 503
    retryable = True


# ── canonical request fingerprint ────────────────────────────────────


def _canonical(value: Any) -> str:
    """Deterministic JSON text shared with ``go/idempotency/canonical.go``.

    Numbers are rounded to 10 decimal places and trailing zeros are removed so
    an integer and the same value decoded as a float fingerprint identically in
    Python (int/float) and Go (float64 from ``encoding/json``).
    """
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        if not math.isfinite(value):
            raise ValueError("non-finite number in idempotency payload")
        if value == 0:
            return "0"
        text = f"{value:.10f}".rstrip("0").rstrip(".")
        return "0" if text in ("-0", "") else text
    if isinstance(value, str):
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, (list, tuple)):
        return "[" + ",".join(_canonical(item) for item in value) + "]"
    if isinstance(value, Mapping):
        parts: List[str] = []
        for key in sorted(value.keys()):
            if not isinstance(key, str):
                raise TypeError("idempotency payload keys must be strings")
            parts.append(json.dumps(key, ensure_ascii=False) + ":" + _canonical(value[key]))
        return "{" + ",".join(parts) + "}"
    raise TypeError(f"unsupported idempotency payload type: {type(value).__name__}")


def request_fingerprint(operation: str, payload: Mapping[str, Any]) -> str:
    """SHA-256 of the canonical operation + payload."""
    material = _canonical({"operation": operation, "payload": payload})
    return hashlib.sha256(material.encode("utf-8")).hexdigest()


def default_scope() -> str:
    """Tenant + hashed authenticated identity, mirrored by the Go client."""
    tenant = (os.getenv("MIFOSX_TENANT_ID") or "default").strip() or "default"
    actor = os.getenv(SCOPE_ACTOR_ENV) or os.getenv("MIFOSX_USERNAME") or "anonymous"
    actor_hash = hashlib.sha256(actor.encode("utf-8")).hexdigest()[:16]
    return f"tenant:{tenant}|actor:{actor_hash}"


def key_hash(scope: str, operation: str, key: str) -> str:
    """Opaque file name for (scope, operation, key)."""
    return hashlib.sha256(f"v1|{scope}|{operation}|{key}".encode("utf-8")).hexdigest()


def backend_idempotency_key(scope: str, operation: str, key: str) -> str:
    """Deterministic Fineract ``Idempotency-Key`` value for one request."""
    digest = hashlib.sha256(f"{scope}\n{operation}\n{key}".encode("utf-8")).hexdigest()
    return f"mcp-{digest[:48]}"


def backend_header_name() -> str:
    return os.getenv(BACKEND_HEADER_ENV, DEFAULT_BACKEND_HEADER).strip() or DEFAULT_BACKEND_HEADER


# ── ledger record ────────────────────────────────────────────────────


def _now() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%S.%fZ")


def _parse_time(value: str) -> datetime:
    return datetime.strptime(value, "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)


@dataclass
class LedgerRecord:
    """One idempotency key's durable state."""

    key_hash: str
    operation: str
    scope: str
    fingerprint: str
    state: str
    attempt: int
    created_at: str
    updated_at: str
    correlation_id: str
    attempt_started_at: str = ""
    attempt_ended_at: str = ""
    owner_pid: int = 0
    owner_host: str = ""
    request: Dict[str, Any] = field(default_factory=dict)
    result: Optional[Dict[str, Any]] = None
    backend_ref: Optional[Dict[str, Any]] = None
    reconcile: Dict[str, Any] = field(default_factory=dict)
    error: Optional[Dict[str, Any]] = None
    version: int = LEDGER_VERSION

    def to_dict(self) -> Dict[str, Any]:
        return {
            "version": self.version,
            "key_hash": self.key_hash,
            "operation": self.operation,
            "scope": self.scope,
            "fingerprint": self.fingerprint,
            "state": self.state,
            "attempt": self.attempt,
            "created_at": self.created_at,
            "updated_at": self.updated_at,
            "correlation_id": self.correlation_id,
            "attempt_started_at": self.attempt_started_at,
            "attempt_ended_at": self.attempt_ended_at,
            "owner_pid": self.owner_pid,
            "owner_host": self.owner_host,
            "request": self.request,
            "result": self.result,
            "backend_ref": self.backend_ref,
            "reconcile": self.reconcile,
            "error": self.error,
        }

    @classmethod
    def from_dict(cls, data: Mapping[str, Any]) -> "LedgerRecord":
        if data.get("version") != LEDGER_VERSION:
            raise ValueError(f"unsupported idempotency ledger version: {data.get('version')!r}")
        required = ("key_hash", "operation", "scope", "fingerprint", "state", "created_at", "updated_at")
        missing = [name for name in required if not isinstance(data.get(name), str) or not data.get(name)]
        if missing:
            raise ValueError(f"idempotency record missing fields: {', '.join(missing)}")
        if data["state"] not in {state.value for state in State}:
            raise ValueError(f"unknown idempotency state: {data['state']!r}")
        return cls(
            key_hash=data["key_hash"],
            operation=data["operation"],
            scope=data["scope"],
            fingerprint=data["fingerprint"],
            state=data["state"],
            attempt=int(data.get("attempt") or 1),
            created_at=data["created_at"],
            updated_at=data["updated_at"],
            correlation_id=str(data.get("correlation_id") or ""),
            attempt_started_at=str(data.get("attempt_started_at") or ""),
            attempt_ended_at=str(data.get("attempt_ended_at") or ""),
            owner_pid=int(data.get("owner_pid") or 0),
            owner_host=str(data.get("owner_host") or ""),
            request=dict(data.get("request") or {}),
            result=data.get("result"),
            backend_ref=data.get("backend_ref"),
            reconcile=dict(data.get("reconcile") or {}),
            error=data.get("error"),
        )


# ── durable ledger ───────────────────────────────────────────────────


def _try_lock(fd: int) -> None:
    """Take a non-blocking exclusive lock on byte 0 of ``fd``."""
    if os.name == "nt":
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_NBLCK, 1)
    else:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)


def _unlock(fd: int) -> None:
    if os.name == "nt":
        os.lseek(fd, 0, os.SEEK_SET)
        msvcrt.locking(fd, msvcrt.LK_UNLCK, 1)
    else:
        fcntl.flock(fd, fcntl.LOCK_UN)


def _is_lock_contention(error: OSError) -> bool:
    return error.errno in {errno.EACCES, errno.EAGAIN, errno.EDEADLK, errno.EWOULDBLOCK}


def _close_quietly(fd: int) -> None:
    try:
        os.close(fd)
    except OSError:
        pass


class ClaimHandle:
    """Ownership token and heartbeat for one held key lock.

    The token is crypto-random per acquisition and is written to the claim
    body while the OS lock is held. Release never unlinks the lock file: it
    verifies the token (best effort, while still holding the lock) and then
    unlocks/closes its own handle. Therefore a former owner can never delete a
    successor's claim, and a successor can only exist after the former owner's
    OS lock is gone.

    External deletion or replacement of the lock *path* by another program is
    explicitly unsupported and outside the threat model. This implementation
    never deletes or renames lock files; ownership is proven by the open
    descriptor and the token, not by the path's current contents.
    """

    def __init__(
        self,
        ledger: "IdempotencyLedger",
        key_hash_value: str,
        path: Path,
        fd: int,
        correlation_id: str,
    ) -> None:
        self.ledger = ledger
        self.key_hash = key_hash_value
        self.path = path
        self.fd = fd
        self.correlation_id = correlation_id
        self.token = uuid.uuid4().hex
        self.acquired_at = _now()
        # Guards the descriptor and the released flag. Every read, write, unlock
        # and close takes it, so a heartbeat write can never overlap a close (or
        # a close that lets the descriptor number be reused). It is re-entrant
        # because release reads the claim while holding it.
        self._io_lock = threading.RLock()
        self._released = False

    def write_claim(self) -> None:
        """Rewrite the claim metadata under the held lock.

        Serialised against :meth:`release` by ``_io_lock``: a release can never
        close the descriptor while a heartbeat write is in flight, and a
        heartbeat that wakes after release sees ``_released`` and does nothing.
        """
        with self._io_lock:
            if self._released:
                raise LedgerUnavailable("The idempotency claim has already been released.")
            now = datetime.now(timezone.utc)
            claim = {
                "claim_token": self.token,
                "owner_pid": os.getpid(),
                "owner_host": socket.gethostname(),
                "acquired_at": self.acquired_at,
                "heartbeat_at": now.strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
                "lease_expires_at": (
                    now + timedelta(seconds=self.ledger.lock_ttl_seconds)
                ).strftime("%Y-%m-%dT%H:%M:%S.%fZ"),
                "correlation_id": self.correlation_id,
            }
            data = json.dumps(claim, sort_keys=True, separators=(",", ":")).encode("utf-8")
            try:
                # Never truncate to zero: a waiter could observe an empty file
                # between truncate and write and try to initialise the byte
                # range itself. Pad with spaces instead.
                previous = os.fstat(self.fd).st_size
                os.lseek(self.fd, 0, os.SEEK_SET)
                os.write(self.fd, data)
                if previous > len(data):
                    os.write(self.fd, b" " * (previous - len(data)))
                os.fsync(self.fd)
            except OSError as e:
                raise LedgerUnavailable(f"Idempotency claim cannot be recorded: {e}") from None

    def _read_claim(self) -> Optional[Dict[str, Any]]:
        with self._io_lock:
            try:
                os.lseek(self.fd, 0, os.SEEK_SET)
                raw = os.read(self.fd, 65536)
                value = json.loads(raw.decode("utf-8") or "{}")
            except (OSError, ValueError, UnicodeDecodeError, json.JSONDecodeError):
                return None
        return value if isinstance(value, dict) else None

    def ensure_held(self) -> None:
        """Fail closed if ownership or the lease can no longer be proven.

        Ownership cannot be transferred while this process holds the OS lock,
        so this is a defence-in-depth check against a heartbeat that stopped
        renewing the lease and against a claim body that no longer carries this
        handle's token.

        External deletion or replacement of the lock *path* is explicitly
        unsupported and outside the threat model: ownership is tracked through
        this handle's open descriptor, not by re-opening the path, so a
        process that unlinks or replaces the file behind this process's back
        cannot be detected reliably on every supported platform. Lock files are
        never deleted or renamed by this implementation.
        """
        with self._io_lock:
            if self._released:
                raise LedgerUnavailable("The idempotency claim has already been released.")
            claim = self._read_claim()
            if claim is None:
                raise LedgerUnavailable("The idempotency claim metadata is unreadable.")
            if (
                claim.get("claim_token") != self.token
                or int(claim.get("owner_pid") or 0) != os.getpid()
            ):
                raise OperationInProgress(
                    "The idempotency claim is no longer owned by this process; it will not execute.",
                    correlation_id=self.correlation_id,
                    idempotency_state=State.IN_PROGRESS.value,
                )
            expires = claim.get("lease_expires_at")
            if isinstance(expires, str):
                try:
                    if datetime.now(timezone.utc) > _parse_time(expires):
                        raise LedgerUnavailable(
                            "The idempotency lease expired before the operation completed; "
                            "refusing to execute."
                        )
                except ValueError:
                    raise LedgerUnavailable("The idempotency lease timestamp is invalid.") from None

    def renew(self) -> None:
        self.write_claim()

    def _heartbeat_loop(self, stop: threading.Event) -> None:
        interval = max(min(self.ledger.lock_ttl_seconds / 3.0, 60.0), 0.01)
        while not stop.wait(interval):
            try:
                self.write_claim()
            except LedgerUnavailable:
                logger.warning("Could not renew idempotency claim %s", self.path.name)
                return

    def _close_unlocked(self) -> None:
        with self._io_lock:
            if self._released:
                return
            self._released = True
            _close_quietly(self.fd)

    def release(self, timeout: float = 5.0) -> None:
        """Give up ownership without deleting or rewriting the claim file.

        The descriptor is unlocked and closed only while ``_io_lock`` is held,
        so a heartbeat write that is still in flight always finishes first. If
        that lock cannot be acquired within ``timeout`` the descriptor is
        deliberately left open: closing it under a concurrent write could let
        the operating system reuse the descriptor number, and the heartbeat
        would then write into an unrelated file. Leaving the OS lock held fails
        closed (later claims for the key report ``OperationInProgress``) instead
        of risking that, and the lock is still released when the process exits.

        External deletion or replacement of the lock path is not detected; see
        :meth:`ensure_held`.
        """
        if not self._io_lock.acquire(timeout=timeout):
            logger.error(
                "Idempotency claim %s is still being written; its descriptor is left open",
                self.path.name,
            )
            return
        try:
            if self._released:
                return
            self._released = True
            # Verify ownership only to report it; release still never deletes or
            # rewrites the claim body, so a successor's claim cannot be damaged.
            current = self._read_claim()
            if current is not None and current.get("claim_token") != self.token:
                logger.warning(
                    "Idempotency claim %s changed owner before release; leaving it untouched",
                    self.path.name,
                )
            try:
                _unlock(self.fd)
            except OSError:
                logger.warning("Could not unlock idempotency claim %s", self.path.name)
            _close_quietly(self.fd)
        finally:
            self._io_lock.release()


class IdempotencyLedger:
    """Atomic, crash-safe, single-host ledger shared with the Go server.

    Each key is guarded by an OS advisory lock on a stable ``<key_hash>.lock``
    file. The lock file is never deleted: the operating system releases the
    lock when the owning process exits or its handle closes, so an abandoned
    claim cannot strand the key and a former owner can never delete a
    successor's claim by path.
    """

    def __init__(
        self,
        directory: Optional[os.PathLike | str] = None,
        *,
        lock_ttl_seconds: Optional[float] = None,
        lock_wait_seconds: Optional[float] = None,
    ) -> None:
        configured = directory or os.getenv(KEY_ENV) or (Path.home() / ".mcp-mifosx" / "idempotency")
        self.directory = Path(configured)
        self.keys_dir = self.directory / "keys"
        self._config_error: Optional[str] = None
        try:
            self.lock_ttl_seconds = _strict_positive_float(
                LOCK_TTL_ENV, lock_ttl_seconds, DEFAULT_LOCK_TTL_SECONDS
            )
        except ValueError as error:
            # Do not silently fall back: a zero or negative lease would let a
            # second process preempt a running owner.
            self.lock_ttl_seconds = DEFAULT_LOCK_TTL_SECONDS
            self._config_error = str(error)
        self.lock_wait_seconds = _env_float(LOCK_WAIT_ENV, lock_wait_seconds, DEFAULT_LOCK_WAIT_SECONDS)
        self._poll_seconds = 0.05

    def ensure_ready(self) -> None:
        """Create the ledger directory or raise ``LedgerUnavailable``."""
        if self._config_error is not None:
            raise LedgerUnavailable(
                f"Invalid idempotency lock configuration: {self._config_error}",
                details={"directory": str(self.directory)},
            )
        try:
            self.keys_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        except OSError as e:
            raise LedgerUnavailable(
                f"Idempotency ledger is unavailable: {e}",
                details={"directory": str(self.directory)},
            ) from None

    def record_path(self, key_hash_value: str) -> Path:
        return self.keys_dir / f"{key_hash_value}.json"

    def lock_path(self, key_hash_value: str) -> Path:
        return self.keys_dir / f"{key_hash_value}.lock"

    def read(self, key_hash_value: str) -> Optional[LedgerRecord]:
        path = self.record_path(key_hash_value)
        try:
            raw = path.read_text(encoding="utf-8")
        except FileNotFoundError:
            return None
        except OSError as e:
            raise LedgerUnavailable(f"Idempotency ledger record cannot be read: {e}") from None
        try:
            return LedgerRecord.from_dict(json.loads(raw))
        except (ValueError, json.JSONDecodeError) as e:
            # A corrupt record is an unknown outcome: refuse rather than guess.
            raise LedgerUnavailable(f"Idempotency ledger record is corrupt: {e}") from None

    def write(self, record: LedgerRecord) -> None:
        record.updated_at = _now()
        target = self.record_path(record.key_hash)
        tmp = self.keys_dir / f".{record.key_hash}.{os.getpid()}.{uuid.uuid4().hex}.tmp"
        try:
            with open(tmp, "w", encoding="utf-8", newline="\n") as handle:
                json.dump(record.to_dict(), handle, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp, target)
        except OSError as e:
            try:
                tmp.unlink()
            except OSError:
                pass
            raise LedgerUnavailable(f"Idempotency ledger record cannot be written: {e}") from None

    @contextmanager
    def claim(self, key_hash_value: str, correlation_id: str) -> Iterator["ClaimHandle"]:
        """Hold the exclusive OS lock for one key and yield its claim handle.

        A second caller waits up to ``lock_wait_seconds`` for the first to
        finish; if the lock is still held it gets ``OperationInProgress`` and
        never executes the financial call. Ownership is enforced by the
        operating system, not by file existence or a timestamp: a crashed or
        terminated owner's lock is released automatically, and a live owner can
        never be preempted by a stale-lease check.
        """
        self.ensure_ready()
        path = self.lock_path(key_hash_value)
        fd = self._acquire_lock(path, correlation_id)
        handle = ClaimHandle(self, key_hash_value, path, fd, correlation_id)
        stop = threading.Event()
        heartbeat = threading.Thread(
            target=handle._heartbeat_loop,
            args=(stop,),
            daemon=True,
            name=f"idempotency-heartbeat-{key_hash_value[:8]}",
        )
        try:
            handle.write_claim()
            heartbeat.start()
        except BaseException:
            handle._close_unlocked()
            raise
        try:
            yield handle
        finally:
            stop.set()
            # The heartbeat only waits on ``stop`` or holds ``_io_lock`` for the
            # duration of one bounded write, so joining here cannot deadlock.
            heartbeat.join(timeout=max(1.0, min(self.lock_ttl_seconds, 5.0)))
            if heartbeat.is_alive():
                logger.warning(
                    "Idempotency heartbeat for %s did not stop before release", path.name
                )
            handle.release()

    def _acquire_lock(self, path: Path, correlation_id: str) -> int:
        deadline = time.monotonic() + self.lock_wait_seconds
        while True:
            try:
                fd = os.open(str(path), os.O_CREAT | os.O_RDWR, 0o600)
            except OSError as e:
                raise LedgerUnavailable(f"Idempotency claim cannot be created: {e}") from None
            try:
                if os.name == "nt" and os.fstat(fd).st_size == 0:
                    # LockFileEx may lock beyond EOF, but msvcrt.locking needs
                    # an existing byte range on some Windows builds.
                    os.write(fd, b"\n")
                    os.fsync(fd)
                _try_lock(fd)
                return fd
            except OSError as e:
                _close_quietly(fd)
                if not _is_lock_contention(e):
                    raise LedgerUnavailable(f"Idempotency claim cannot be locked: {e}") from None
                if time.monotonic() >= deadline:
                    raise OperationInProgress(
                        "Another request with the same idempotency key is still in progress.",
                        correlation_id=correlation_id,
                        idempotency_state=State.IN_PROGRESS.value,
                    ) from None
                time.sleep(self._poll_seconds)


_default_ledger: Optional[IdempotencyLedger] = None
_default_ledger_lock = threading.Lock()


def default_ledger() -> IdempotencyLedger:
    global _default_ledger
    with _default_ledger_lock:
        if _default_ledger is None:
            _default_ledger = IdempotencyLedger()
        return _default_ledger


def reset_default_ledger() -> None:
    """Test hook: re-read the configured ledger directory."""
    global _default_ledger
    with _default_ledger_lock:
        _default_ledger = None


def ledger_available() -> bool:
    """Health probe: the ledger directory can be created and written."""
    probe = default_ledger()
    try:
        probe.ensure_ready()
        path = probe.keys_dir / f".probe-{os.getpid()}.tmp"
        path.write_text("ok", encoding="utf-8")
        path.unlink()
        return True
    except (OSError, LedgerUnavailable):
        return False


# ── execution contract ───────────────────────────────────────────────


@dataclass(frozen=True)
class OperationRequest:
    """What is being executed, and what is needed to reconcile it later."""

    operation: str
    resource: str
    payload: Mapping[str, Any]
    reconciliation: Mapping[str, Any] = field(default_factory=dict)


@dataclass
class ExecutionResult:
    """Classified outcome of one backend attempt.

    ``raw`` keeps the body the domain layer already returned so an
    unprotected call that fails conclusively keeps its historical response
    shape (for example ``{"error": ..., "status_code": 400}``).
    """

    outcome: Outcome
    result: Optional[Dict[str, Any]] = None
    error: Optional[str] = None
    backend_ref: Optional[Dict[str, Any]] = None
    detail: Optional[str] = None
    raw: Optional[Dict[str, Any]] = None


@dataclass
class ReconcileResult:
    """What a backend read proved about an earlier attempt."""

    status: ReconcileStatus
    result: Optional[Dict[str, Any]] = None
    backend_ref: Optional[Dict[str, Any]] = None
    detail: str = ""


class Reconciler(Protocol):
    """Operation-specific read model used after an ambiguous attempt."""

    def snapshot(self, request: OperationRequest) -> Any:
        """Capture the pre-execution state needed to recognise the new result."""

    def reconcile(self, record: LedgerRecord) -> ReconcileResult:
        """Look for the attempt's effect; never mutates the backend."""


def new_correlation_id() -> str:
    return uuid.uuid4().hex


def _env_float(name: str, explicit: Optional[float], default: float) -> float:
    if explicit is not None:
        return float(explicit)
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw)
    except ValueError:
        return default
    return value if math.isfinite(value) and value >= 0 else default


def _strict_positive_float(name: str, explicit: Optional[float], default: float) -> float:
    """Parse a lease duration, rejecting zero, negative and non-finite values.

    A zero or negative lock TTL used to be accepted and made every claim look
    instantly stale, which let a second process preempt a live owner. Such a
    configuration is a deployment error, not a safe default to fall back to.
    """
    raw: Optional[str]
    if explicit is not None:
        value = float(explicit)
        source = "the configured value"
    else:
        raw = os.getenv(name)
        if raw is None or raw.strip() == "":
            return default
        try:
            value = float(raw)
        except ValueError:
            raise ValueError(f"{name} must be a number greater than zero") from None
        source = name
    if not math.isfinite(value) or value <= 0:
        raise ValueError(f"{source} must be greater than zero (got {value!r})")
    return value


def _env_bool(name: str, default: bool) -> bool:
    raw = os.getenv(name)
    if raw is None or raw.strip() == "":
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


# Backend fields a reconciler may inspect for a unique per-request marker.
#
# This is a defensive allow-list, not a claim of support: current Fineract
# money-operation request bodies (loan repayment/disbursal, savings
# deposit/withdrawal, loan/savings charges) accept no field that is proven to be
# persisted and returned on a later transaction read. No marker is therefore
# injected into a request body, and marker-based confirmation cannot succeed on
# a stock Fineract deployment. An ambiguous outcome stays UNKNOWN unless a
# deployment (or future Fineract version) genuinely persists one of these
# fields; see python/README.md.
DEFAULT_MARKER_FIELDS = (
    "externalId",
    "idempotencyKey",
    "idempotency_key",
    "transactionReference",
    "note",
    "comments",
)


def backend_dedup_verified() -> bool:
    """Whether the deployment has independently proven backend deduplication.

    Defaults to false. Without proof, even a fully confirmed absence must not
    authorise an automatic retry: it stays UNKNOWN for manual reconciliation.
    """
    return _env_bool(BACKEND_DEDUP_ENV, False)


def absence_safety_window_seconds() -> float:
    return _env_float(ABSENCE_WINDOW_ENV, None, DEFAULT_ABSENCE_WINDOW_SECONDS)


def _reset_absence_evidence(record: LedgerRecord) -> None:
    for name in (
        "absence_reads",
        "absence_last_read_at",
        "absence_attempt",
        "absence_confirmed_at",
    ):
        record.reconcile.pop(name, None)


def _mark_attempt_started(record: LedgerRecord) -> None:
    record.attempt_started_at = _now()
    record.attempt_ended_at = ""
    _reset_absence_evidence(record)


def _mark_attempt_ended(record: LedgerRecord) -> None:
    record.attempt_ended_at = _now()


def _apply_marker(record: LedgerRecord, scope: str, operation: str, key: str) -> None:
    """Record the per-key marker a reconciler may look for in backend records.

    The marker is stored only in the local ledger. It is deliberately never
    sent to Fineract: no supported money-operation request field is proven to
    persist it, and inventing one would write unverified data to the backend.
    """
    record.reconcile.setdefault("marker", backend_idempotency_key(scope, operation, key))
    record.reconcile.setdefault("marker_fields", list(DEFAULT_MARKER_FIELDS))


def validate_key(key: str, *, correlation_id: Optional[str] = None) -> None:
    if not KEY_PATTERN.fullmatch(key):
        raise InvalidIdempotencyKey(
            "Idempotency key must be 8-128 characters from [A-Za-z0-9._:-] and start with an alphanumeric.",
            correlation_id=correlation_id,
        )


def _snapshot_for(reconciler: Optional[Reconciler], request: OperationRequest, correlation_id: str) -> Any:
    if reconciler is None:
        return None
    try:
        return reconciler.snapshot(request)
    except Exception:
        logger.exception("Could not capture reconciliation snapshot (correlation_id=%s)", correlation_id)
        return None


def _reconcile(
    reconciler: Optional[Reconciler], record: LedgerRecord, correlation_id: str
) -> ReconcileResult:
    if reconciler is None:
        return ReconcileResult(
            ReconcileStatus.UNKNOWN,
            detail="No reconciler is configured for this operation; the outcome must be checked in Fineract.",
        )
    try:
        result = reconciler.reconcile(record)
    except Exception as e:
        logger.exception("Reconciliation read failed (correlation_id=%s)", correlation_id)
        return ReconcileResult(ReconcileStatus.UNKNOWN, detail=f"Reconciliation read failed: {e}")
    if not isinstance(result, ReconcileResult) or not isinstance(result.status, ReconcileStatus):
        # A reconciler is an internal extension point, not a trusted oracle.
        # Anything it returns outside the three known verdicts is not evidence
        # and must never fall through to the absence/retry path.
        logger.error(
            "Reconciler returned an unrecognised result (correlation_id=%s)", correlation_id
        )
        return ReconcileResult(
            ReconcileStatus.UNKNOWN,
            detail="The reconciler returned an unrecognised status, so the outcome cannot be determined.",
        )
    return result


def _failure_result(message: str, correlation_id: str, state: str, *, safe_to_retry: bool) -> Dict[str, Any]:
    return {
        "error": message,
        "error_code": "safe_retry_permitted" if safe_to_retry else "operation_failed",
        "correlation_id": correlation_id,
        "idempotency_state": state,
        "safe_to_retry": safe_to_retry,
    }


# The only FAILED classifications this implementation writes. Anything else in
# a persisted record is treated as untrustworthy, not as a conclusive refusal.
_KNOWN_FAILED_CODES = frozenset({"safe_retry_permitted", "operation_failed"})


def _classify_failed_record(record: LedgerRecord, correlation_id: str) -> bool:
    """Whether a persisted FAILED record is absence-derived.

    Returns ``True`` for a legacy absence-derived failure, which must be
    reconciled before any retry. A record whose persisted error field has an
    unexpected type is a corrupt ledger record, so this raises a typed
    fail-closed error instead of letting the state machine start another
    financial attempt.
    """
    if record.state != State.FAILED.value:
        return False
    error = record.error
    if error is None:
        raise LedgerUnavailable(
            "The idempotency record is malformed: a failed operation has no error classification.",
            correlation_id=correlation_id,
            idempotency_state=record.state,
        )
    if not isinstance(error, Mapping):
        raise LedgerUnavailable(
            "The idempotency record is malformed: its error field is not an object.",
            correlation_id=correlation_id,
            idempotency_state=record.state,
        )
    code = error.get("code")
    if not isinstance(code, str) or code not in _KNOWN_FAILED_CODES:
        raise LedgerUnavailable(
            "The idempotency record is malformed: its error classification is not understood.",
            correlation_id=correlation_id,
            idempotency_state=record.state,
        )
    return code == "safe_retry_permitted"


def _execute_unprotected(
    request: OperationRequest,
    executor: Callable[[Optional[str]], ExecutionResult],
    correlation_id: str,
) -> Dict[str, Any]:
    """Backward-compatible path: no key, so no durable retry protection.

    The operation still refuses to pretend an ambiguous attempt failed: the
    caller is told the outcome is unknown and that a retry needs a key.
    """
    logger.warning(
        "Money operation %s executed without an idempotency key (correlation_id=%s)",
        request.operation,
        correlation_id,
    )
    outcome = executor(None)
    if outcome.outcome is Outcome.SUCCESS:
        return outcome.result or {}
    if outcome.outcome is Outcome.FAILURE:
        if outcome.raw is not None:
            # Keep the pre-idempotency response shape when the backend
            # conclusively rejected the request and no key was supplied.
            return outcome.raw
        return _failure_result(
            outcome.error or "The operation failed.", correlation_id, "UNPROTECTED", safe_to_retry=True
        )
    logger.error(
        "Ambiguous outcome without an idempotency key for %s (correlation_id=%s)",
        request.operation,
        correlation_id,
    )
    return {
        "error": "The outcome of this operation is unknown. It may have been processed. "
        "Check Fineract before retrying, and pass an idempotency_key to make retries safe.",
        "error_code": "outcome_unknown",
        "correlation_id": correlation_id,
        "idempotency_state": State.UNKNOWN.value,
        "safe_to_retry": False,
    }


def execute_money_operation(
    request: OperationRequest,
    idempotency_key: Optional[str],
    executor: Callable[[Optional[str]], ExecutionResult],
    reconciler: Optional[Reconciler] = None,
    *,
    scope: Optional[str] = None,
    correlation_id: Optional[str] = None,
    ledger: Optional[IdempotencyLedger] = None,
    required: Optional[bool] = None,
) -> Dict[str, Any]:
    """Execute a money-moving operation at most once per key.

    Parameters
    ----------
    request : OperationRequest
        Operation name, resource id, canonical request payload and the
        reconciliation parameters needed by ``reconciler``.
    idempotency_key : str or None
        Client-supplied key. A missing key is allowed unless
        ``MCP_IDEMPOTENCY_REQUIRED`` is on; the operation then has no durable
        retry protection.
    executor : Callable
        Performs exactly one backend attempt. It receives the deterministic
        Fineract idempotency key (or None) and must classify the outcome.
    reconciler : Reconciler, optional
        Read-only check used when a previous attempt may have succeeded.
    """
    correlation_id = correlation_id or new_correlation_id()
    scope = scope or default_scope()
    key = idempotency_key.strip() if isinstance(idempotency_key, str) else None
    if key == "":
        key = None

    if key is None:
        if required is None:
            required = _env_bool(REQUIRED_ENV, False)
        if required:
            raise MissingIdempotencyKey(
                "An idempotency_key is required for money-moving operations.",
                correlation_id=correlation_id,
            )
        return _execute_unprotected(request, executor, correlation_id)

    validate_key(key, correlation_id=correlation_id)
    fingerprint = request_fingerprint(request.operation, request.payload)
    digest = key_hash(scope, request.operation, key)
    ledger = ledger or default_ledger()

    with ledger.claim(digest, correlation_id) as claim_handle:
        record = ledger.read(digest)
        if record is None:
            record = LedgerRecord(
                key_hash=digest,
                operation=request.operation,
                scope=scope,
                fingerprint=fingerprint,
                state=State.IN_PROGRESS.value,
                attempt=1,
                created_at=_now(),
                updated_at=_now(),
                correlation_id=correlation_id,
                attempt_started_at=_now(),
                attempt_ended_at="",
                owner_pid=os.getpid(),
                owner_host=socket.gethostname(),
                request={"resource": request.resource},
                reconcile=dict(request.reconciliation),
            )
            _apply_marker(record, scope, request.operation, key)
            snapshot = _snapshot_for(reconciler, request, correlation_id)
            if snapshot is not None:
                record.reconcile["snapshot"] = snapshot
            ledger.write(record)
            logger.info(
                "Idempotency record created operation=%s state=%s correlation_id=%s",
                request.operation,
                record.state,
                correlation_id,
            )
        else:
            if (
                record.operation != request.operation
                or record.scope != scope
                or record.fingerprint != fingerprint
            ):
                raise IdempotencyKeyReused(
                    "This idempotency key was already used for a different operation or payload.",
                    correlation_id=correlation_id,
                    idempotency_state=record.state,
                ) from None

            if record.state == State.SUCCEEDED.value:
                logger.info(
                    "Idempotent replay operation=%s correlation_id=%s",
                    request.operation,
                    correlation_id,
                )
                return record.result or {}

            # An absence-derived FAILED from an older ledger version must be
            # reconciled, never treated as a conclusive failure to retry. A
            # FAILED record with an unusable or unrecognised classification
            # fails closed instead of being retried.
            legacy_absence_failure = _classify_failed_record(record, correlation_id)
            if record.state in (
                State.IN_PROGRESS.value,
                State.PENDING.value,
                State.UNKNOWN.value,
                State.RETRYABLE_ABSENT.value,
            ) or legacy_absence_failure:
                reconciled = _reconcile(reconciler, record, correlation_id)
                if reconciled.status is ReconcileStatus.CONFIRMED:
                    record.state = State.SUCCEEDED.value
                    record.result = reconciled.result or {}
                    record.backend_ref = reconciled.backend_ref
                    record.error = None
                    ledger.write(record)
                    logger.info(
                        "Ambiguous outcome reconciled as SUCCEEDED operation=%s correlation_id=%s",
                        request.operation,
                        correlation_id,
                    )
                    return record.result
                if reconciled.status is ReconcileStatus.UNKNOWN:
                    record.state = State.UNKNOWN.value
                    record.error = {
                        "code": ReconciliationRequired.code,
                        "message": reconciled.detail or "The operation's outcome cannot be determined.",
                        "retryable": False,
                    }
                    ledger.write(record)
                    raise ReconciliationRequired(
                        "The previous attempt's outcome cannot be determined, so it was not repeated. "
                        "Verify the operation in Fineract before retrying with a new idempotency key.",
                        correlation_id=correlation_id,
                        idempotency_state=State.UNKNOWN.value,
                        details={"reason": record.error["message"]},
                    ) from None

                # ABSENT evidence is only attempt-scoped and time-windowed; it
                # is never sufficient by itself to execute a financial retry.
                if not backend_dedup_verified():
                    record.state = State.UNKNOWN.value
                    record.error = {
                        "code": ReconciliationRequired.code,
                        "message": (
                            "No record of this operation is visible, but backend deduplication "
                            "has not been verified, so an automatic retry is not permitted."
                        ),
                        "retryable": False,
                    }
                    ledger.write(record)
                    raise ReconciliationRequired(
                        "The attempt appears absent, but without proven backend deduplication "
                        "the outcome cannot be treated as safe to repeat. Verify it in Fineract "
                        "before retrying with a new idempotency key.",
                        correlation_id=correlation_id,
                        idempotency_state=State.UNKNOWN.value,
                        details={"reason": record.error["message"]},
                    ) from None

                if record.state == State.RETRYABLE_ABSENT.value:
                    # Re-verified absence on a later request: the controlled
                    # retry may now begin. It still reconciled first.
                    logger.info(
                        "Absence re-verified; controlled retry operation=%s correlation_id=%s",
                        request.operation,
                        correlation_id,
                    )
                else:
                    record.state = State.RETRYABLE_ABSENT.value
                    record.reconcile["absence_confirmed_at"] = _now()
                    record.error = {
                        "code": "safe_retry_permitted",
                        "message": (
                            "The backend has no record of this operation and deduplication is "
                            "verified; retry with the same idempotency key to complete it."
                        ),
                        "retryable": True,
                    }
                    ledger.write(record)
                    return _failure_result(
                        "The operation was not found in Fineract; it is safe to retry with the same "
                        "idempotency key.",
                        correlation_id,
                        State.RETRYABLE_ABSENT.value,
                        safe_to_retry=True,
                    )

            record.attempt += 1
            record.state = State.IN_PROGRESS.value
            _mark_attempt_started(record)
            record.correlation_id = correlation_id
            record.owner_pid = os.getpid()
            record.owner_host = socket.gethostname()
            record.error = None
            _apply_marker(record, scope, request.operation, key)
            if reconciler is not None and record.reconcile.get("snapshot") is None:
                snapshot = _snapshot_for(reconciler, request, correlation_id)
                if snapshot is not None:
                    record.reconcile["snapshot"] = snapshot
            ledger.write(record)

        claim_handle.ensure_held()
        return _run_attempt(record, request, executor, reconciler, ledger, scope, key, correlation_id)


def _run_attempt(
    record: LedgerRecord,
    request: OperationRequest,
    executor: Callable[[Optional[str]], ExecutionResult],
    reconciler: Optional[Reconciler],
    ledger: IdempotencyLedger,
    scope: str,
    key: str,
    correlation_id: str,
) -> Dict[str, Any]:
    backend_key = backend_idempotency_key(scope, request.operation, key)

    try:
        outcome = executor(backend_key)
    except Exception:
        # The executor is expected to classify network failures. Anything that
        # escapes is unexpected, so the only safe state is UNKNOWN: a future
        # attempt reconciles first and never blindly repeats a money move.
        _mark_attempt_ended(record)
        record.state = State.UNKNOWN.value
        record.error = {
            "code": OutcomeUnknown.code,
            "message": "The operation raised an unexpected error; its outcome is unknown.",
            "retryable": False,
        }
        ledger.write(record)
        logger.exception(
            "Unexpected money-operation error operation=%s correlation_id=%s",
            request.operation,
            correlation_id,
        )
        raise

    _mark_attempt_ended(record)

    if outcome.outcome is Outcome.SUCCESS:
        record.state = State.SUCCEEDED.value
        record.result = outcome.result or {}
        record.backend_ref = outcome.backend_ref
        record.error = None
        ledger.write(record)
        logger.info(
            "Operation succeeded operation=%s attempt=%d correlation_id=%s",
            request.operation,
            record.attempt,
            correlation_id,
        )
        return record.result

    if outcome.outcome is Outcome.FAILURE:
        record.state = State.FAILED.value
        record.error = {
            "code": "operation_failed",
            "message": outcome.error or "The operation failed.",
            "retryable": True,
        }
        ledger.write(record)
        logger.info(
            "Operation failed conclusively operation=%s correlation_id=%s",
            request.operation,
            correlation_id,
        )
        return {
            "error": record.error["message"],
            "error_code": "operation_failed",
            "correlation_id": correlation_id,
            "idempotency_state": State.FAILED.value,
            "safe_to_retry": True,
        }

    record.state = State.UNKNOWN.value
    record.error = {
        "code": OutcomeUnknown.code,
        "message": outcome.error or outcome.detail or "The backend did not confirm the operation.",
        "retryable": False,
    }
    record.reconcile["ambiguous_detail"] = outcome.detail or outcome.error
    ledger.write(record)
    logger.warning(
        "Ambiguous operation outcome operation=%s correlation_id=%s",
        request.operation,
        correlation_id,
    )

    reconciled = _reconcile(reconciler, record, correlation_id)
    if reconciled.status is ReconcileStatus.CONFIRMED:
        record.state = State.SUCCEEDED.value
        record.result = reconciled.result or {}
        record.backend_ref = reconciled.backend_ref
        record.error = None
        ledger.write(record)
        logger.info(
            "Ambiguous outcome reconciled as SUCCEEDED operation=%s correlation_id=%s",
            request.operation,
            correlation_id,
        )
        return record.result

    if reconciled.status is ReconcileStatus.ABSENT:
        if backend_dedup_verified():
            # The absence evidence is attempt-scoped and time-windowed. Even
            # with proven backend dedup it records RETRYABLE_ABSENT instead of
            # executing here, so every retry reconciles once more.
            record.state = State.RETRYABLE_ABSENT.value
            record.reconcile["absence_confirmed_at"] = _now()
            record.error = {
                "code": "safe_retry_permitted",
                "message": (
                    "The backend has no record of this operation and deduplication is verified; "
                    "retry with the same idempotency key to complete it."
                ),
                "retryable": True,
            }
            ledger.write(record)
            logger.info(
                "Absence confirmed with verified backend dedup operation=%s correlation_id=%s",
                request.operation,
                correlation_id,
            )
            return _failure_result(
                "The operation was not found in Fineract; it is safe to retry with the same "
                "idempotency key.",
                correlation_id,
                State.RETRYABLE_ABSENT.value,
                safe_to_retry=True,
            )

        # An absence-derived outcome is NOT a failure. Without independently
        # proven dedup it stays UNKNOWN and never authorises another attempt.
        record.state = State.UNKNOWN.value
        record.error = {
            "code": ReconciliationRequired.code,
            "message": (
                "No record of this operation is visible, but backend deduplication has not "
                "been verified, so an automatic retry is not permitted."
            ),
            "retryable": False,
        }
        ledger.write(record)
        raise ReconciliationRequired(
            "The operation's outcome could not be proven. Verify it in Fineract before retrying "
            "with a new idempotency key.",
            correlation_id=correlation_id,
            idempotency_state=State.UNKNOWN.value,
            details={"reason": record.error["message"]},
        ) from None

    record.state = State.UNKNOWN.value
    record.error = {
        "code": OutcomeUnknown.code,
        "message": reconciled.detail or "The backend did not confirm or rule out the operation.",
        "retryable": False,
    }
    ledger.write(record)
    raise ReconciliationRequired(
        "The operation's outcome is unknown. It may have been processed, so it was not repeated. "
        "Verify it in Fineract before retrying with a new idempotency key.",
        correlation_id=correlation_id,
        idempotency_state=State.UNKNOWN.value,
        details={"reason": record.error["message"]},
    ) from None
