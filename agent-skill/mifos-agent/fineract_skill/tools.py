# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Agent Tool Registry
====================

Provides a **framework-agnostic** registry of all Fineract tools that
Pi Agent, Hermes Agent, and Prime-Agent can discover and invoke.

Each tool is described by a :class:`ToolSpec` with:
* ``name`` — unique tool identifier
* ``description`` — natural-language description (used as the LLM prompt)
* ``parameters`` — JSON-Schema-style parameter definitions
* ``domain`` — logical grouping (``clients``, ``loans``, …)
* ``handler`` — the callable that executes the tool

Usage::

    from fineract_skill import list_tools, get_tool, FineractClient

    # Discover tools
    for tool in list_tools():
        print(tool.name, tool.description)

    # Execute a tool
    client = FineractClient()
    tool = get_tool("search_clients")
    result = tool.handler(client, name_query="John")
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Callable

# ── Import all domain handlers ─────────────────────────────────────────
from fineract_skill.domains import (
    accounting,
    charges,
    clients,
    groups,
    loans,
    products,
    reports,
    savings,
    staff,
)


@dataclass(frozen=True)
class ToolParam:
    """Describes a single parameter for a tool."""

    name: str
    type: str  # "string", "integer", "number", "boolean", "array", "object"
    description: str
    required: bool = True
    default: Any = None


@dataclass(frozen=True)
class ToolSpec:
    """A discoverable tool definition.

    This is the contract that agent frameworks consume.  Each spec
    contains enough metadata for an LLM to choose and invoke the tool.
    """

    name: str
    description: str
    domain: str
    handler: Callable[..., dict[str, Any]]
    parameters: list[ToolParam] = field(default_factory=list)
    read_only: bool = False
    destructive: bool = False
    require_opt_in: bool = False

    def to_openai_schema(self) -> dict[str, Any]:
        """Export as an OpenAI function-calling compatible schema."""
        properties: dict[str, Any] = {}
        required: list[str] = []
        for p in self.parameters:
            prop: dict[str, Any] = {"type": p.type, "description": p.description}
            if p.default is not None:
                prop["default"] = p.default
            properties[p.name] = prop
            if p.required:
                required.append(p.name)

        return {
            "type": "function",
            "function": {
                "name": self.name,
                "description": self.description,
                "parameters": {
                    "type": "object",
                    "properties": properties,
                    "required": required,
                },
            },
        }

    def to_mcp_schema(self) -> dict[str, Any]:
        """Export as an MCP-compatible tool definition."""
        input_schema: dict[str, Any] = {
            "type": "object",
            "properties": {},
            "required": [],
        }
        for p in self.parameters:
            prop: dict[str, Any] = {"type": p.type, "description": p.description}
            if p.default is not None:
                prop["default"] = p.default
            input_schema["properties"][p.name] = prop
            if p.required:
                input_schema["required"].append(p.name)

        schema: dict[str, Any] = {
            "name": self.name,
            "description": self.description,
            "inputSchema": input_schema,
        }

        schema["annotations"] = {
            "readOnlyHint": self.read_only,
            "destructiveHint": self.destructive,
        }

        return schema


# ══════════════════════════════════════════════════════════════════════
#  TOOL DEFINITIONS
# ══════════════════════════════════════════════════════════════════════

