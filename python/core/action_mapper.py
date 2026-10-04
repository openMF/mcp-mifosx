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


def get_actions_for_status(domain: str, status: str) -> List[str]:
    """
    Returns allowed actions for a status within a domain.

    Parameters:
        domain: Action-map key ("loan", "savings", or "client").
        status: Fineract status value, e.g. "Active" or "Pending Approval".

    Returns:
        The allowed actions, or an empty list when nothing matches.
    """
    if not domain or not status:
        return []

    actions_by_status = ACTIONS_BY_DOMAIN_STATUS.get(domain.lower(), {})
    status = status.lower()
    if status in actions_by_status:
        return actions_by_status[status]

    # Fineract value strings may carry suffixes (e.g. "Pending Approval")
    for key, actions in actions_by_status.items():
        if status.startswith(key):
            return actions

    return []
