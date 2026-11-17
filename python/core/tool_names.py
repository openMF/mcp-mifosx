# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Translate intent-router domain names into the public MCP tool names.

``tools/registry.py`` routes over the domain layer, so its ``FunctionTool``
names are Python function names such as ``search_clients_by_name``.
``profiles.toml`` and ``mcp_server.py`` expose public names such as
``search_clients``. The REST router endpoint must answer in the public
namespace: otherwise a profile filter can never match, and clients are told
about tools that the MCP server does not register under that name.

Keep this map in step with the registry. ``tests/test_api_server_security.py``
fails when a registry tool has no entry here.
"""

from types import MappingProxyType
from typing import Mapping

DOMAIN_TOOL_TO_MCP_TOOL: Mapping[str, str] = MappingProxyType({
    # Clients
    "search_clients_by_name": "search_clients",
    "get_client_details": "get_client",
    "get_client_accounts": "get_client_accts",
    "create_client": "create_new_client",
    "activate_client": "activate_pending_client",
    "update_client_mobile": "update_mobile",
    "close_client": "close_client_profile",
    "get_client_identifiers": "get_identifiers",
    "create_client_identifier": "add_identifier",
    "get_client_documents": "list_documents",
    "validate_capture_quality": "validate_capture_quality",
    "upload_client_document": "upload_client_document",
    "verify_client_details": "verify_client_details",
    "get_client_charges": "list_client_charges",
    "apply_client_charge": "apply_client_fee",
    "get_client_transactions": "list_client_txns",
    "get_client_addresses": "get_addresses",
    # Groups and centers
    "create_group": "create_lending_group",
    "get_group_details": "get_group",
    "list_groups": "list_all_groups",
    "activate_group": "activate_pending_group",
    "add_group_member": "add_member_to_group",
    "list_centers": "list_all_centers",
    "get_center": "get_center",
    "create_center": "create_new_center",
    # Loans
    "get_loan_details": "get_loan",
    "get_repayment_schedule": "get_repayment_sched",
    "get_loan_history": "get_loan_hist",
    "create_loan": "create_new_loan",
    "approve_and_disburse_loan": "approve_disburse_loan",
    "reject_loan_application": "reject_loan",
    "make_loan_repayment": "make_repayment",
    "apply_late_fee": "apply_loan_fee",
    "waive_interest": "waive_loan_interest",
    "get_overdue_loans": "get_overdue_loans_for_client",
    "create_group_loan": "create_group_loan_app",
    "undo_loan_approval": "undo_approval",
    "undo_loan_disbursal": "undo_disbursal",
    "get_loan_template": "get_loan_app_template",
    "reschedule_loan": "reschedule_loan_app",
    "update_loan": "update_existing_loan",
    "delete_loan": "delete_loan_app",
    # Savings
    "get_savings_account": "get_savings",
    "get_savings_transactions": "get_savings_txns",
    "create_savings_account": "create_savings",
    "approve_and_activate_savings": "approve_activate_savings",
    "close_savings_account": "close_savings",
    "deposit_savings": "deposit",
    "withdraw_savings": "withdraw",
    "apply_savings_charge": "apply_savings_fee",
    "calculate_and_post_interest": "calc_post_interest",
    # Staff and offices
    "list_staff": "list_all_staff",
    "get_staff_details": "get_staff",
    "list_offices": "list_all_offices",
    "get_office_details": "get_office",
    # Accounting
    "list_gl_accounts": "list_accounts",
    "get_journal_entries": "list_journal_entries",
    "create_journal_entry": "record_journal_entry",
    # Reports
    "list_reports": "list_all_reports",
    "get_report": "get_report_definition",
    "run_report": "run_fineract_report",
    "create_report": "create_report_definition",
    "update_report": "update_report_definition",
    # Products
    "list_loan_products": "list_available_loan_products",
    "get_loan_product": "get_loan_product_details",
    "list_savings_products": "list_available_savings_products",
    "get_savings_product": "get_savings_product_details",
    # Charges
    "list_charges": "list_all_charges",
    "get_charge": "get_charge",
    "create_charge": "create_new_charge",
    "update_charge": "update_existing_charge",
    # Code tables
    "list_codes": "list_system_codes",
    "get_code_values": "get_code_values",
    "list_datatables": "list_all_datatables",
})
