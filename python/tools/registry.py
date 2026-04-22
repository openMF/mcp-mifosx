# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import re
from typing import Any, List

from tools.domains.accounting import create_journal_entry, get_journal_entries, list_gl_accounts
from tools.domains.charges import create_charge, get_charge, list_charges, update_charge
from tools.domains.clients import (
    activate_client,
    apply_client_charge,
    close_client,
    create_client,
    create_client_identifier,
    create_group,
    get_client_accounts,
    get_client_addresses,
    get_client_charges,
    get_client_details,
    get_client_documents,
    get_client_identifiers,
    get_client_transactions,
    get_group_details,
    search_clients_by_name,
    update_client_mobile,
)
from tools.domains.codetables import get_code_values, list_codes, list_datatables
from tools.domains.groups import (
    activate_group,
    add_group_member,
    create_center,
    get_center,
    list_centers,
    list_groups,
)
from tools.domains.loans import (
    apply_late_fee,
    approve_and_disburse_loan,
    create_group_loan,
    create_loan,
    get_loan_details,
    get_loan_template,
    get_overdue_loans,
    get_repayment_schedule,
    make_loan_repayment,
    reject_loan_application,
    reschedule_loan,
    undo_loan_approval,
    undo_loan_disbursal,
    waive_interest,
)
from tools.domains.products import (
    get_loan_product,
    get_savings_product,
    list_loan_products,
    list_savings_products,
)
from tools.domains.reports import create_report, get_report, list_reports, run_report, update_report
from tools.domains.savings import (
    apply_savings_charge,
    approve_and_activate_savings,
    calculate_and_post_interest,
    close_savings_account,
    create_savings_account,
    deposit_savings,
    get_savings_account,
    get_savings_transactions,
    withdraw_savings,
)
from tools.domains.staff import get_office_details, get_staff_details, list_offices, list_staff

# ✅ AI Suggestion Engine
from core.suggestion_engine import generate_suggestions


class DomainRegistry:
    """
    Domain router that maps user intent to a filtered subset of MCP tools.
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
                undo_loan_approval, undo_loan_disbursal,
                get_loan_template, reschedule_loan
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
            ],
        }

    def get_domain(self, domain: str) -> List[Any]:
        return self.domain_map.get(domain, [])

    def get_all_tools(self) -> List[Any]:
        all_tools = []
        seen = set()

        for tools in self.domain_map.values():
            for tool in tools:
                if tool.name not in seen:
                    all_tools.append(tool)
                    seen.add(tool.name)

        return all_tools

   def route_intent(self, user_query: str) -> List[Any]:
    """Route user query to relevant domain tools using word-boundary matching."""
    query = user_query.lower()
    active_domains = set()

    def matches_keyword(keywords: List[str]) -> bool:
        for word in keywords:
            pattern = rf"\b{re.escape(word)}\b"
            if re.search(pattern, query):
                return True
        return False

    # Loans
    if matches_keyword([
        "loan", "repayment", "disburse", "overdue", "arrear",
        "reject", "waive", "installment", "undo", "reschedule", "template"
    ]):
        active_domains.add("loans")

    # Clients
    if matches_keyword([
        "client", "person", "search", "activate", "mobile", "kyc",
        "identifier", "address", "charge", "fee", "document"
    ]):
        active_domains.add("clients")

    # Groups
    if matches_keyword([
        "group", "center", "centre", "member"
    ]):
        active_domains.add("groups")

    # Savings
    if matches_keyword([
        "saving", "deposit", "withdraw", "balance", "wallet", "interest"
    ]):
        active_domains.add("savings")

    # Staff
    if matches_keyword([
        "staff", "officer", "employee", "office", "branch"
    ]):
        active_domains.add("staff")

    # Accounting
    if matches_keyword([
        "journal", "ledger", "account", "debit", "credit", "accounting"
    ]):
        active_domains.add("accounting")

    # Reports
    if matches_keyword([
        "report", "generate", "sql"
    ]):
        active_domains.add("reports")

    # Products
    if matches_keyword([
        "product"
    ]):
        active_domains.add("products")

    # Charges
    if matches_keyword([
        "charge", "penalty", "fee"
    ]):
        active_domains.add("charges")

    # Code tables
    if matches_keyword([
        "code", "datatable", "dropdown"
    ]):
        active_domains.add("codetables")

    # Default fallback
    if not active_domains:
        active_domains.add("clients")

    # Deduplicate tools
    seen = set()
    result = []

    for domain in active_domains:
        for tool in self.domain_map[domain]:
            if tool.name not in seen:
                result.append(tool)
                seen.add(tool.name)

    return result

    def get_suggestions(self, intent: str, data: Any) -> List[str]:
        return generate_suggestions(intent, data)


# Global router instance
router = DomainRegistry()