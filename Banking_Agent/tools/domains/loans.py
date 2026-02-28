import datetime
from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

# ==========================================
# 🔍 1. LOAN READ OPERATIONS
# ==========================================

@tool
def get_loan_details(loan_id: int):
    """Answers: 'What is the status and outstanding balance of Loan #12345?'"""
    print(f"📡 [Tool] Fetching summary details for Loan #{loan_id}...")
    return fineract_client.execute_get(f"loans/{loan_id}")

@tool
def get_repayment_schedule(loan_id: int):
    """Answers: 'What is the repayment schedule for Loan #12345?'"""
    print(f"📡 [Tool] Fetching repayment schedule for Loan #{loan_id}...")
    response = fineract_client.execute_get(f"loans/{loan_id}?associations=repaymentSchedule")
    if "error" in response: return response
    return response.get("repaymentSchedule", {})


# ==========================================
# ✍️ 2. LOAN LIFECYCLE OPERATIONS (WRITE)
# ==========================================

@tool
def create_loan(client_id: int, principal: float, months: int, product_id: int = 1):
    """Answers: 'Create a new loan for Client X for MXN 20,000 over 12 months'"""
    print(f"📡 [Tool] Creating {principal} loan for Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "clientId": client_id,
        "productId": product_id,
        "principal": str(principal),
        "loanTermFrequency": months,
        "loanTermFrequencyType": 2,  # 2 = Months
        "numberOfRepayments": months,
        "repaymentEvery": 1,
        "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 5.0,
        "amortizationType": 1,       # Equal installments
        "interestType": 0,
        "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy",
        "expectedDisbursementDate": today,
        "submittedOnDate": today,
        "locale": "en",
        "dateFormat": "dd MMMM yyyy",
        "loanType": "individual"
    }
    return fineract_client.execute_post("loans", payload)

@tool
def approve_and_disburse_loan(loan_id: int, amount: float = None):
    """Answers: 'Approve this loan and disburse today'"""
    today = datetime.datetime.now().strftime("%d %B %Y")
    base_payload = {"dateFormat": "dd MMMM yyyy", "locale": "en"}

    # Step 1: Approve
    print(f"📡 [Tool] Approving Loan #{loan_id}...")
    approve_payload = {**base_payload, "approvedOnDate": today, "note": "AI Approved"}
    approval = fineract_client.execute_post(f"loans/{loan_id}?command=approve", approve_payload)
    if "error" in approval: return approval

    # Step 2: Disburse
    print(f"📡 [Tool] Disbursing Loan #{loan_id}...")
    disburse_payload = {**base_payload, "actualDisbursementDate": today}
    if amount: disburse_payload["transactionAmount"] = amount

    return fineract_client.execute_post(f"loans/{loan_id}?command=disburse", disburse_payload)

@tool
def reject_loan_application(loan_id: int, note: str = "Rejected via AI Agent due to risk profile"):
    """Answers: 'Reject this loan application'"""
    print(f"📡 [Tool] Rejecting Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "rejectedOnDate": today,
        "note": note,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}?command=reject", payload)


# ==========================================
# 💸 3. LOAN TRANSACTION OPERATIONS
# ==========================================

@tool
def make_loan_repayment(loan_id: int, amount: float):
    """Answers: 'The client just paid $150 towards their loan.'"""
    print(f"📡 [Tool] Logging repayment of {amount} on Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "paymentTypeId": 1  # Standard Cash Receipt
    }
    return fineract_client.execute_post(f"loans/{loan_id}/transactions?command=repayment", payload)

@tool
def apply_late_fee(loan_id: int, fee_amount: float):
    """Answers: 'Apply a late fee to the last missed installment'"""
    print(f"📡 [Tool] Applying late fee of {fee_amount} to Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "chargeId": 1,  # Assuming 1 is the default Late Fee ID in Fineract
        "amount": fee_amount,
        "dueDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}/charges", payload)

@tool
def waive_interest(loan_id: int, amount: float, note: str = "AI Authorized Waiver"):
    """Answers: 'Waive $50 of interest on this loan to help the client'"""
    print(f"📡 [Tool] Waiving interest of {amount} on Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "note": note,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}/transactions?command=waiveinterest", payload)