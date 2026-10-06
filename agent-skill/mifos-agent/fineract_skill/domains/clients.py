# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Client domain — search, create, update, and manage banking clients.
"""

from __future__ import annotations

from typing import Any
import urllib.parse

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import fineract_today, fmt_date, safe_result


# ── Search & Read ──────────────────────────────────────────────────────

def search_clients(client: FineractClient, name_query: str) -> dict[str, Any]:
    """Find clients by name.

    Parameters
    ----------
    name_query : str
        Full or partial client name to search for.

    Returns
    -------
    dict
        ``{"ok": True, "data": {"clients": [...]}}``
    """
    try:
        encoded_query = urllib.parse.quote_plus(name_query)
        result = client.get(f"search?query={encoded_query}&resource=clients&exactMatch=false")
        clients_list = result if isinstance(result, list) else result.get("pageItems", [])
        return safe_result({
            "clients": [
                {
                    "clientId": c.get("entityId") or c.get("id"),
                    "displayName": c.get("entityName") or c.get("displayName"),
                    "accountNo": c.get("entityAccountNo") or c.get("accountNo"),
                    "status": (
                        c.get("entityStatus", {}).get("value")
                        if isinstance(c.get("entityStatus"), dict)
                        else c.get("entityStatus")
                    ),
                }
                for c in clients_list
            ]
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_details(client: FineractClient, client_id: int) -> dict[str, Any]:
    """Get detailed information for a specific client.

    Parameters
    ----------
    client_id : int
        The Fineract client ID.

    Returns
    -------
    dict
        Key fields: ``clientId``, ``displayName``, ``status``, ``activationDate``, etc.
    """
    try:
        data = client.get(f"clients/{client_id}")
        return safe_result({
            "clientId": data.get("id"),
            "displayName": data.get("displayName"),
            "firstname": data.get("firstname"),
            "lastname": data.get("lastname"),
            "accountNo": data.get("accountNo"),
            "mobileNo": data.get("mobileNo"),
            "status": data.get("status", {}).get("value"),
            "activationDate": fmt_date(data.get("activationDate")),
            "officeName": data.get("officeName"),
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_accounts(client: FineractClient, client_id: int) -> dict[str, Any]:
    """Get all loan and savings accounts for a client.

    Parameters
    ----------
    client_id : int
        The Fineract client ID.

    Returns
    -------
    dict
        Contains ``loanAccounts`` and ``savingsAccounts`` lists.
    """
    try:
        result = client.get(f"clients/{client_id}/accounts")
        loans = result.get("loanAccounts", [])
        savings = result.get("savingsAccounts", [])
        return safe_result({
            "clientId": client_id,
            "loanAccounts": [
                {
                    "loanId": loan.get("id"),
                    "accountNo": loan.get("accountNo"),
                    "status": loan.get("status", {}).get("value"),
                    "outstandingBalance": loan.get("loanBalance", 0.00),
                }
                for loan in loans
            ],
            "savingsAccounts": [
                {
                    "savingsId": s.get("id"),
                    "accountNo": s.get("accountNo"),
                    "status": s.get("status", {}).get("value"),
                    "balance": s.get("accountBalance", 0.00),
                }
                for s in savings
            ],
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Create, Update, Delete ────────────────────────────────────────────

def create_client(
    client: FineractClient,
    firstname: str,
    lastname: str,
    *,
    mobile_no: str | None = None,
    office_id: int = 1,
    active: bool = True,
) -> dict[str, Any]:
    """Create a new banking client.

    Parameters
    ----------
    firstname, lastname : str
        Client's legal name.
    mobile_no : str, optional
        Phone number.
    office_id : int
        Office / branch ID. Defaults to head office (1).
    active : bool
        If ``True``, the client is activated immediately.

    Returns
    -------
    dict
        Contains the new ``clientId`` on success.
    """
    today = fineract_today()
    payload: dict[str, Any] = {
        "officeId": office_id,
        "firstname": firstname,
        "lastname": lastname,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "legalFormId": 1,
    }
    if active:
        payload["activationDate"] = today
        payload["active"] = True
    else:
        payload["active"] = False
    if mobile_no:
        payload["mobileNo"] = mobile_no

    try:
        result = client.post("clients", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def activate_client(client: FineractClient, client_id: int) -> dict[str, Any]:
    """Activate a pending client profile.

    Parameters
    ----------
    client_id : int
        Client ID to activate.
    """
    try:
        result = client.post(f"clients/{client_id}?command=activate", {
            "activationDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def update_client(
    client: FineractClient,
    client_id: int,
    *,
    firstname: str | None = None,
    lastname: str | None = None,
    mobile_no: str | None = None,
    external_id: str | None = None,
) -> dict[str, Any]:
    """Update an existing client's details.

    Only the provided fields are changed; others are left untouched.

    Parameters
    ----------
    client_id : int
        Client to update.
    firstname, lastname, mobile_no, external_id : str, optional
        Fields to modify.
    """
    payload: dict[str, Any] = {"locale": "en", "dateFormat": "dd MMMM yyyy"}
    if firstname:
        payload["firstname"] = firstname
    if lastname:
        payload["lastname"] = lastname
    if mobile_no:
        payload["mobileNo"] = mobile_no
    if external_id:
        payload["externalId"] = external_id

    if len(payload) <= 2:
        return safe_result(None, error="No fields provided to update.")

    try:
        result = client.put(f"clients/{client_id}", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def close_client(
    client: FineractClient, client_id: int, closure_reason_id: int
) -> dict[str, Any]:
    """Close a client profile.

    Parameters
    ----------
    client_id : int
        Client to close.
    closure_reason_id : int
        Code-table value for the closure reason.
    """
    try:
        result = client.post(f"clients/{client_id}?command=close", {
            "closureDate": fineract_today(),
            "closureReasonId": closure_reason_id,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def delete_client(client: FineractClient, client_id: int) -> dict[str, Any]:
    """Delete a client profile (only pending/closed clients)."""
    try:
        result = client.delete(f"clients/{client_id}")
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Sub-resources ──────────────────────────────────────────────────────

def get_client_identifiers(client: FineractClient, client_id: int) -> dict[str, Any]:
    """List identity documents (passport, SSN, etc.) for a client."""
    try:
        return safe_result(client.get(f"clients/{client_id}/identifiers"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_client_identifier(
    client: FineractClient, client_id: int, document_type_id: int, document_key: str
) -> dict[str, Any]:
    """Add a new identity document to a client's profile."""
    try:
        result = client.post(f"clients/{client_id}/identifiers", {
            "documentTypeId": document_type_id,
            "documentKey": document_key,
            "description": "Added via Agent Skill",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_documents(client: FineractClient, client_id: int) -> dict[str, Any]:
    """List all uploaded files/documents for a client."""
    try:
        return safe_result(client.get(f"clients/{client_id}/documents"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_charges(client: FineractClient, client_id: int) -> dict[str, Any]:
    """List client-level fees and penalties."""
    try:
        return safe_result(client.get(f"clients/{client_id}/charges"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def apply_client_charge(
    client: FineractClient, client_id: int, charge_id: int, amount: float
) -> dict[str, Any]:
    """Apply a one-time charge/fee to a client profile."""
    try:
        result = client.post(f"clients/{client_id}/charges", {
            "chargeId": charge_id,
            "amount": amount,
            "dueDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_transactions(client: FineractClient, client_id: int) -> dict[str, Any]:
    """List all financial transactions linked to a client."""
    try:
        return safe_result(client.get(f"clients/{client_id}/transactions"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_client_addresses(client: FineractClient, client_id: int) -> dict[str, Any]:
    """Get addresses registered for a client."""
    try:
        return safe_result(client.get(f"clients/{client_id}/addresses"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))
