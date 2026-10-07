# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Groups domain — create, manage, and query lending groups and centers.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import fineract_today, safe_result


def create_group(
    client: FineractClient,
    name: str,
    *,
    office_id: int = 1,
    external_id: str | None = None,
) -> dict[str, Any]:
    """Create a new lending group.

    Parameters
    ----------
    name : str
        Group name.
    office_id : int
        Office / branch ID.
    external_id : str, optional
        External reference identifier.
    """
    today = fineract_today()
    payload: dict[str, Any] = {
        "officeId": office_id,
        "name": name,
        "active": True,
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    if external_id:
        payload["externalId"] = external_id
    try:
        result = client.post("groups", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_group(client: FineractClient, group_id: int) -> dict[str, Any]:
    """Show details and members of a lending group.

    Parameters
    ----------
    group_id : int
        Fineract group ID.
    """
    try:
        return safe_result(client.get(f"groups/{group_id}?associations=clientMembers"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def list_groups(client: FineractClient, office_id: int | None = None) -> dict[str, Any]:
    """List all lending groups, optionally filtered by office.

    Parameters
    ----------
    office_id : int, optional
        Filter by office / branch.
    """
    try:
        endpoint = "groups"
        if office_id:
            endpoint += f"?officeId={office_id}"
        return safe_result(client.get(endpoint))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def activate_group(client: FineractClient, group_id: int) -> dict[str, Any]:
    """Activate a pending group.

    Parameters
    ----------
    group_id : int
        Group to activate.
    """
    try:
        result = client.post(f"groups/{group_id}?command=activate", {
            "activationDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def add_group_member(
    client: FineractClient, group_id: int, client_id: int
) -> dict[str, Any]:
    """Add a client to a lending group.

    Parameters
    ----------
    group_id : int
        Target group.
    client_id : int
        Client to add.
    """
    try:
        result = client.post(f"groups/{group_id}?command=associateClients", {
            "clientMembers": [client_id],
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Centers ────────────────────────────────────────────────────────────

def list_centers(client: FineractClient, office_id: int | None = None) -> dict[str, Any]:
    """List all centers, optionally filtered by office."""
    try:
        endpoint = "centers"
        if office_id:
            endpoint += f"?officeId={office_id}"
        return safe_result(client.get(endpoint))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_center(client: FineractClient, center_id: int) -> dict[str, Any]:
    """Get details for a center."""
    try:
        return safe_result(client.get(f"centers/{center_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_center(
    client: FineractClient,
    name: str,
    office_id: int,
    *,
    external_id: str | None = None,
) -> dict[str, Any]:
    """Create a new center.

    Parameters
    ----------
    name : str
        Center name.
    office_id : int
        Office / branch ID.
    external_id : str, optional
        External reference.
    """
    today = fineract_today()
    payload: dict[str, Any] = {
        "name": name,
        "officeId": office_id,
        "active": True,
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    if external_id:
        payload["externalId"] = external_id
    try:
        result = client.post("centers", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))