TOOL_REGISTRY: list[ToolSpec] = [
    # ── Clients ────────────────────────────────────────────────────────
    ToolSpec(
        name="search_clients",
        description="Search for clients by name. Returns matching client IDs and display names.",
        domain="clients",
        handler=clients.search_clients,
        parameters=[
            ToolParam("name_query", "string", "Full or partial client name to search for."),
        ],
    ),
    ToolSpec(
        name="get_client_details",
        description="Get detailed information for a specific client by their ID.",
        domain="clients",
        handler=clients.get_client_details,
        parameters=[
            ToolParam("client_id", "integer", "The Fineract client ID."),
        ],
    ),
    ToolSpec(
        name="get_client_accounts",
        description="Show all loans and savings accounts for a client.",
        domain="clients",
        handler=clients.get_client_accounts,
        parameters=[
            ToolParam("client_id", "integer", "The Fineract client ID."),
        ],
    ),
    ToolSpec(
        name="create_client",
        description="Create a new banking client.",
        domain="clients",
        handler=clients.create_client,
        parameters=[
            ToolParam("firstname", "string", "Client's first name."),
            ToolParam("lastname", "string", "Client's last name."),
            ToolParam("mobile_no", "string", "Phone number.", required=False),
            ToolParam("office_id", "integer", "Office/branch ID.", required=False, default=1),
            ToolParam("active", "boolean", "Activate immediately.", required=False, default=True),
        ],
    ),
    ToolSpec(
        name="activate_client",
        description="Activate a pending client profile.",
        domain="clients",
        handler=clients.activate_client,
        parameters=[
            ToolParam("client_id", "integer", "Client ID to activate."),
        ],
    ),
    ToolSpec(
        name="update_client",
        description="Update an existing client's details (firstname, lastname, mobile, externalId).",
        domain="clients",
        handler=clients.update_client,
        parameters=[
            ToolParam("client_id", "integer", "Client ID to update."),
            ToolParam("firstname", "string", "New first name.", required=False),
            ToolParam("lastname", "string", "New last name.", required=False),
            ToolParam("mobile_no", "string", "New phone number.", required=False),
            ToolParam("external_id", "string", "New external ID.", required=False),
        ],
    ),
    ToolSpec(
        name="close_client",
        description="Close a client profile.",
        domain="clients",
        handler=clients.close_client,
        parameters=[
            ToolParam("client_id", "integer", "Client ID to close."),
            ToolParam("closure_reason_id", "integer", "Closure reason code.", required=False, default=17),
        ],
    ),
    ToolSpec(
        name="delete_client",
        description="Delete a pending/closed client profile.",
        domain="clients",
        handler=clients.delete_client,
        parameters=[
            ToolParam("client_id", "integer", "Client ID to delete."),
        ],
        destructive=True,
    ),
    ToolSpec(
        name="get_client_identifiers",
        description="List identity documents (passport, SSN, etc.) for a client.",
        domain="clients",
        handler=clients.get_client_identifiers,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
        ],
    ),
    ToolSpec(
        name="create_client_identifier",
        description="Add a new identity document to a client's profile.",
        domain="clients",
        handler=clients.create_client_identifier,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
            ToolParam("document_type_id", "integer", "Document type (from code-table)."),
            ToolParam("document_key", "string", "Document number/key."),
        ],
    ),
    ToolSpec(
        name="get_client_documents",
        description="List all uploaded files/documents for a client.",
        domain="clients",
        handler=clients.get_client_documents,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
        ],
    ),
    ToolSpec(
        name="get_client_charges",
        description="List client-level fees and penalties.",
        domain="clients",
        handler=clients.get_client_charges,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
        ],
    ),
    ToolSpec(
        name="apply_client_charge",
        description="Apply a one-time charge/fee to a client profile.",
        domain="clients",
        handler=clients.apply_client_charge,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
            ToolParam("charge_id", "integer", "Charge definition ID."),
            ToolParam("amount", "number", "Charge amount."),
        ],
    ),
    ToolSpec(
        name="get_client_transactions",
        description="List all financial transactions linked to a client.",
        domain="clients",
        handler=clients.get_client_transactions,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
        ],
    ),
    ToolSpec(
        name="get_client_addresses",
        description="Get addresses registered for a client.",
        domain="clients",
        handler=clients.get_client_addresses,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
        ],
    ),

    # ── Loans ──────────────────────────────────────────────────────────
    ToolSpec(
        name="get_loan_details",
        description="Get key details of a specific loan (status, principal, balance, schedule, timeline).",
        domain="loans",
        handler=loans.get_loan_details,
        parameters=[
            ToolParam("loan_id", "integer", "Fineract loan ID."),
        ],
    ),
    ToolSpec(
        name="get_repayment_schedule",
        description="Get the repayment schedule for a loan.",
        domain="loans",
        handler=loans.get_repayment_schedule,
        parameters=[
            ToolParam("loan_id", "integer", "Fineract loan ID."),
        ],
    ),
    ToolSpec(
        name="get_loan_transactions",
        description="Get the transaction history for a loan (repayments, disbursements, charges).",
        domain="loans",
        handler=loans.get_loan_transactions,
        parameters=[
            ToolParam("loan_id", "integer", "Fineract loan ID."),
        ],
    ),
    ToolSpec(
        name="get_loan_template",
        description="Get the pre-filled loan application template for a client. Use before creating a loan.",
        domain="loans",
        handler=loans.get_loan_template,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
            ToolParam("product_id", "integer", "Loan product ID.", required=False),
        ],
    ),
    ToolSpec(
        name="create_loan",
        description="Create a new individual loan application.",
        domain="loans",
        handler=loans.create_loan,
        parameters=[
            ToolParam("client_id", "integer", "Client to receive the loan."),
            ToolParam("principal", "number", "Loan principal amount."),
            ToolParam("months", "integer", "Repayment term in months."),
            ToolParam("product_id", "integer", "Loan product ID.", required=False, default=1),
        ],
    ),
    ToolSpec(
        name="create_group_loan",
        description="Create a group loan application for an existing lending group.",
        domain="loans",
        handler=loans.create_group_loan,
        parameters=[
            ToolParam("group_id", "integer", "Group to receive the loan."),
            ToolParam("principal", "number", "Loan principal amount."),
            ToolParam("months", "integer", "Repayment term in months."),
            ToolParam("product_id", "integer", "Loan product ID.", required=False, default=1),
        ],
    ),
    ToolSpec(
        name="approve_and_disburse_loan",
        description="Approve and disburse a pending loan in a single action.",
        domain="loans",
        handler=loans.approve_and_disburse_loan,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID to approve and disburse."),
            ToolParam("amount", "number", "Disbursement amount (uses principal if omitted).", required=False),
        ],
    ),
    ToolSpec(
        name="reject_loan",
        description="Reject a pending loan application.",
        domain="loans",
        handler=loans.reject_loan,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID to reject."),
            ToolParam("note", "string", "Rejection reason.", required=False, default="Rejected via Agent"),
        ],
    ),
    ToolSpec(
        name="make_repayment",
        description="Make a repayment on an active loan.",
        domain="loans",
        handler=loans.make_repayment,
        parameters=[
            ToolParam("loan_id", "integer", "Active loan ID."),
            ToolParam("amount", "number", "Repayment amount."),
        ],
    ),
    ToolSpec(
        name="waive_interest",
        description="Waive interest on a loan.",
        domain="loans",
        handler=loans.waive_interest,
        parameters=[
            ToolParam("loan_id", "integer", "Active loan ID."),
            ToolParam("amount", "number", "Amount of interest to waive."),
            ToolParam("note", "string", "Reason for the waiver.", required=False, default="Waived via Agent"),
        ],
    ),
    ToolSpec(
        name="undo_loan_approval",
        description="Undo a loan approval (returns loan to pending status).",
        domain="loans",
        handler=loans.undo_loan_approval,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID."),
        ],
    ),
    ToolSpec(
        name="undo_loan_disbursal",
        description="Undo a loan disbursal (returns loan to approved status).",
        domain="loans",
        handler=loans.undo_loan_disbursal,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID."),
        ],
    ),
    ToolSpec(
        name="update_loan",
        description="Update a draft/submitted loan application (principal, term, product).",
        domain="loans",
        handler=loans.update_loan,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID to update."),
            ToolParam("principal", "number", "New principal.", required=False),
            ToolParam("months", "integer", "New term in months.", required=False),
            ToolParam("product_id", "integer", "New product ID.", required=False),
        ],
    ),
    ToolSpec(
        name="delete_loan",
        description="Delete a draft/submitted loan application.",
        domain="loans",
        handler=loans.delete_loan,
        parameters=[
            ToolParam("loan_id", "integer", "Loan ID to delete."),
        ],
        destructive=True,
    ),
    ToolSpec(
        name="reschedule_loan",
        description="Submit a loan reschedule request for modified repayment terms.",
        domain="loans",
        handler=loans.reschedule_loan,
        parameters=[
            ToolParam("loan_id", "integer", "Active loan ID."),
            ToolParam("reschedule_from_date", "string", "Date to reschedule from (dd MMMM yyyy)."),
            ToolParam("reschedule_reason_id", "integer", "Reason ID for rescheduling."),
            ToolParam("adjusted_due_date", "string", "New due date.", required=False),
            ToolParam("new_interest_rate", "number", "New interest rate.", required=False),
            ToolParam("grace_on_principal", "integer", "Grace periods on principal.", required=False),
            ToolParam("extra_terms", "integer", "Additional repayment periods.", required=False),
            ToolParam("reason", "string", "Reschedule reason.", required=False, default="Rescheduled via Agent"),
        ],
    ),

    # ── Savings ────────────────────────────────────────────────────────
    ToolSpec(
        name="get_savings_account",
        description="Get key details of a savings account.",
        domain="savings",
        handler=savings.get_savings_account,
        parameters=[
            ToolParam("account_id", "integer", "Savings account ID."),
        ],
    ),
    ToolSpec(
        name="get_savings_transactions",
        description="Get transaction history for a savings account.",
        domain="savings",
        handler=savings.get_savings_transactions,
        parameters=[
            ToolParam("account_id", "integer", "Savings account ID."),
        ],
    ),
    ToolSpec(
        name="create_savings_account",
        description="Create a new savings account for a client.",
        domain="savings",
        handler=savings.create_savings_account,
        parameters=[
            ToolParam("client_id", "integer", "Client ID."),
            ToolParam("product_id", "integer", "Savings product ID.", required=False, default=1),
        ],
    ),
    ToolSpec(
        name="approve_and_activate_savings",
        description="Approve and activate a savings account in a single action.",
        domain="savings",
        handler=savings.approve_and_activate_savings,
        parameters=[
            ToolParam("account_id", "integer", "Savings account ID."),
        ],
    ),
    ToolSpec(
        name="close_savings_account",
        description="Close an active savings account.",
        domain="savings",
        handler=savings.close_savings_account,
        parameters=[
            ToolParam("account_id", "integer", "Savings account ID."),
        ],
    ),
    ToolSpec(
        name="deposit",
        description="Deposit money into an active savings account.",
        domain="savings",
        handler=savings.deposit,
        parameters=[
            ToolParam("account_id", "integer", "Active savings account ID."),
            ToolParam("amount", "number", "Amount to deposit."),
        ],
    ),
    ToolSpec(
        name="withdraw",
        description="Withdraw money from an active savings account.",
        domain="savings",
        handler=savings.withdraw,
        parameters=[
            ToolParam("account_id", "integer", "Active savings account ID."),
            ToolParam("amount", "number", "Amount to withdraw."),
        ],
        destructive=True,
    ),
    ToolSpec(
        name="apply_savings_charge",
        description="Apply a charge/fee to a savings account.",
        domain="savings",
        handler=savings.apply_savings_charge,
        parameters=[
            ToolParam("account_id", "integer", "Savings account ID."),
            ToolParam("amount", "number", "Charge amount."),
            ToolParam("charge_id", "integer", "Charge definition ID.", required=False, default=1),
        ],
    ),
    ToolSpec(
        name="calculate_and_post_interest",
        description="Calculate and post accrued interest to a savings account.",
        domain="savings",
        handler=savings.calculate_and_post_interest,
        parameters=[
            ToolParam("account_id", "integer", "Active savings account ID."),
        ],
    ),

    # ── Groups & Centers ──────────────────────────────────────────────
    ToolSpec(
        name="create_group",
        description="Create a new lending group.",
        domain="groups",
        handler=groups.create_group,
        parameters=[
            ToolParam("name", "string", "Group name."),
            ToolParam("office_id", "integer", "Office/branch ID.", required=False, default=1),
            ToolParam("external_id", "string", "External reference.", required=False),
        ],
    ),
    ToolSpec(
        name="get_group",
        description="Show details and members of a lending group.",
        domain="groups",
        handler=groups.get_group,
        parameters=[
            ToolParam("group_id", "integer", "Group ID."),
        ],
    ),
    ToolSpec(
        name="list_groups",
        description="List all lending groups, optionally filtered by office.",
        domain="groups",
        handler=groups.list_groups,
        parameters=[
            ToolParam("office_id", "integer", "Filter by office.", required=False),
        ],
    ),
    ToolSpec(
        name="activate_group",
        description="Activate a pending group.",
        domain="groups",
        handler=groups.activate_group,
        parameters=[
            ToolParam("group_id", "integer", "Group ID to activate."),
        ],
    ),
    ToolSpec(
        name="add_group_member",
        description="Add a client to a lending group.",
        domain="groups",
        handler=groups.add_group_member,
        parameters=[
            ToolParam("group_id", "integer", "Target group."),
            ToolParam("client_id", "integer", "Client to add."),
        ],
    ),
    ToolSpec(
        name="list_centers",
        description="List all centers, optionally filtered by office.",
        domain="groups",
        handler=groups.list_centers,
        parameters=[
            ToolParam("office_id", "integer", "Filter by office.", required=False),
        ],
    ),
    ToolSpec(
        name="get_center",
        description="Get details for a center.",
        domain="groups",
        handler=groups.get_center,
        parameters=[
            ToolParam("center_id", "integer", "Center ID."),
        ],
    ),
    ToolSpec(
        name="create_center",
        description="Create a new center.",
        domain="groups",
        handler=groups.create_center,
        parameters=[
            ToolParam("name", "string", "Center name."),
            ToolParam("office_id", "integer", "Office/branch ID."),
            ToolParam("external_id", "string", "External reference.", required=False),
        ],
    ),

    # ── Accounting ─────────────────────────────────────────────────────
    ToolSpec(
        name="list_gl_accounts",
        description="List GL accounts (Chart of Accounts). Types: 1=Asset, 2=Liability, 3=Equity, 4=Income, 5=Expense.",
        domain="accounting",
        handler=accounting.list_gl_accounts,
        parameters=[
            ToolParam("account_type", "integer", "Account type filter (1-5).", required=False),
        ],
    ),
    ToolSpec(
        name="get_journal_entries",
        description="List journal entries, optionally filtered by GL account or transaction.",
        domain="accounting",
        handler=accounting.get_journal_entries,
        parameters=[
            ToolParam("gl_account_id", "integer", "GL account ID filter.", required=False),
            ToolParam("transaction_id", "string", "Transaction ID filter.", required=False),
        ],
    ),
    ToolSpec(
        name="create_journal_entry",
        description="Record a manual journal entry with debit and credit lines.",
        domain="accounting",
        handler=accounting.create_journal_entry,
        parameters=[
            ToolParam("office_id", "integer", "Office/branch ID."),
            ToolParam("date", "string", "Transaction date (dd MMMM yyyy)."),
            ToolParam("credits", "array", "Credit entries [{glAccountId, amount}]."),
            ToolParam("debits", "array", "Debit entries [{glAccountId, amount}]."),
            ToolParam("comment", "string", "Optional narrative.", required=False, default=""),
        ],
    ),

    # ── Staff & Offices ────────────────────────────────────────────────
    ToolSpec(
        name="list_staff",
        description="List bank staff members, optionally filtered by office and status.",
        domain="staff",
        handler=staff.list_staff,
        parameters=[
            ToolParam("office_id", "integer", "Filter by office.", required=False),
            ToolParam("status", "string", "Filter: all | active | inactive.", required=False, default="all"),
        ],
    ),
    ToolSpec(
        name="get_staff_details",
        description="Get details for a staff member.",
        domain="staff",
        handler=staff.get_staff_details,
        parameters=[
            ToolParam("staff_id", "integer", "Staff member ID."),
        ],
    ),
    ToolSpec(
        name="list_offices",
        description="List all bank offices / branches.",
        domain="staff",
        handler=staff.list_offices,
        parameters=[],
    ),
    ToolSpec(
        name="get_office_details",
        description="Get details for a specific office.",
        domain="staff",
        handler=staff.get_office_details,
        parameters=[
            ToolParam("office_id", "integer", "Office ID."),
        ],
    ),

    # ── Products ───────────────────────────────────────────────────────
    ToolSpec(
        name="list_loan_products",
        description="List all loan products with principal ranges, interest rates, and terms.",
        domain="products",
        handler=products.list_loan_products,
        parameters=[],
    ),
    ToolSpec(
        name="get_loan_product",
        description="Get full details for a loan product including charges and amortization.",
        domain="products",
        handler=products.get_loan_product,
        parameters=[
            ToolParam("product_id", "integer", "Loan product ID."),
        ],
    ),
    ToolSpec(
        name="list_savings_products",
        description="List all savings products with interest rates and minimums.",
        domain="products",
        handler=products.list_savings_products,
        parameters=[],
    ),
    ToolSpec(
        name="get_savings_product",
        description="Get full details for a savings product.",
        domain="products",
        handler=products.get_savings_product,
        parameters=[
            ToolParam("product_id", "integer", "Savings product ID."),
        ],
    ),

    # ── Charges ────────────────────────────────────────────────────────
    ToolSpec(
        name="list_charges",
        description="List all available charge definitions (fees and penalties).",
        domain="charges",
        handler=charges.list_charges,
        parameters=[],
    ),
    ToolSpec(
        name="get_charge",
        description="Get details of a specific charge definition.",
        domain="charges",
        handler=charges.get_charge,
        parameters=[
            ToolParam("charge_id", "integer", "Charge definition ID."),
        ],
    ),
    ToolSpec(
        name="create_charge",
        description="Create a new charge definition (fee or penalty).",
        domain="charges",
        handler=charges.create_charge,
        parameters=[
            ToolParam("name", "string", "Charge name."),
            ToolParam("amount", "number", "Default amount."),
            ToolParam("currency_code", "string", "ISO currency code.", required=False, default="USD"),
            ToolParam("charge_applies_to", "integer", "1=Loan, 2=Savings, 3=Client.", required=False, default=1),
            ToolParam("charge_time_type", "integer", "When the charge is applied."),
            ToolParam("charge_calculation_type", "integer", "1=Flat, 2=% of Amount.", required=False, default=1),
            ToolParam("is_penalty", "boolean", "Whether this is a penalty.", required=False, default=False),
            ToolParam("is_active", "boolean", "Whether this charge is active.", required=False, default=True),
        ],
    ),
    ToolSpec(
        name="update_charge",
        description="Update an existing charge definition.",
        domain="charges",
        handler=charges.update_charge,
        parameters=[
            ToolParam("charge_id", "integer", "Charge definition ID."),
            ToolParam("name", "string", "New name.", required=False),
            ToolParam("amount", "number", "New amount.", required=False),
            ToolParam("is_active", "boolean", "New active state.", required=False),
        ],
    ),

    # ── Reports ────────────────────────────────────────────────────────
    ToolSpec(
        name="list_reports",
        description="List all Fineract report definitions. Optionally filter by type.",
        domain="reports",
        handler=reports.list_reports,
        parameters=[
            ToolParam("report_type", "string", "Filter: Table | Chart | SMS | Text | Pentaho.", required=False),
        ],
    ),
    ToolSpec(
        name="get_report",
        description="Get the full definition for a specific report.",
        domain="reports",
        handler=reports.get_report,
        parameters=[
            ToolParam("report_id", "integer", "Report definition ID."),
        ],
    ),
    ToolSpec(
        name="run_report",
        description="Run a Fineract report by name and return results.",
        domain="reports",
        handler=reports.run_report,
        parameters=[
            ToolParam("report_name", "string", "Exact report name."),
            ToolParam("params", "object", "Report parameters.", required=False),
        ],
    ),
    ToolSpec(
        name="create_report",
        description="Register a new report definition in Fineract.",
        domain="reports",
        handler=reports.create_report,
        parameters=[
            ToolParam("report_name", "string", "Display name."),
            ToolParam("report_type", "string", "Table | Chart | SMS | Text | Pentaho."),
            ToolParam("report_sql", "string", "The SQL query."),
            ToolParam("description", "string", "Optional description.", required=False, default=""),
        ],
        require_opt_in=True,
    ),
    ToolSpec(
        name="update_report",
        description="Update an existing report definition.",
        domain="reports",
        handler=reports.update_report,
        parameters=[
            ToolParam("report_id", "integer", "Report definition ID."),
            ToolParam("report_name", "string", "New name.", required=False),
            ToolParam("report_type", "string", "New type.", required=False),
            ToolParam("report_sql", "string", "New SQL.", required=False),
            ToolParam("description", "string", "New description.", required=False),
        ],
        require_opt_in=True,
    ),
]


