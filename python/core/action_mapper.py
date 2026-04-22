from typing import List

# Centralized mapping: status → allowed actions
ACTIONS_BY_STATUS = {
    "active": ["repay", "view_schedule"],
    "pending": ["approve", "reject"],
    "submitted": ["approve", "reject"],
    "inactive": ["activate"],
    "closed": [],
}


def get_actions_for_status(status: str) -> List[str]:
    """
    Returns allowed actions based on normalized status.
    """
    if not status:
        return []

    status = status.lower()
    return ACTIONS_BY_STATUS.get(status, [])