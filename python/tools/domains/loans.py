# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import datetime
from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

# --- LOAN READ OPERATIONS ---

@tool
def get_loan_details(loan_id: int):
    """Answers: 'What is the status and outstanding balance of Loan #12345?'"""
    print(f"[Tool] Fetching summary details for Loan #{loan_id}...")
    return fineract_client.execute_get(f"loans/{loan_id}")

@tool
def get_repayment_schedule(loan_id: int):
    """Answers: 'What is the repayment schedule for Loan #12345?'"""
    print(f"[Tool] Fetching repayment schedule for Loan #{loan_id}...")
    response = fineract_client.execute_get(f"loans/{loan_id}?associations=repaymentSchedule")
    if "error" in response: return response
    return response.get("repaymentSchedule", {})

@tool
def get_loan_history(loan_id: int):
    """Answers: 'Show me the full transaction history for Loan #12345', 'What was the last repayment?', 'How much has been paid so far on this loan?', 'Create a loan with the same terms as the previous cycle'"""
    print(f"[Tool] Fetching full transaction history for Loan #{loan_id}...")
    response = fineract_client.execute_get(
        f"loans/{loan_id}?associations=transactions,repaymentSchedule,charges"
    )
    if "error" in response:
        return response

    # --- Extract core loan metadata ---
    summary   = response.get("summary", {})
    timeline  = response.get("timeline", {})
    status    = response.get("status", {}).get("value", "Unknown")

    # --- Extract and clean each transaction ---
    raw_txns = response.get("transactions", [])
    transactions = []
    for t in raw_txns:
        if t.get("reversed", False):          # skip reversed/voided entries
            continue
        transactions.append({
            "id":           t.get("id"),
            "type":         t.get("type", {}).get("value"),          # e.g. "Repayment", "Disbursement"
            "date":         "-".join(str(d) for d in t.get("date", [])),
            "amount":       t.get("amount"),
            "principalPortion":  t.get("principalPortion"),
            "interestPortion":   t.get("interestPortion"),
            "feeChargesPortion": t.get("feeChargesPortion"),
            "outstandingLoanBalance": t.get("outstandingLoanBalance"),
        })

    # --- Extract applied charges ---
    charges = [
        {
            "chargeId":   c.get("chargeId"),
            "name":       c.get("name"),
            "amount":     c.get("amount"),
            "amountPaid": c.get("amountPaid"),
            "dueDate":    "-".join(str(d) for d in c.get("dueDate", [])),
            "paid":       c.get("paid"),
            "waived":     c.get("waived"),
        }
        for c in response.get("charges", [])
    ]

    return {
        "loanId":             loan_id,
        "accountNo":          response.get("accountNo"),
        "status":             status,
        "principal":          response.get("principal"),
        "interestRate":       response.get("interestRatePerPeriod"),
        "termMonths":         response.get("termFrequency"),
        "product":            response.get("loanProductName"),
        "disbursedOn":        "-".join(str(d) for d in timeline.get("actualDisbursementDate", [])),
        "totalRepaid":        summary.get("totalRepaymentTransaction"),
        "totalOutstanding":   summary.get("totalOutstanding"),
        "totalOverdue":       summary.get("totalOverdue", 0),
        "transactions":       transactions,
        "charges":            charges,
        "transactionCount":   len(transactions),
    }

@tool
def get_overdue_loans(client_id: int):
    """Answers: 'Show me all overdue loans for this client' or 'Which loans are in arrears?'"""
    print(f"[Tool] Fetching overdue loans for Client #{client_id}...")
    response = fineract_client.execute_get(f"loans?clientId={client_id}&loanStatus=300&inArrears=true&associations=repaymentSchedule")
    if "error" in response:
        return response
    loans = response.get("pageItems", response) if isinstance(response, dict) else response
    overdue = []
    for loan in (loans if isinstance(loans, list) else []):
        summary = loan.get("summary", {})
        if summary.get("totalOverdue", 0) > 0 or loan.get("inArrears", False):
            overdue.append({
                "loanId": loan.get("id"),
                "accountNo": loan.get("accountNo"),
                "principal": summary.get("principalOutstanding"),
                "totalOverdue": summary.get("totalOverdue"),
                "status": loan.get("status", {}).get("value"),
            })
    return {"overdueLoans": overdue, "count": len(overdue)}


# --- LOAN LIFECYCLE OPERATIONS (WRITE) ---

@tool
def create_loan(client_id: int, principal: float, months: int, product_id: int = 1):
    """Answers: 'Create a new loan for Client X for MXN 20,000 over 12 months'"""
    print(f"[Tool] Creating {principal} loan for Client #{client_id}...")
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
def create_group_loan(group_id: int, principal: float, months: int, product_id: int = 1):
    """Answers: 'Create a group loan for Group #5 for $10,000 over 6 months'"""
    print(f"[Tool] Creating {principal} group loan for Group #{group_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "groupId": group_id,
        "productId": product_id,
        "principal": str(principal),
        "loanTermFrequency": months,
        "loanTermFrequencyType": 2,
        "numberOfRepayments": months,
        "repaymentEvery": 1,
        "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 5.0,
        "amortizationType": 1,
        "interestType": 0,
        "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy",
        "expectedDisbursementDate": today,
        "submittedOnDate": today,
        "locale": "en",
        "dateFormat": "dd MMMM yyyy",
        "loanType": "group"
    }
    return fineract_client.execute_post("loans", payload)

@tool
def approve_and_disburse_loan(loan_id: int, amount: float = None):
    """Answers: 'Approve this loan and disburse today'"""
    today = datetime.datetime.now().strftime("%d %B %Y")
    base_payload = {"dateFormat": "dd MMMM yyyy", "locale": "en"}

    # Step 1: Approve
    print(f"[Tool] Approving Loan #{loan_id}...")
    approve_payload = {**base_payload, "approvedOnDate": today, "note": "AI Approved"}
    approval = fineract_client.execute_post(f"loans/{loan_id}?command=approve", approve_payload)
    if "error" in approval: return approval

    # Step 2: Disburse
    print(f"[Tool] Disbursing Loan #{loan_id}...")
    disburse_payload = {**base_payload, "actualDisbursementDate": today}
    if amount: disburse_payload["transactionAmount"] = amount

    return fineract_client.execute_post(f"loans/{loan_id}?command=disburse", disburse_payload)

@tool
def reject_loan_application(loan_id: int, note: str = "Rejected via AI Agent due to risk profile"):
    """Answers: 'Reject this loan application'"""
    print(f"[Tool] Rejecting Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "rejectedOnDate": today,
        "note": note,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}?command=reject", payload)


# --- LOAN TRANSACTION OPERATIONS ---

@tool
def make_loan_repayment(loan_id: int, amount: float):
    """Answers: 'The client just paid $150 towards their loan.'"""
    print(f"[Tool] Logging repayment of {amount} on Loan #{loan_id}...")
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
def apply_late_fee(loan_id: int, fee_amount: float, charge_id: int = 2):
    """Answers: 'Apply a late fee to the last missed installment'"""
    print(f"[Tool] Applying late fee of {fee_amount} to Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "chargeId": charge_id,
        "amount": fee_amount,
        "dueDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}/charges", payload)

@tool
def waive_interest(loan_id: int, amount: float, note: str = "AI Authorized Waiver"):
    """Answers: 'Waive $50 of interest on this loan to help the client'"""
    print(f"[Tool] Waiving interest of {amount} on Loan #{loan_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "transactionDate": today,
        "transactionAmount": amount,
        "note": note,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"loans/{loan_id}/transactions?command=waiveinterest", payload)