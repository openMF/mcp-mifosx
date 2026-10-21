# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Shared helpers used by domain modules.
"""

from __future__ import annotations

from datetime import datetime, timezone

_MONTHS = [
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
]


def fineract_today() -> str:
    """Return today's date in the Fineract-required format ``dd MMMM yyyy``.

    Example: ``"25 September 2026"``
    """
    now = datetime.now(timezone.utc)
    return f"{now.day} {_MONTHS[now.month]} {now.year}"


def fmt_date(raw) -> str | None:
    """Normalise Fineract date representations to ``"2 March 2026"``.

    Handles:
    * ``[2026, 3, 2]`` — Java-style int list
    * ``"2026-03-02"`` — ISO string
    * ``None`` — returns ``None``
    """
    if raw is None:
        return None
    if isinstance(raw, list) and len(raw) >= 3:
        return f"{raw[2]} {_MONTHS[raw[1]]} {raw[0]}"
    if isinstance(raw, str) and "-" in raw:
        parts = raw.split("-")
        if len(parts) == 3:
            try:
                return f"{int(parts[2])} {_MONTHS[int(parts[1])]} {parts[0]}"
            except (ValueError, IndexError):
                pass
    return str(raw)


def invalid_payment_type(payment_type_id: int | None) -> str | None:
    """Message for a payment type id that is not a positive integer, else ``None``.

    Fineract checks ``paymentTypeId`` with ``integerGreaterThanZero()`` on savings
    transactions (``SavingsAccountTransactionDataValidator.validatePaymentTypeDetails``)
    and loan repayments (``LoanTransactionValidatorImpl.validatePaymentDetails``), so
    ``0`` and negative ids are rejected there. Catching it here turns a platform
    validation error into one an agent can act on, and saves the round trip. A payment
    type id of ``0`` cannot exist anyway: Fineract's sequence starts above it.

    Parameters
    ----------
    payment_type_id : int or None
        The id about to be posted, or None when it was not supplied.

    Returns
    -------
    str or None
        The message to fail with, or None when the id may be posted.
    """
    if payment_type_id is not None and payment_type_id <= 0:
        return (
            f"payment_type_id must be greater than zero, and {payment_type_id} was given. "
            "Use the id of one of this tenant's payment types; leave it out to be told the configured ones."
        )
    return None


def safe_result(data: dict | list | None, *, error: str | None = None) -> dict:
    """Wrap a raw Fineract response into a standardised agent-friendly
    envelope.

    Returns ``{"ok": True, "data": ...}`` on success or
    ``{"ok": False, "error": "..."}`` on failure.
    """
    if error:
        return {"ok": False, "error": error}
    return {"ok": True, "data": data}
