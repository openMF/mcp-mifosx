"""
NOTE:
This module is experimental and intended for client-side or AI-agent usage.
It is NOT integrated into MCP tool responses to keep MCP layer clean.
"""

MAX_SUGGESTIONS = 5


def generate_suggestions(intent, data):
    """
    Generate context-aware suggestions based on MCP tool responses.

    Returns:
        dict:
        {
            "suggestions": [str],
            "suggestions_structured": [dict]
        }
    """

    raw_suggestions = []
    structured = []

    # 🔹 Case 1: Overdue loans
    if intent == "get_overdue_loans":
        if not isinstance(data, list):
            return _empty_response()

        for loan in data:
            loan_id = loan.get("id")
            if not loan_id:
                continue

            # Text suggestions
            raw_suggestions.extend([
                f"Apply late fee to loan {loan_id}",
                f"View repayment schedule for loan {loan_id}",
                f"Send reminder for loan {loan_id}",
            ])

            # Structured suggestions
            structured.extend([
                {
                    "action": "apply_late_fee",
                    "label": "Apply late fee",
                    "loanId": loan_id,
                },
                {
                    "action": "view_repayment_schedule",
                    "label": "View repayment schedule",
                    "loanId": loan_id,
                },
                {
                    "action": "send_reminder",
                    "label": "Send reminder",
                    "loanId": loan_id,
                },
            ])

    # 🔹 Case 2: Loan details
    elif intent == "get_loan_details":
        if not isinstance(data, dict):
            return _empty_response()

        loan_id = data.get("loanId")
        if not loan_id:
            return _empty_response()

        status = str(data.get("status", "")).lower()

        if "active" in status:
            raw_suggestions.extend([
                f"Make repayment for loan {loan_id}",
                f"View repayment schedule for loan {loan_id}",
            ])

            structured.extend([
                {
                    "action": "make_repayment",
                    "label": "Make repayment",
                    "loanId": loan_id,
                },
                {
                    "action": "view_repayment_schedule",
                    "label": "View repayment schedule",
                    "loanId": loan_id,
                },
            ])

        if "pending" in status or "submitted" in status:
            raw_suggestions.extend([
                f"Approve loan {loan_id}",
                f"Reject loan {loan_id}",
            ])

            structured.extend([
                {
                    "action": "approve_loan",
                    "label": "Approve loan",
                    "loanId": loan_id,
                },
                {
                    "action": "reject_loan",
                    "label": "Reject loan",
                    "loanId": loan_id,
                },
            ])

    else:
        return _empty_response()

    # 🔹 Deduplicate
    raw_suggestions = list(dict.fromkeys(raw_suggestions))
    structured = _deduplicate_structured(structured)

    # 🔹 Limit results
    raw_suggestions = raw_suggestions[:MAX_SUGGESTIONS]
    structured = structured[:MAX_SUGGESTIONS]

    return {
        "suggestions": raw_suggestions,
        "suggestions_structured": structured,
    }


# ------------------ Helpers ------------------ #

def _empty_response():
    return {
        "suggestions": [],
        "suggestions_structured": []
    }


def _deduplicate_structured(items):
    seen = set()
    result = []

    for item in items:
        key = (item.get("action"), item.get("loanId"))
        if key not in seen:
            seen.add(key)
            result.append(item)

    return result