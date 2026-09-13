from typing import Any, List

def generate_loan_suggestions(intent: str, data: Any) -> List[str]:
    suggestions: List[str] = []

    if intent == "get_overdue_loans":
        loans = data.get("overdueLoans", []) if isinstance(data, dict) else []

        for loan in loans:
            if not isinstance(loan, dict):
                continue

            loan_id = loan.get("loanId")

            suggestions.append(f"Apply a late fee to loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")
            suggestions.append(f"Send a repayment reminder for loan {loan_id}")

    elif intent == "get_loan_details":
        if not isinstance(data, dict):
            return suggestions

        loan_id = data.get("loanId")
        status = (data.get("status") or "").lower()

        if "active" in status:
            suggestions.append(f"Make a repayment for loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")

        if "pending" in status or "submitted" in status:
            suggestions.append(f"Approve loan {loan_id}")
            suggestions.append(f"Reject loan {loan_id}")

    return suggestions