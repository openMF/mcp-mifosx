# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Savings domain — create, approve, deposit, withdraw, and manage savings accounts.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import fineract_today, fmt_date, safe_result

# ── Read ───────────────────────────────────────────────────────────────

def get_savings_account(client: FineractClient, account_id: int) -> dict[str, Any]:
    """Get key details of a savings account.

    Parameters
    ----------
    account_id : int
        Fineract savings account ID.
    """
    try:
        data = client.get(f"savingsaccounts/{account_id}")
        tl = data.get("timeline", {})
        summary = data.get("summary", {})
        return safe_result({
            "savingsId": data.get("id"),
            "accountNo": data.get("accountNo"),
            "clientName": data.get("clientName"),
            "productName": data.get("savingsProductName"),
            "status": data.get("status", {}).get("value"),
            "balance": summary.get("accountBalance"),
            "availableBalance": summary.get("availableBalance"),
            "activationDate": fmt_date(tl.get("activatedOnDate")),
            "nominalAnnualInterestRate": data.get("nominalAnnualInterestRate"),
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_savings_transactions(client: FineractClient, account_id: int) -> dict[str, Any]:
    """Get transaction history for a savings account.

    Parameters
    ----------
    account_id : int
        Fineract savings account ID.
    """
    try:
        data = client.get(f"savingsaccounts/{account_id}?associations=transactions")
        txns = data.get("transactions", [])
        return safe_result({
            "savingsId": account_id,
            "transactions": [
                {
                    "transactionId": t.get("id"),
                    "type": t.get("transactionType", {}).get("value"),
                    "date": fmt_date(t.get("date")),
                    "amount": t.get("amount"),
                    "runningBalance": t.get("runningBalance"),
                }
                for t in txns
            ],
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Create & Lifecycle ─────────────────────────────────────────────────

def create_savings_account(
    client: FineractClient,
    client_id: int,
    *,
    product_id: int = 1,
) -> dict[str, Any]:
    """Create a new savings account for a client.

    Parameters
    ----------
    client_id : int
        Client to open the account for.
    product_id : int
        Savings product ID. Default ``1``.
    """
    today = fineract_today()
    payload = {
        "clientId": client_id,
        "productId": product_id,
        "submittedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    try:
        result = client.post("savingsaccounts", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def approve_and_activate_savings(
    client: FineractClient, account_id: int
) -> dict[str, Any]:
    """Approve and activate a savings account in two steps.

    Parameters
    ----------
    account_id : int
        Savings account to approve and activate.
    """
    today = fineract_today()
    # Step 1: Approve
    try:
        client.post(f"savingsaccounts/{account_id}?command=approve", {
            "approvedOnDate": today,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
    except FineractError as exc:
        return safe_result(None, error=f"Approval failed: {exc}")

    # Step 2: Activate
    try:
        result = client.post(f"savingsaccounts/{account_id}?command=activate", {
            "activatedOnDate": today,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=f"Activation failed (account was approved): {exc}")


def close_savings_account(client: FineractClient, account_id: int) -> dict[str, Any]:
    """Close a savings account.

    Parameters
    ----------
    account_id : int
        Active savings account to close.
    """
    try:
        result = client.post(f"savingsaccounts/{account_id}?command=close", {
            "closedOnDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Transactions ───────────────────────────────────────────────────────

def deposit(client: FineractClient, account_id: int, amount: float) -> dict[str, Any]:
    """Deposit money into an active savings account.

    Parameters
    ----------
    account_id : int
        Active savings account ID.
    amount : float
        Amount to deposit.
    """
    try:
        result = client.post(f"savingsaccounts/{account_id}/transactions?command=deposit", {
            "transactionDate": fineract_today(),
            "transactionAmount": amount,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def withdraw(client: FineractClient, account_id: int, amount: float) -> dict[str, Any]:
    """Withdraw money from an active savings account.

    Parameters
    ----------
    account_id : int
        Active savings account ID.
    amount : float
        Amount to withdraw.
    """
    try:
        result = client.post(f"savingsaccounts/{account_id}/transactions?command=withdrawal", {
            "transactionDate": fineract_today(),
            "transactionAmount": amount,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def apply_savings_charge(
    client: FineractClient,
    account_id: int,
    amount: float,
    charge_id: int = 1,
) -> dict[str, Any]:
    """Apply a charge/fee to a savings account.

    Parameters
    ----------
    account_id : int
        Savings account.
    amount : float
        Charge amount.
    charge_id : int
        Charge definition ID.
    """
    try:
        result = client.post(f"savingsaccounts/{account_id}/charges", {
            "chargeId": charge_id,
            "amount": amount,
            "dueDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def calculate_and_post_interest(
    client: FineractClient, account_id: int
) -> dict[str, Any]:
    """Calculate and post accrued interest to a savings account.

    Parameters
    ----------
    account_id : int
        Active savings account ID.
    """
    # Step 1: Calculate
    try:
        client.post(
            f"savingsaccounts/{account_id}?command=calculateInterest", {}
        )
    except FineractError as exc:
        return safe_result(None, error=f"Calculate interest failed: {exc}")

    # Step 2: Post
    try:
        result = client.post(
            f"savingsaccounts/{account_id}?command=postInterest", {}
        )
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=f"Post interest failed: {exc}")
