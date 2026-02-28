from tools.domains.clients import (
    search_clients_by_name, get_client_details, get_client_accounts, 
    create_client, activate_client, update_client_mobile, 
    close_client, create_group, get_group_details
)
from tools.domains.loans import (
    get_loan_details, get_repayment_schedule, create_loan, 
    approve_and_disburse_loan, reject_loan_application, 
    make_loan_repayment, apply_late_fee, waive_interest
)
from tools.domains.savings import (
    get_savings_account, get_savings_transactions, create_savings_account, 
    approve_and_activate_savings, close_savings_account, deposit_savings, 
    withdraw_savings, apply_savings_charge, calculate_and_post_interest
)

class DomainRegistry:
    def __init__(self):
        # We group actual TOOL OBJECTS by domain to prevent overwhelming the AI context window
        self.domain_map = {
            "clients": [
                search_clients_by_name, get_client_details, get_client_accounts, 
                create_client, activate_client, update_client_mobile,
                close_client, create_group, get_group_details
            ],
            "loans": [
                get_loan_details, get_repayment_schedule, create_loan, 
                approve_and_disburse_loan, reject_loan_application, 
                make_loan_repayment, apply_late_fee, waive_interest
            ],
            "savings": [
                get_savings_account, get_savings_transactions, create_savings_account, 
                approve_and_activate_savings, close_savings_account, deposit_savings, 
                withdraw_savings, apply_savings_charge, calculate_and_post_interest
            ]
        }

    def route_intent(self, user_query: str) -> list:
        """
        Analyzes the prompt and returns ONLY the required tool objects for LangGraph.
        """
        query = user_query.lower()
        active_tools = []

        # Routing for Loans
        if any(word in query for word in ["loan", "repayment", "disburse", "fee", "overdue", "interest", "reject"]):
            active_tools.extend(self.domain_map["loans"])
            
        # Routing for Clients & Groups
        if any(word in query for word in ["client", "group", "person", "member", "search", "activate", "mobile"]):
            active_tools.extend(self.domain_map["clients"])
            
        # Routing for Savings
        if any(word in query for word in ["saving", "deposit", "withdraw", "balance", "history", "fee", "interest"]):
            active_tools.extend(self.domain_map["savings"])

        # Default to clients if no keywords match to ensure the LLM has basic lookup capabilities
        if not active_tools:
            return self.domain_map["clients"]

        # Deduplicate the list (in case a word triggered multiple domains) and return
        return list(set(active_tools))

# Global router instance
router = DomainRegistry()