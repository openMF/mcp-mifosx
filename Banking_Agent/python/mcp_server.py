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
    get_loan_details, get_repayment_schedule, create_loan, 
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
def search_clients(name_query: str) -> dict:
    """Find the client ID for a given name"""
    clients = search_clients_by_name.func(name_query)
    return {"clients": clients} if isinstance(clients, list) else clients

@mcp.tool()
def get_client(client_id: int) -> dict:
    """Show details for a specific client ID"""
    return get_client_details.func(client_id)

@mcp.tool()
def get_client_accts(client_id: int) -> dict:
    """Show all loans and savings accounts for a client"""
    return get_client_accounts.func(client_id)

@mcp.tool()
def create_new_client(firstname: str, lastname: str, mobile_no: str = None, office_id: int = 1, is_active: bool = True) -> dict:
    """Create a new banking client"""
    return create_client.func(firstname, lastname, mobile_no, office_id, is_active)

@mcp.tool()
def activate_pending_client(client_id: int) -> dict:
    """Activate a pending client profile"""
    return activate_client.func(client_id)

@mcp.tool()
def update_mobile(client_id: int, new_mobile_no: str) -> dict:
    """Update a client's phone number"""
    return update_client_mobile.func(client_id, new_mobile_no)

@mcp.tool()
def close_client_profile(client_id: int, closure_reason_id: int = 17) -> dict:
    """Close a client's profile"""
    return close_client.func(client_id, closure_reason_id)

@mcp.tool()
def create_lending_group(name: str, office_id: int = 1, client_members: list[int] = None) -> dict:
    """Create a new lending group"""
    return create_group.func(name, office_id, client_members)

@mcp.tool()
def get_group(group_id: int) -> dict:
    """Show details and members of a lending group"""
    return get_group_details.func(group_id)

# -> LOANS
@mcp.tool()
def get_loan(loan_id: int) -> dict:
    """Get details of a specific loan"""
    return get_loan_details.func(loan_id)

@mcp.tool()
def get_repayment_sched(loan_id: int) -> dict:
    """Get the repayment schedule for a loan"""
    return get_repayment_schedule.func(loan_id)

@mcp.tool()
def create_new_loan(client_id: int, principal: float, months: int, product_id: int = 1) -> dict:
    """Create a new loan application"""
    return create_loan.func(client_id, principal, months, product_id)

@mcp.tool()
def approve_disburse_loan(loan_id: int, amount: float = None) -> dict:
    """Approve and disburse a pending loan"""
    return approve_and_disburse_loan.func(loan_id, amount)

@mcp.tool()
def reject_loan(loan_id: int, note: str = "Rejected via AI Agent due to risk profile") -> dict:
    """Reject a pending loan application"""
    return reject_loan_application.func(loan_id, note)

@mcp.tool()
def make_repayment(loan_id: int, amount: float) -> dict:
    """Make a repayment on an active loan"""
    return make_loan_repayment.func(loan_id, amount)

@mcp.tool()
def apply_fee(loan_id: int, fee_amount: float) -> dict:
    """Apply a fee to a loan"""
    return apply_late_fee.func(loan_id, fee_amount, 2)  # charge_id=2 (Flat Service Fee)

@mcp.tool()
def waive_loan_interest(loan_id: int, amount: float, note: str = "AI Authorized Waiver") -> dict:
    """Waive interest on a loan"""
    return waive_interest.func(loan_id, amount, note)

# -> SAVINGS
@mcp.tool()
def get_savings(account_id: int) -> dict:
    """Get details of a savings account"""
    return get_savings_account.func(account_id)

@mcp.tool()
def get_savings_txns(account_id: int) -> dict:
    """Get transactions for a savings account"""
    return get_savings_transactions.func(account_id)

@mcp.tool()
def create_savings(client_id: int, product_id: int = 1) -> dict:
    """Create a new savings account"""
    return create_savings_account.func(client_id, product_id)

@mcp.tool()
def approve_activate_savings(account_id: int) -> dict:
    """Approve and activate a savings account"""
    return approve_and_activate_savings.func(account_id)

@mcp.tool()
def close_savings(account_id: int) -> dict:
    """Close a savings account"""
    return close_savings_account.func(account_id)

@mcp.tool()
def deposit(account_id: int, amount: float) -> dict:
    """Deposit money into a savings account"""
    return deposit_savings.func(account_id, amount)

@mcp.tool()
def withdraw(account_id: int, amount: float) -> dict:
    """Withdraw money from a savings account"""
    return withdraw_savings.func(account_id, amount)

@mcp.tool()
def apply_savings_fee(account_id: int, amount: float, charge_id: int = 1) -> dict:
    """Apply a charge/block to a savings account"""
    return apply_savings_charge.func(account_id, amount, charge_id)

@mcp.tool()
def calc_post_interest(account_id: int) -> dict:
    """Calculate and post interest to a savings account"""
    return calculate_and_post_interest.func(account_id)


# 5. START SERVER
if __name__ == "__main__":
    logger.info("Initializing Mifos FastMCP Server on STDIO Transport")
    mcp.run()
