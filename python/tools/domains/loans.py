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

MONTHS = ["", "January", "February", "March", "April", "May", "June",
          "July", "August", "September", "October", "November", "December"]

@tool
def update_loan(loan_id: int, principal: float = None, months: int = None, product_id: int = None):
    """Answers: 'Update Loan #123 to increase principal to $25,000' or 'Change loan term to 18 months'"""
    print(f"[Tool] Updating Loan #{loan_id}...")
    
    # Validate input types
    if not isinstance(loan_id, int) or loan_id <= 0:
        return {"error": f"Invalid loan_id: {loan_id}. Must be a positive integer."}
    if principal is not None and (not isinstance(principal, (int, float)) or (principal is not None and principal <= 0)):
        return {"error": f"Invalid principal: {principal}. Must be a positive number."}
    if months is not None and (not isinstance(months, int) or (months is not None and months <= 0)):
        return {"error": f"Invalid months: {months}. Must be a positive integer."}
    if product_id is not None and (not isinstance(product_id, int) or (product_id is not None and product_id <= 0)):
        return {"error": f"Invalid product_id: {product_id}. Must be a positive integer."}
    
    current = fineract_client.execute_get(f"loans/{loan_id}")
    if "error" in current:
        return current
    
    # Validate loan is in updatable state
    status = current.get("status", {}).get("value", "").lower()
    if "pending" not in status and "submitted" not in status:
        return {"error": f"Loan {loan_id} is in status '{status}'. Only pending/submitted loans can be updated."}
    
    timeline = current.get("timeline", {})
    
    def fmt_date(key):
        d = timeline.get(key)
        if isinstance(d, list) and len(d) >= 3:
            return f"{d[2]} {MONTHS[d[1]]} {d[0]}"
        return None
    
    payload = {
        "productId": product_id or current.get("productId") or current.get("product", {}).get("id", 1),
        "principal": str(principal) if principal else str(current.get("principal", 0)),
        "loanTermFrequency": months or current.get("termFrequency") or current.get("loanTermFrequency", 1),
        "loanTermFrequencyType": 2,
        "numberOfRepayments": months or current.get("numberOfRepayments", 1),
        "repaymentEvery": 1,
        "repaymentFrequencyType": 2,
        "interestRatePerPeriod": current.get("interestRatePerPeriod", 5.0),
        "amortizationType": 1,
        "interestType": 0,
        "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": current.get("transactionProcessingStrategyCode", "mifos-standard-strategy"),
        "loanType": (current.get("loanType", {}).get("value") or "individual").lower(),
        "locale": "en",
        "dateFormat": "dd MMMM yyyy",
    }
    
    if fmt_date("expectedDisbursementDate"):
        payload["expectedDisbursementDate"] = fmt_date("expectedDisbursementDate")
    if fmt_date("submittedOnDate"):
        payload["submittedOnDate"] = fmt_date("submittedOnDate")
    
    return fineract_client.execute_put(f"loans/{loan_id}", payload)

@tool
def delete_loan(loan_id: int):
    """Answers: 'Delete loan application #123' or 'Cancel this draft loan'"""
    # Validate loan_id
    if not isinstance(loan_id, int) or loan_id <= 0:
        return {"error": f"Invalid loan_id: {loan_id}. Must be a positive integer."}
    
    print(f"[Tool] Deleting Loan #{loan_id}...")
    return fineract_client.execute_delete(f"loans/{loan_id}")
