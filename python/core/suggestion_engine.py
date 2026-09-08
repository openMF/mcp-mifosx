from typing import Any, Dict, List, Union
from core.action_mapper import get_actions_for_status


def generate_suggestions(intent: str, data: Union[Dict[str, Any], List[Any], None]) -> List[str]:
    """
    Generate context-aware suggestions across MCP domains.

    Supports:
    - Loans
    - Clients
    - Savings (basic)
    """

    suggestions: List[str] = []

    if not data:
        return suggestions

    # 🔹 Case 1: Overdue Loans
    if intent == "get_overdue_loans":
        loans = data.get("overdueLoans", []) if isinstance(data, dict) else data

        if not isinstance(loans, list):
            return suggestions

        for loan in loans:
            if not isinstance(loan, dict):
                continue

            loan_id = loan.get("loanId") or loan.get("id")

            suggestions.append(f"Apply a late fee to loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")
            suggestions.append(f"Send a repayment reminder for loan {loan_id}")

    # 🔹 Case 2: Loan Details
    elif intent == "get_loan_details":
        if not isinstance(data, dict):
            return suggestions

        loan_id = data.get("loanId")

        status = (data.get("status") or "").lower()

        actions = get_actions_for_status(status)

        if "repay" in actions:
            suggestions.append(f"Make a repayment for loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")

        if "approve" in actions:
            suggestions.append(f"Approve loan {loan_id}")
            suggestions.append(f"Reject loan {loan_id}")

    # 🔹 Case 3: Client Details (NEW)
    elif intent == "get_client_details":
        if not isinstance(data, dict):
            return suggestions

        client_id = data.get("clientId")
        status = (data.get("status") or "").lower()

        actions = get_actions_for_status(status)

        if "activate" in actions:
            suggestions.append(f"Activate client {client_id}")

        suggestions.append(f"View accounts for client {client_id}")

    # 🔹 Case 4: Savings Account (NEW)
    elif intent == "get_savings_account":
        if not isinstance(data, dict):
            return suggestions

        acc_id = data.get("savingsId")

        suggestions.append(f"Deposit money into account {acc_id}")
        suggestions.append(f"Withdraw money from account {acc_id}")
        suggestions.append(f"View transactions for account {acc_id}")

    return suggestions