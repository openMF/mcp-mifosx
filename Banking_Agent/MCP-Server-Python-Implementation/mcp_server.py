# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from fastmcp import FastMCP
import logging

# 1. Provide an MCP-specific Logger
logging.basicConfig(level=logging.INFO, format='%(asctime)s - %(levelname)s - [MCP Server] %(message)s')
logger = logging.getLogger(__name__)

# 2. Import all the available domain functions from the existing codebase
from tools.domains.clients import (
    search_clients_by_name, get_client_details, get_client_accounts, 
    create_client, activate_client, update_client_mobile, 
    close_client, create_group, get_group_details
)
from tools.domains.loans import (
    get_loan_details, get_repayment_schedule, get_loan_history, get_overdue_loans,
    create_loan, create_group_loan,
    approve_and_disburse_loan, reject_loan_application, 
    make_loan_repayment, apply_late_fee, waive_interest
)
from tools.domains.savings import (
    get_savings_account, get_savings_transactions, create_savings_account, 
    approve_and_activate_savings, close_savings_account, deposit_savings, 
    withdraw_savings, apply_savings_charge, calculate_and_post_interest
)

# 3. Initialize the FastMCP Server
mcp = FastMCP("Mifos-Banking-Agent")

# 4. Register all 26 existing LangChain tools as MCP-native tools using decorators
# -------------------------------------------------------------------------
# FastMCP reads python type hints and docstrings from these wrappers to 
# generate the JSON Schema automatically.
# -------------------------------------------------------------------------

# -> CLIENTS & GROUPS
@mcp.tool()
def search_clients(nameQuery: str) -> dict:
    """Find the client ID for a given name. Returns clientId — use this for all subsequent client and loan tool calls."""
    result = search_clients_by_name.func(nameQuery)
    clients = result if isinstance(result, list) else result.get("pageItems", [])
    return {
        "clients": [
            {
                "clientId":    c.get("entityId") or c.get("id"),   # USE THIS in all follow-up calls
                "displayName": c.get("entityName") or c.get("displayName"),
                "accountNo":   c.get("entityAccountNo") or c.get("accountNo"),
                "status":      c.get("entityStatus", {}).get("value") if isinstance(c.get("entityStatus"), dict) else c.get("entityStatus"),
            }
            for c in clients
        ]
    }

@mcp.tool()
def get_client(clientId: int) -> dict:
    """Show details for a specific client ID"""
    return get_client_details.func(clientId)

@mcp.tool()
def get_client_accts(clientId: int = None, clientIds: list = None, id: int = None) -> dict:
    """Show all loans and savings accounts for a client. IMPORTANT: use the returned 'loanId' (not 'accountNo') when calling loan tools."""
    # Accept any variation the LLM might send
    actual_id = clientId or id or (clientIds[0] if clientIds else None)
    if not actual_id:
        return {"error": "No client ID provided"}
    result = get_client_accounts.func(int(actual_id))
    if not isinstance(result, dict):
        return result
    loans = result.get("loanAccounts", [])
    savings = result.get("savingsAccounts", [])
    return {
        "loanAccounts": [
            {
                "loanId":    l.get("id"),
                "accountNo": l.get("accountNo"),
                "status":    l.get("status", {}).get("value"),
                "balance":   l.get("loanBalance"),
            }
            for l in loans
        ],
        "savingsAccounts": [
            {
                "savingsId": s.get("id"),
                "accountNo": s.get("accountNo"),
                "status":    s.get("status", {}).get("value"),
                "balance":   s.get("accountBalance"),
            }
            for s in savings
        ],
    }

@mcp.tool()
def create_new_client(firstname: str, lastname: str, mobileNo: str = None, officeId: int = 1, isActive: bool = True) -> dict:
    """Create a new banking client"""
    return create_client.func(firstname, lastname, mobileNo, officeId, isActive)

@mcp.tool()
def activate_pending_client(clientId: int) -> dict:
    """Activate a pending client profile"""
    return activate_client.func(clientId)

@mcp.tool()
def update_mobile(clientId: int, newMobileNo: str) -> dict:
    """Update a client's phone number"""
    return update_client_mobile.func(clientId, newMobileNo)

@mcp.tool()
def close_client_profile(clientId: int, closureReasonId: int = 17) -> dict:
    """Close a client's profile"""
    return close_client.func(clientId, closureReasonId)

