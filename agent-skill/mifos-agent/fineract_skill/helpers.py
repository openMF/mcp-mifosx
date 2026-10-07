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


def safe_result(data: dict | list | None, *, error: str | None = None) -> dict:
    """Wrap a raw Fineract response into a standardised agent-friendly
    envelope.

    Returns ``{"ok": True, "data": ...}`` on success or
    ``{"ok": False, "error": "..."}`` on failure.
    """
    if error:
        return {"ok": False, "error": error}
    return {"ok": True, "data": data}
