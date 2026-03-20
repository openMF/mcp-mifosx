# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from tools.domains.clients import (
    search_clients_by_name, get_client_details, get_client_accounts,
    create_client, activate_client, update_client_mobile,
    close_client, create_group, get_group_details,
    get_client_identifiers, create_client_identifier,
    get_client_documents, get_client_charges, apply_client_charge,
    get_client_transactions, get_client_addresses
)
from tools.domains.groups import (
    list_groups, activate_group, add_group_member,
    list_centers, get_center, create_center
)
from tools.domains.loans import (
    get_loan_details, get_repayment_schedule, create_loan,
    approve_and_disburse_loan, reject_loan_application,
    make_loan_repayment, apply_late_fee, waive_interest,
    get_overdue_loans, create_group_loan,
    undo_loan_approval, undo_loan_disbursal, get_loan_template, reschedule_loan
)
from tools.domains.savings import (
    get_savings_account, get_savings_transactions, create_savings_account,
    approve_and_activate_savings, close_savings_account, deposit_savings,
    withdraw_savings, apply_savings_charge, calculate_and_post_interest
)
from tools.domains.staff import (
    list_staff, get_staff_details, list_offices, get_office_details
)
from tools.domains.accounting import (
    list_gl_accounts, get_journal_entries, create_journal_entry
)
from tools.domains.reports import (
    list_reports, get_report, run_report, create_report, update_report
)
from tools.domains.products import (
    list_loan_products, get_loan_product,
    list_savings_products, get_savings_product
)
from tools.domains.charges import (
    list_charges, get_charge, create_charge, update_charge
)
from tools.domains.codetables import (
    list_codes, get_code_values, list_datatables
)


class DomainRegistry:
    """
    Domain router that maps user intent to a filtered subset of MCP tools.

    This is used by MCP clients that want to limit the tool context window
    based on the user's query. Instead of sending all 49 tools to the LLM,
    the router returns only the tools relevant to the detected domain(s).

    Usage:
        from tools.registry import router
        tools = router.route_intent("Show me the repayment schedule for loan 5")
        # returns only the Loans domain tools

    Full domain map:
        router.domain_map["clients"]    - 15 client & KYC tools
        router.domain_map["groups"]     - 6 group & center tools
        router.domain_map["loans"]      - 14 loan tools
        router.domain_map["savings"]    - 9 savings tools
        router.domain_map["staff"]      - 4 staff & office tools
        router.domain_map["accounting"] - 3 accounting tools
        router.domain_map["reports"]    - 5 report definition & execution tools
        router.domain_map["products"]   - 4 loan & savings product tools
        router.domain_map["charges"]    - 4 charge/fee tools
        router.domain_map["codetables"] - 3 code table & datatable tools
    """

    def __init__(self):
        self.domain_map = {
            "clients": [
                search_clients_by_name, get_client_details, get_client_accounts,
                create_client, activate_client, update_client_mobile, close_client,
                create_group, get_group_details, get_client_identifiers,
                create_client_identifier, get_client_documents, get_client_charges,
                apply_client_charge, get_client_transactions, get_client_addresses
            ],
            "groups": [
                list_groups, activate_group, add_group_member,
                list_centers, get_center, create_center
            ],
            "loans": [
                get_loan_details, get_repayment_schedule, create_loan,
                approve_and_disburse_loan, reject_loan_application,
                make_loan_repayment, apply_late_fee, waive_interest,
                get_overdue_loans, create_group_loan,
                undo_loan_approval, undo_loan_disbursal, get_loan_template, reschedule_loan
            ],
            "savings": [
                get_savings_account, get_savings_transactions, create_savings_account,
                approve_and_activate_savings, close_savings_account, deposit_savings,
                withdraw_savings, apply_savings_charge, calculate_and_post_interest
            ],
            "staff": [
                list_staff, get_staff_details, list_offices, get_office_details
            ],
            "accounting": [
                list_gl_accounts, get_journal_entries, create_journal_entry
            ],
            "reports": [
                list_reports, get_report, run_report, create_report, update_report
            ],
            "products": [
                list_loan_products, get_loan_product,
                list_savings_products, get_savings_product
            ],
            "charges": [
                list_charges, get_charge, create_charge, update_charge
            ],
            "codetables": [
                list_codes, get_code_values, list_datatables
            ]
        }

    def get_domain(self, domain: str) -> list:
        """
        Return all tools for a specific domain by name.
        Valid names: 'clients', 'groups', 'loans', 'savings', 'staff', 'accounting', 'charges', 'codetables'
        """
        return self.domain_map.get(domain, [])

    def get_all_tools(self) -> list:
        """Return the full flat list of all 61 tools across all domains."""
        all_tools = []
        seen = set()
        for tools in self.domain_map.values():
            for tool in tools:
                if tool.name not in seen:
                    all_tools.append(tool)
                    seen.add(tool.name)
        return all_tools

    def route_intent(self, user_query: str) -> list:
        """
        Analyzes a user prompt and returns only the tool objects needed.
        Used to keep the LLM context window lean for single-domain queries.
        """
        query = user_query.lower()
        active_domains = set()

        # Loans
        if any(w in query for w in ["loan", "repayment", "disburse", "overdue", "arrear", "reject", "waive", "installment", "undo", "reschedule", "template"]):
            active_domains.add("loans")

        # Clients
        if any(w in query for w in ["client", "person", "search", "activate", "mobile", "kyc", "identifier", "address", "charge", "fee", "document"]):
            active_domains.add("clients")

        # Groups & Centers
        if any(w in query for w in ["group", "center", "centre", "member", "lending group"]):
            active_domains.add("groups")

        # Savings
        if any(w in query for w in ["saving", "deposit", "withdraw", "balance", "wallet", "interest"]):
            active_domains.add("savings")

        # Staff & Offices
        if any(w in query for w in ["staff", "officer", "employee", "office", "branch"]):
            active_domains.add("staff")

        # Accounting
        if any(w in query for w in ["journal", "ledger", "account", "gl account", "debit", "credit", "accounting"]):
            active_domains.add("accounting")

        # Reports
        if any(w in query for w in ["report", "run report", "report template", "portfolio report", "generate report", "sql report"]):
            active_domains.add("reports")

        # Products
        if any(w in query for w in ["product", "loan product", "savings product", "product type", "available products"]):
            active_domains.add("products")

        # Charges
        if any(w in query for w in ["charge", "fee", "penalty", "late fee", "disbursement fee"]):
            active_domains.add("charges")

        # Code Tables
        if any(w in query for w in ["code", "dropdown", "code value", "gender", "id type", "datatable", "custom field"]):
            active_domains.add("codetables")

        # Default: return clients for basic lookup if nothing matched
        if not active_domains:
            active_domains.add("clients")

        # Flatten and deduplicate across matched domains
        seen = set()
        result = []
        for domain in active_domains:
            for tool in self.domain_map[domain]:
                if tool.name not in seen:
                    result.append(tool)
                    seen.add(tool.name)
        return result


# Global router instance — import this in your MCP client
router = DomainRegistry()