# ── Public API ─────────────────────────────────────────────────────────

_INDEX: dict[str, ToolSpec] = {t.name: t for t in TOOL_REGISTRY}


def list_tools(domain: str | None = None) -> list[ToolSpec]:
    """Return all registered tools, optionally filtered by domain.

    Parameters
    ----------
    domain : str, optional
        Filter by domain name (``"clients"``, ``"loans"``, ``"savings"``, etc.).
    """
    if domain:
        return [t for t in TOOL_REGISTRY if t.domain == domain]
    return list(TOOL_REGISTRY)


def get_tool(name: str) -> ToolSpec:
    """Look up a tool by name.

    Raises
    ------
    KeyError
        If no tool with the given name exists.
    """
    if name not in _INDEX:
        raise KeyError(f"Tool '{name}' not found. Available: {list(_INDEX.keys())}")
    return _INDEX[name]


def get_openai_tools(allow_opt_in: bool = False) -> list[dict[str, Any]]:
    """Export all tools in OpenAI function-calling format."""
    return [t.to_openai_schema() for t in TOOL_REGISTRY if not t.require_opt_in or allow_opt_in]


def get_mcp_tools(allow_opt_in: bool = False) -> list[dict[str, Any]]:
    """Export all tools in MCP-compatible format."""
    return [t.to_mcp_schema() for t in TOOL_REGISTRY if not t.require_opt_in or allow_opt_in]
