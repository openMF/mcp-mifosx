import datetime
from tools.mcp_adapter import fineract_client

# ==========================================
# 🔍 1. SAVINGS READ & SEARCH OPERATIONS
# ==========================================

def get_savings_account(account_id: int):
    """Answers: 'What is the balance of savings account #123?'"""
    print(f"📡 [Tool] Fetching details for Savings Account #{account_id}...")
    return fineract_client.execute_get(f"savingsaccounts/{account_id}")

def get_savings_transactions(account_id: int):
    """Answers: 'Show me the transaction history for savings account #123'"""
    print(f"📡 [Tool] Fetching transaction history for Savings Account #{account_id}...")
    # Fineract returns transactions in the associations parameter
    response = fineract_client.execute_get(f"savingsaccounts/{account_id}?associations=transactions")
    if "error" in response: return response
    return response.get("transactions", [])


# ==========================================
# ✍️ 2. SAVINGS LIFECYCLE OPERATIONS (WRITE)
# ==========================================

def create_savings_account(client_id: int, product_id: int = 1):
    """Answers: 'Open a new savings account for this client'"""
    print(f"📡 [Tool] Opening Savings Account for Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "clientId": client_id,
        "productId": product_id,
        "submittedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post("savingsaccounts", payload)

def approve_and_activate_savings(account_id: int):
    """Answers: 'Approve and activate this pending savings account'"""
    print(f"📡 [Tool] Approving & Activating Savings Account #{account_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")
    base_payload = {"dateFormat": "dd MMMM yyyy", "locale": "en"}

    # Step 1: Approve
    approve_payload = {**base_payload, "approvedOnDate": today}
    approval = fineract_client.execute_post(f"savingsaccounts/{account_id}?command=approve", approve_payload)
    if "error" in approval: return approval

    # Step 2: Activate
    activate_payload = {**base_payload, "activatedOnDate": today}
    return fineract_client.execute_post(f"savingsaccounts/{account_id}?command=activate", activate_payload)

def close_savings_account(account_id: int):
    """Answers: 'Close this savings account'"""
    print(f"📡 [Tool] Closing Savings Account #{account_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "closedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}?command=close", payload)


# ==========================================
# 💸 3. SAVINGS TRANSACTION OPERATIONS
# ==========================================

def deposit_savings(account_id: int, amount: float):
    """Answers: 'Deposit $500 into this savings account'"""
    print(f"📡 [Tool] Depositing {amount} into Savings Account #{account_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "paymentTypeId": 1  # Standard Cash Receipt
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/transactions?command=deposit", payload)

def withdraw_savings(account_id: int, amount: float):
    """Answers: 'Withdraw $100 from this savings account'"""
    print(f"📡 [Tool] Withdrawing {amount} from Savings Account #{account_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "paymentTypeId": 2  # Standard Cash Payout
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/transactions?command=withdrawal", payload)

def apply_savings_charge(account_id: int, amount: float, charge_id: int = 1):
    """Answers: 'Deduct a $15 account maintenance fee from this savings account'"""
    print(f"📡 [Tool] Applying charge of {amount} to Savings Account #{account_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "chargeId": charge_id,
        "amount": amount,
        "feeOnMonthDay": today[:5],  # Usually expects MM-DD or similar depending on charge setup
        "dueDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"savingsaccounts/{account_id}/charges", payload)

def calculate_and_post_interest(account_id: int):
    """Answers: 'Calculate and post the accrued interest for this savings account'"""
    print(f"📡 [Tool] Posting interest for Savings Account #{account_id}...")
    # This specific command usually doesn't require a payload in Fineract
    return fineract_client.execute_post(f"savingsaccounts/{account_id}?command=postInterest", payload={})