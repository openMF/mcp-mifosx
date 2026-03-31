"""
NOTE:
This module is experimental and intended for client-side or AI-agent usage.
It is NOT integrated into MCP tool responses to keep MCP layer clean.
"""


def generate_suggestions(intent, data):
    """
    Generate context-aware suggestions based on MCP tool responses.

    This function analyzes the intent (which tool was called)
    and the returned data to provide meaningful next-step actions.

    Args:
        intent (str): Name of the MCP tool / action performed
        data (dict or list): Response data from the tool

    Returns:
        list: A list of suggested next actions (strings)
    """

    suggestions = []

    # 🔹 Case 1: Overdue loans → suggest recovery actions
    if intent == "get_overdue_loans":
        # Validate data type
        if not isinstance(data, list):
            return []

        for loan in data:
            loan_id = loan.get("id")
            if not loan_id:
                continue

            suggestions.append(f"Apply late fee to loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")
            suggestions.append(f"Send reminder for loan {loan_id}")

    # 🔹 Case 2: Loan details → suggest actions based on loan status
    elif intent == "get_loan_details":
        # Validate data type
        if not isinstance(data, dict):
            return []

        loan_id = data.get("loanId")
        if not loan_id:
            return []

        # Normalize status safely
        status = str(data.get("status", "")).lower()

        # Active loan → repayment actions
        if "active" in status:
            suggestions.append(f"Make repayment for loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")

        # Pending/submitted loan → approval actions
        if "pending" in status or "submitted" in status:
            suggestions.append(f"Approve loan {loan_id}")
            suggestions.append(f"Reject loan {loan_id}")

    # 🔹 Default fallback
    return suggestions