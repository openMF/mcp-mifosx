"""
NOTE:
This module is experimental and intended for client-side or AI-agent usage.
It is NOT integrated into MCP tool responses to keep MCP layer clean.
"""

def generate_suggestions(intent, data):
    """
    Generate context-aware suggestions based on MCP tool responses.

    This function analyzes the intent (which tool was called)
    and the returned data to provide meaningful next-step actions
    for users inside the Mifos AI Assistant.

    Args:
        intent (str): Name of the MCP tool / action performed
        data (dict or list): Response data from the tool

    Returns:
        list: A list of suggested next actions (strings)
    """

    # Initialize empty suggestion list
    suggestions = []

    # 🔹 Case 1: Overdue loans → suggest recovery actions
    if intent == "get_overdue_loans":
        for loan in data:
            loan_id = loan.get("id")

            # Suggest applying penalty
            suggestions.append(f"Apply a late fee to loan {loan_id}")

            # Suggest checking repayment plan
            suggestions.append(f"View repayment schedule for loan {loan_id}")

            # Suggest notifying the client
            suggestions.append(f"Send a repayment reminder for loan {loan_id}")

    # 🔹 Case 2: Loan details → suggest actions based on loan status
    elif intent == "get_loan_details":
        loan_id = data.get("loanId")

        # Normalize status for safe comparison
        status = data.get("status", "").lower()

        # If loan is active → allow repayment actions
        if "active" in status:
            suggestions.append(f"Make a repayment for loan {loan_id}")
            suggestions.append(f"View repayment schedule for loan {loan_id}")

        # If loan is pending → allow approval/rejection
        if "pending" in status or "submitted" in status:
            suggestions.append(f"Approve loan {loan_id}")
            suggestions.append(f"Reject loan {loan_id}")

    # 🔹 Return final suggestions list
    return suggestions