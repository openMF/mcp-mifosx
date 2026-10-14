# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.


from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client
from tools.utils import get_fineract_today

# --- SAVINGS READ & SEARCH OPERATIONS ---

@tool
def get_savings_account(account_id: int):
    """Answers: 'What is the balance of savings account #123?'"""
    print(f"[Tool] Fetching details for Savings Account #{account_id}...")
    return fineract_client.execute_get(f"savingsaccounts/{account_id}")

@tool
def get_savings_transactions(account_id: int):
    """Answers: 'Show me the transaction history for savings account #123'"""
    print(f"[Tool] Fetching transaction history for Savings Account #{account_id}...")
    # Fineract returns transactions in the associations parameter
    response = fineract_client.execute_get(f"savingsaccounts/{account_id}?associations=transactions")
    if "error" in response: return response
    return response.get("transactions", [])


# --- SAVINGS LIFECYCLE OPERATIONS (WRITE) ---

@tool
def create_savings_account(client_id: int, product_id: int = 1):
    """Answers: 'Open a new savings account for this client'"""
    print(f"[Tool] Opening Savings Account for Client #{client_id}...")
    today = get_fineract_today()

    payload = {
        "clientId": client_id,
        "productId": product_id,
        "submittedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post("savingsaccounts", payload)

def _savings_status(account_id: int):
    """The account's status as Fineract reports it now, or None if it can't be read."""
    account = fineract_client.execute_get(f"savingsaccounts/{account_id}")
    if not isinstance(account, dict) or "error" in account:
        return None
    return account.get("status", {}).get("value")

@tool
def approve_and_activate_savings(account_id: int, already_approved: bool = False):
    """Answers: 'Approve and activate this pending savings account'
    With already_approved=True only the activation is done (finishing an earlier
    call whose approval succeeded but whose activation failed)."""
    print(f"[Tool] Approving & Activating Savings Account #{account_id}...")
    today = get_fineract_today()
    base_payload = {"dateFormat": "dd MMMM yyyy", "locale": "en"}

    # Step 1: Approve
    if not already_approved:
        approve_payload = {**base_payload, "approvedOnDate": today}
        approval = fineract_client.execute_post(f"savingsaccounts/{account_id}?command=approve", approve_payload)
        if "error" in approval: return approval

    # Step 2: Activate
    activate_payload = {**base_payload, "activatedOnDate": today}
    activation = fineract_client.execute_post(f"savingsaccounts/{account_id}?command=activate", activate_payload)
    if "error" not in activation:
        return activation
    # A timeout or server error can follow an activation Fineract did make, so read
    # the account's status instead of assuming it is still 'Approved'
    status = _savings_status(account_id)
    if status == "Active":
        return {"savingsId": account_id, "status": status,
                "note": f"The activation call returned an error ({activation['error']}), but savings account "
                        f"{account_id} is now Active, so it was activated."}
    if status is None:
        return {
            **activation,
            "error": f"The activation of savings account {account_id} failed: {activation['error']} Its current "
                     "status could not be read, so check it with get_savings_account before calling this tool again.",
        }
    if not already_approved and status == "Approved":
        return {
            **activation,
            "error": f"Savings account {account_id} was approved, but the activation failed: {activation['error']} "
                     "The account is now 'Approved'. Fix the cause and call this tool again to activate it.",
        }
    return activation

@tool
def close_savings_account(account_id: int):
    """Answers: 'Close this savings account'"""
    print(f"[Tool] Closing Savings Account #{account_id}...")
    today = get_fineract_today()

    payload = {
        "closedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}?command=close", payload)


# --- SAVINGS TRANSACTION OPERATIONS ---

@tool
def deposit_savings(account_id: int, amount: float):
    """Answers: 'Deposit $500 into this savings account'"""
    print(f"[Tool] Depositing {amount} into Savings Account #{account_id}...")
    today = get_fineract_today()

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "paymentTypeId": 1  # Standard Cash Receipt
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/transactions?command=deposit", payload)

@tool
def withdraw_savings(account_id: int, amount: float):
    """Answers: 'Withdraw $100 from this savings account'"""
    print(f"[Tool] Withdrawing {amount} from Savings Account #{account_id}...")
    today = get_fineract_today()

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "paymentTypeId": 2  # Standard Cash Payout
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/transactions?command=withdrawal", payload)

@tool
def apply_savings_charge(account_id: int, amount: float, charge_id: int = 1):
    """Answers: 'Deduct a $15 account maintenance fee from this savings account'"""
    print(f"[Tool] Applying charge of {amount} to Savings Account #{account_id}...")
    today = get_fineract_today()

    payload = {
        "chargeId": charge_id,
        "amount": amount,
        "dueDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/charges", payload)

@tool
def calculate_and_post_interest(account_id: int):
    """Answers: 'Calculate and post the accrued interest for this savings account'"""
    print(f"[Tool] Posting interest for Savings Account #{account_id}...")
    # This specific command usually doesn't require a payload in Fineract
    return fineract_client.execute_post(f"savingsaccounts/{account_id}?command=postInterest", payload={})
