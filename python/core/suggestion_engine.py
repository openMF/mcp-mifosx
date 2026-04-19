from typing import Any, Dict, List, Union


def generate_suggestions(intent: str, data: Union[Dict[str, Any], List[Any], None]) -> List[str]:
    """
    Generate context-aware suggestions based on MCP tool responses.
    """

    suggestions: List[str] = []

    # 🔹 Case 1: Overdue loans
    if intent == "get_overdue_loans":

        # Extract loan list safely
        if isinstance(data, dict):
            if "error" in data:
                return []  # Don't generate suggestions for error responses
            loans = data.get("overdueLoans", [])
        else:
            loans = data or []

        # Ensure it's iterable list
        if not isinstance(loans, list):
            return []

        for loan in loans:
            if not isinstance(loan, dict):
                continue

            loan_id = loan.get("loanId") or loan.get("id")

            if not loan_id:
                continue

            suggestions.append(f"Apply a late fee to loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")
            suggestions.append(f"Send a repayment reminder for loan {loan_id}")

    # 🔹 Case 2: Loan details
    elif intent == "get_loan_details":

        if not isinstance(data, dict):
            return suggestions

        if "error" in data:
            return suggestions

        loan_id = data.get("loanId")

        # Safe normalization (prevents None.lower() crash)
        status = (data.get("status") or "").lower()

        if not loan_id:
            return suggestions

        # Active loan actions
        if "active" in status:
            suggestions.append(f"Make a repayment for loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")

        # Pending/submitted actions
        if "pending" in status or "submitted" in status:
            suggestions.append(f"Approve loan {loan_id}")
            suggestions.append(f"Reject loan {loan_id}")

    return suggestions