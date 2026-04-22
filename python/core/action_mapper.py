from typing import List

# Centralized mapping: status → allowed actions
# ACTIONS_BY_STATUS = {
#     "active": ["repay", "view_schedule"],
#     "pending": ["approve", "reject"],
#     "submitted": ["approve", "reject"],
#     "inactive": ["activate"],
#     "closed": [],
# }

# Domain-aware action mapping
ACTIONS_BY_DOMAIN_STATUS = {
    "loan": {
        "active": ["repay", "view_schedule"],
        "pending": ["approve", "reject"],
        "submitted": ["approve", "reject"],
        "closed": [],
    },
    "savings": {
        "active": ["deposit", "withdraw"],
        "inactive": ["activate"],
        "closed": [],
    },
    "client": {
        "inactive": ["activate"],
        "active": ["update", "add_identifier"],
        "closed": [],
    }
}


def get_actions_for_status(status: str) -> List[str]:
    """
    Returns allowed actions based on normalized status.
    """
    if not status:
        return []

    status = status.lower()
    return ACTIONS_BY_DOMAIN_STATUS.get(status, [])