@mcp.tool()
def create_lending_group(name: str, officeId: int = None, clientMembers: list = None) -> dict:
    """Create a new lending group. clientMembers should be a flat list of integer client IDs e.g. [8, 9]"""
    office = int(officeId) if officeId and str(officeId) not in ("<nil>", "nil", "null", "") else 1
    # Normalize clientMembers: accept [8, 9] or [{"clientId": 8}, {"clientId": 9}]
    members = None
    if clientMembers:
        members = []
        for m in clientMembers:
            if isinstance(m, dict):
                members.append(int(m.get("clientId") or m.get("id") or 0))
            else:
                members.append(int(m))
        members = [m for m in members if m > 0]  # filter out zeros
    return create_group.func(name, office, members)

@mcp.tool()
def get_group(groupId: int) -> dict:
    """Show details and members of a lending group"""
    return get_group_details.func(groupId)

# -> LOANS
@mcp.tool()
def get_loan(loanId: int) -> dict:
    """Get details of a specific loan"""
    return get_loan_details.func(loanId)

@mcp.tool()
def get_repayment_sched(loanId: int) -> dict:
    """Get the repayment schedule for a loan"""
    return get_repayment_schedule.func(loanId)

@mcp.tool()
def get_loan_hist(loanId: int) -> dict:
    """Get the full transaction history for a loan including repayments, disbursements, charges, and outstanding balance — useful for context memory and recreating previous loan terms"""
    return get_loan_history.func(loanId)

@mcp.tool()
def create_new_loan(clientId: int, principal: float, months: int, productId: int = 1) -> dict:
    """Create a new loan application"""
    return create_loan.func(clientId, principal, months, productId)

@mcp.tool()
def approve_disburse_loan(loanId: int, amount: float = None) -> dict:
    """Approve and disburse a pending loan"""
    return approve_and_disburse_loan.func(loanId, amount)

@mcp.tool()
def reject_loan(loanId: int, note: str = "Rejected via AI Agent due to risk profile") -> dict:
    """Reject a pending loan application"""
    return reject_loan_application.func(loanId, note)

@mcp.tool()
def make_repayment(loanId: int, amount: float) -> dict:
    """Make a repayment on an active loan"""
    return make_loan_repayment.func(loanId, amount)

@mcp.tool()
def apply_fee(loanId: int, feeAmount: float) -> dict:
    """Apply a fee to a loan"""
    return apply_late_fee.func(loanId, feeAmount, 2)  # charge_id=2 (Flat Service Fee)

@mcp.tool()
def waive_loan_interest(loanId: int, amount: float, note: str = "AI Authorized Waiver") -> dict:
    """Waive interest on a loan"""
    return waive_interest.func(loanId, amount, note)

@mcp.tool()
def get_overdue_loans_for_client(clientId: int) -> dict:
    """Get all overdue or in-arrears loans for a client"""
    return get_overdue_loans.func(clientId)

@mcp.tool()
def create_group_loan_app(groupId: int, principal: float, months: int, productId: int = 1) -> dict:
    """Create a group loan application for an existing lending group"""
    return create_group_loan.func(groupId, principal, months, productId)

# -> SAVINGS
@mcp.tool()
def get_savings(accountId: int) -> dict:
    """Get details of a savings account"""
    return get_savings_account.func(accountId)

@mcp.tool()
def get_savings_txns(accountId: int) -> dict:
    """Get transactions for a savings account"""
    return get_savings_transactions.func(accountId)

@mcp.tool()
def create_savings(clientId: int, productId: int = 1) -> dict:
    """Create a new savings account"""
    return create_savings_account.func(clientId, productId)

@mcp.tool()
def approve_activate_savings(accountId: int) -> dict:
    """Approve and activate a savings account"""
    return approve_and_activate_savings.func(accountId)

@mcp.tool()
def close_savings(accountId: int) -> dict:
    """Close a savings account"""
    return close_savings_account.func(accountId)

@mcp.tool()
def deposit(accountId: int, amount: float) -> dict:
    """Deposit money into a savings account"""
    return deposit_savings.func(accountId, amount)

@mcp.tool()
def withdraw(accountId: int, amount: float) -> dict:
    """Withdraw money from a savings account"""
    return withdraw_savings.func(accountId, amount)

@mcp.tool()
def apply_savings_fee(accountId: int, amount: float, chargeId: int = 1) -> dict:
    """Apply a charge/block to a savings account"""
    return apply_savings_charge.func(accountId, amount, chargeId)

@mcp.tool()
def calc_post_interest(accountId: int) -> dict:
    """Calculate and post interest to a savings account"""
    return calculate_and_post_interest.func(accountId)


# 5. START SERVER
if __name__ == "__main__":
    logger.info("Initializing Mifos FastMCP Server on STDIO Transport")
    mcp.run()
