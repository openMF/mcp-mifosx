# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Staff & Offices domain — query organizational structure.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import safe_result


def list_staff(
    client: FineractClient,
    office_id: int | None = None,
    status: str = "all",
) -> dict[str, Any]:
    """List staff members, optionally filtered by office and status.

    Parameters
    ----------
    office_id : int, optional
        Filter by office.
    status : str
        ``"all"`` | ``"active"`` | ``"inactive"``.
    """
    try:
        params: dict[str, Any] = {}
        if office_id:
            params["officeId"] = office_id
        if status:
            params["status"] = status
        return safe_result(client.get("staff", params=params))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_staff_details(client: FineractClient, staff_id: int) -> dict[str, Any]:
    """Get details for a staff member.

    Parameters
    ----------
    staff_id : int
        Staff member ID.
    """
    try:
        return safe_result(client.get(f"staff/{staff_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def list_offices(client: FineractClient) -> dict[str, Any]:
    """List all bank offices / branches."""
    try:
        return safe_result(client.get("offices"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_office_details(client: FineractClient, office_id: int) -> dict[str, Any]:
    """Get details for a specific office.

    Parameters
    ----------
    office_id : int
        Office ID.
    """
    try:
        return safe_result(client.get(f"offices/{office_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))
