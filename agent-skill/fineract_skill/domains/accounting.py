# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Accounting domain — GL accounts, journal entries.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import safe_result


def list_gl_accounts(
    client: FineractClient, account_type: int | None = None
) -> dict[str, Any]:
    """List GL accounts (Chart of Accounts).

    Parameters
    ----------
    account_type : int, optional
        Filter: 1=Asset, 2=Liability, 3=Equity, 4=Income, 5=Expense.
    """
    try:
        endpoint = "glaccounts"
        if account_type:
            endpoint += f"?type={account_type}"
        return safe_result(client.get(endpoint))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_journal_entries(
    client: FineractClient,
    gl_account_id: int | None = None,
    transaction_id: str | None = None,
) -> dict[str, Any]:
    """List journal entries, optionally filtered by GL account or transaction.

    Parameters
    ----------
    gl_account_id : int, optional
        GL account ID to filter by.
    transaction_id : str, optional
        Transaction ID to filter by.
    """
    try:
        params = {}
        if gl_account_id:
            params["glAccountId"] = gl_account_id
        if transaction_id:
            params["transactionId"] = transaction_id
        return safe_result(client.get("journalentries", params=params))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_journal_entry(
    client: FineractClient,
    office_id: int,
    date: str,
    credits: list[dict[str, Any]],
    debits: list[dict[str, Any]],
    comment: str = "",
) -> dict[str, Any]:
    """Record a manual journal entry.

    Parameters
    ----------
    office_id : int
        Office / branch ID.
    date : str
        Transaction date in ``"dd MMMM yyyy"`` format.
    credits : list[dict]
        ``[{"glAccountId": int, "amount": float}, ...]``
    debits : list[dict]
        ``[{"glAccountId": int, "amount": float}, ...]``
    comment : str
        Optional narrative.
    """
    payload = {
        "officeId": office_id,
        "transactionDate": date,
        "credits": credits,
        "debits": debits,
        "comments": comment,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    try:
        result = client.post("journalentries", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))
