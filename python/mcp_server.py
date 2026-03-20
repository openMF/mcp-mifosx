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
    close_client,
    get_client_identifiers, create_client_identifier, get_client_documents,
    get_client_charges, apply_client_charge, get_client_transactions,
    get_client_addresses
)
from tools.domains.groups import (
    list_groups, get_group as get_group_domain, create_group as create_group_domain,
    activate_group as activate_group_domain, add_group_member,
    list_centers, get_center as get_center_domain, create_center as create_center_domain
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

# --- Shared helpers ---

def _fmt_date(d) -> str:
    """Format Fineract dates to '2 March 2026'. Handles list [YYYY,M,D] and string 'YYYY-M-D'."""
    months = ["","January","February","March","April","May","June",
              "July","August","September","October","November","December"]
    if isinstance(d, list) and len(d) >= 3:
        return f"{d[2]} {months[d[1]]} {d[0]}"
    if isinstance(d, str) and "-" in d:
        parts = d.split("-")
        if len(parts) == 3:
            try:
                return f"{int(parts[2])} {months[int(parts[1])]} {parts[0]}"
            except (ValueError, IndexError):
                pass
    return d

def _resolve_client_id(name: str):
    """Search for a client by name and return their numeric ID, or None."""
    result = search_clients_by_name.func(name)
    clients = result if isinstance(result, list) else result.get("pageItems", [])
    if not clients:
        return None
    return clients[0].get("entityId") or clients[0].get("id")

# --- Register all MCP-native tools ---

# --- CLIENTS & GROUPS ---

@mcp.tool()
def search_clients(nameQuery: str) -> dict:
    """Find the client ID for a given name. Returns clientId — use this for all subsequent tool calls."""
    result = search_clients_by_name.func(nameQuery)
    clients = result if isinstance(result, list) else result.get("pageItems", [])
    return {
        "clients": [
            {
                "clientId":    c.get("entityId") or c.get("id"),
                "displayName": c.get("entityName") or c.get("displayName"),
                "accountNo":   c.get("entityAccountNo") or c.get("accountNo"),
                "status":      c.get("entityStatus", {}).get("value") if isinstance(c.get("entityStatus"), dict) else c.get("entityStatus"),
            }
            for c in clients
        ]
    }

@mcp.tool()
def get_client(clientId: int) -> dict:
    """Show key details for a specific client."""
    data = get_client_details.func(clientId)
    if not isinstance(data, dict):
        return data
    return {
        "clientId":       data.get("id"),
        "displayName":    data.get("displayName"),
        "firstname":      data.get("firstname"),
        "lastname":       data.get("lastname"),
        "accountNo":      data.get("accountNo"),
        "mobileNo":       data.get("mobileNo"),
        "status":         data.get("status", {}).get("value"),
        "activationDate": _fmt_date(data.get("activationDate")),
        "officeName":     data.get("officeName"),
    }

@mcp.tool()
def get_client_accts(clientId: int = None, clientIds: list = None, id: int = None, nameQuery: str = None, name: str = None) -> dict:
    """Show all loans and savings accounts for a client. Accepts either a clientId (int) or a client name via nameQuery."""
    # Resolve by name if no ID was given (Qwen may pass nameQuery directly)
    if not clientId and not id and not clientIds:
        search_name = nameQuery or name
        if search_name:
            actual_id = _resolve_client_id(search_name)
            if not actual_id:
                return {"error": f"No client found for name '{search_name}'. Please verify the name and try again."}
        else:
            return {"error": "No client ID or name provided. Pass clientId (int) or nameQuery (str)."}
    else:
        actual_id = clientId or id or (clientIds[0] if clientIds else None)

    result = get_client_accounts.func(int(actual_id))
    if not isinstance(result, dict) or result.get("httpStatusCode") == 404:
        return {"error": f"Client ID {actual_id} not found. Call search_clients(name) first."}

    loans = result.get("loanAccounts", [])
    savings = result.get("savingsAccounts", [])

    # Catch hallucinated IDs where Fineract returns 200 OK but empty data
    if not loans and not savings and int(actual_id) > 1000:
        return {"error": f"Client ID {actual_id} returned no accounts — likely hallucinated. Call search_clients(name) first."}

    return {
        "resolvedClientId": actual_id,
        "loanAccounts": [
            {
                "loanId":                l.get("id"),
                "accountNo":             l.get("accountNo"),
                "status":                l.get("status", {}).get("value"),
                "outstandingBalance_USD": l.get("loanBalance") if l.get("loanBalance") is not None else 0.00,
            }
            for l in loans
        ],
        "savingsAccounts": [
            {
                "savingsId":             s.get("id"),
                "accountNo":             s.get("accountNo"),
                "status":                s.get("status", {}).get("value"),
                "outstandingBalance_USD": s.get("accountBalance") if s.get("accountBalance") is not None else 0.00,
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
def create_lending_group(name: str, officeId: int = 1, externalId: str = None) -> dict:
    """Create a new lending group"""
    return create_group_domain.func(name, officeId, externalId)

@mcp.tool()
def get_group(groupId: int) -> dict:
    """Show details and members of a lending group"""
    return get_group_domain.func(groupId)

@mcp.tool()
def list_all_groups(officeId: int = None) -> dict:
    """List all lending groups"""
    return list_groups.func(officeId)

@mcp.tool()
def activate_pending_group(groupId: int) -> dict:
    """Activate a pending group"""
    return activate_group_domain.func(groupId)

@mcp.tool()
def add_member_to_group(groupId: int, clientId: int) -> dict:
    """Add a client member to a group"""
    return add_group_member.func(groupId, clientId)

@mcp.tool()
def list_all_centers(officeId: int = None) -> dict:
    """List all centers"""
    return list_centers.func(officeId)

@mcp.tool()
def get_center(centerId: int) -> dict:
    """Show details for a center"""
    return get_center_domain.func(centerId)

@mcp.tool()
def create_new_center(name: str, officeId: int, externalId: str = None) -> dict:
    """Create a new center"""
    return create_center_domain.func(name, officeId, externalId)

@mcp.tool()
def get_identifiers(clientId: int) -> dict:
    """List client ID documents (passports, etc.)"""
    return get_client_identifiers.func(clientId)

@mcp.tool()
def add_identifier(clientId: int, documentTypeId: int, documentKey: str) -> dict:
    """Add a new identity document for a client"""
    return create_client_identifier.func(clientId, documentTypeId, documentKey)

@mcp.tool()
def list_documents(clientId: int) -> dict:
    """List all uploaded files/documents for a client"""
    return get_client_documents.func(clientId)

@mcp.tool()
def list_client_charges(clientId: int) -> dict:
    """List client-level fees or penalties"""
    return get_client_charges.func(clientId)

@mcp.tool()
def apply_client_fee(clientId: int, chargeId: int, amount: float) -> dict:
    """Apply a one-time fee/charge to a client profile"""
    return apply_client_charge.func(clientId, chargeId, amount)

@mcp.tool()
def list_client_txns(clientId: int) -> dict:
    """List financial transactions for the client"""
    return get_client_transactions.func(clientId)

@mcp.tool()
def get_addresses(clientId: int) -> dict:
    """Show client addresses"""
    return get_client_addresses.func(clientId)

# --- LOANS ---

@mcp.tool()
def get_loan(loanId: int) -> dict:
    """Get key details of a specific loan."""
    data = get_loan_details.func(loanId)
    if not isinstance(data, dict):
        return data
    tl = data.get("timeline", {})
    return {
        "loanId":              data.get("id"),
        "accountNo":           data.get("accountNo"),
        "productName":         data.get("loanProductName"),
        "status":              data.get("status", {}).get("value"),
        "loanType":            data.get("loanType", {}).get("value"),
        "principal_USD":       data.get("approvedPrincipal"),
        "outstandingBalance_USD": data.get("summary", {}).get("totalOutstanding"),
        "interestRate_pct":    data.get("annualInterestRate"),
        "submittedDate":       _fmt_date(tl.get("submittedOnDate")),
        "approvedDate":        _fmt_date(tl.get("approvedOnDate")),
        "disbursedDate":       _fmt_date(tl.get("actualDisbursementDate")),
        "expectedMaturityDate": _fmt_date(tl.get("expectedMaturityDate")),
        "numberOfRepayments":  data.get("numberOfRepayments"),
        "repaymentFrequency":  f"Every {data.get('repaymentEvery')} {data.get('repaymentFrequencyType', {}).get('value','')}",
    }

@mcp.tool()
def get_repayment_sched(loanId: int) -> dict:
    """Get the repayment schedule for a loan."""
    data = get_repayment_schedule.func(loanId)
    if not isinstance(data, dict):
        return data
    periods = data.get("periods", [])
    return {
        "loanId": loanId,
        "periods": [
            {
                "period":           p.get("period"),
                "dueDate":          _fmt_date(p.get("dueDate")),
                "principalDue_USD": p.get("principalDue", 0),
                "interestDue_USD":  p.get("interestDue", 0),
                "feesDue_USD":      p.get("feeChargesDue", 0),
                "totalDue_USD":     p.get("totalDueForPeriod", 0),
                "totalPaid_USD":    p.get("totalPaidForPeriod", 0),
                "isComplete":       p.get("complete", False),
            }
            for p in periods if p.get("period")  # skip the header period (period=None)
        ],
    }

@mcp.tool()
def get_loan_hist(loanId: int) -> dict:
    """Get the transaction history for a loan (repayments, disbursements, charges)."""
    data = get_loan_history.func(loanId)
    if not isinstance(data, dict):
        return data
    txns = data.get("transactions", [])
    return {
        "loanId": loanId,
        "transactions": [
            {
                "transactionId": t.get("id"),
                "type":          t.get("type") if isinstance(t.get("type"), str) else (t.get("type") or {}).get("value"),
                "date":          _fmt_date(t.get("date")),
                "amount_USD":    t.get("amount"),
                "runningBalance_USD": t.get("outstandingLoanBalance"),
                "note":          t.get("noteText"),
            }
            for t in txns
        ],
    }

@mcp.tool()
def create_new_loan(clientId: int, principal: float, months: int, productId: int = 1) -> dict:
    """Create a new loan application"""
    return create_loan.func(clientId, principal, months, productId)

@mcp.tool()
def approve_disburse_loan(loanId: int, amount: float = None) -> dict:
    """Approve and disburse a pending loan. Validates loanId exists before executing."""
    check = get_loan_details.func(loanId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Loan ID {loanId} not found. Check get_client_accts to see valid loanIds."}
    status = check.get("status", {}).get("value", "")
    if "pending" not in status.lower() and "submitted" not in status.lower():
        return {"error": f"Loan {loanId} is in status '{status}' and cannot be approved. Only 'Submitted and pending approval' loans can be approved."}
    return approve_and_disburse_loan.func(loanId, amount)

@mcp.tool()
def reject_loan(loanId: int, note: str = "Rejected via AI Agent due to risk profile") -> dict:
    """Reject a pending loan application. Validates loanId exists before executing."""
    check = get_loan_details.func(loanId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Loan ID {loanId} not found. Check get_client_accts to see valid loanIds."}
    status = check.get("status", {}).get("value", "")
    if "pending" not in status.lower() and "submitted" not in status.lower():
        return {"error": f"Loan {loanId} is in status '{status}' and cannot be rejected. Only pending loans can be rejected."}
    return reject_loan_application.func(loanId, note)

@mcp.tool()
def make_repayment(loanId: int, amount: float) -> dict:
    """Make a repayment on an active loan. Validates loanId and status before executing."""
    check = get_loan_details.func(loanId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Loan ID {loanId} not found. Check get_client_accts to see valid loanIds."}
    status = check.get("status", {}).get("value", "")
    if "active" not in status.lower():
        return {"error": f"Loan {loanId} is in status '{status}'. Only Active loans can receive repayments."}
    return make_loan_repayment.func(loanId, amount)

@mcp.tool()
def apply_loan_fee(loanId: int, feeAmount: float) -> dict:
    """Apply a fee/charge to a loan. Validates loanId exists before executing."""
    check = get_loan_details.func(loanId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Loan ID {loanId} not found. Check get_client_accts to see valid loanIds."}
    return apply_late_fee.func(loanId, feeAmount, 2)

@mcp.tool()
def waive_loan_interest(loanId: int, amount: float, note: str = "AI Authorized Waiver") -> dict:
    """Waive interest on a loan. Validates loanId exists before executing."""
    check = get_loan_details.func(loanId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Loan ID {loanId} not found. Check get_client_accts to see valid loanIds."}
    return waive_interest.func(loanId, amount, note)

@mcp.tool()
def get_overdue_loans_for_client(clientId: int) -> dict:
    """Get all overdue or in-arrears loans for a client"""
    return get_overdue_loans.func(clientId)

@mcp.tool()
def create_group_loan_app(groupId: int, principal: float, months: int, productId: int = 1) -> dict:
    """Create a group loan application for an existing lending group"""
    return create_group_loan.func(groupId, principal, months, productId)

# --- SAVINGS ---

@mcp.tool()
def get_savings(accountId: int) -> dict:
    """Get key details of a savings account."""
    data = get_savings_account.func(accountId)
    if not isinstance(data, dict):
        return data
    tl = data.get("timeline", {})
    summary = data.get("summary", {})
    return {
        "savingsId":             data.get("id"),
        "accountNo":             data.get("accountNo"),
        "clientName":            data.get("clientName"),
        "productName":           data.get("savingsProductName"),
        "status":                data.get("status", {}).get("value"),
        "balance_USD":           summary.get("accountBalance"),
        "availableBalance_USD":  summary.get("availableBalance"),
        "activationDate":        _fmt_date(tl.get("activatedOnDate")),
        "nominalAnnualInterestRate": data.get("nominalAnnualInterestRate"),
    }

@mcp.tool()
def get_savings_txns(accountId: int) -> dict:
    """Get transactions for a savings account."""
    data = get_savings_transactions.func(accountId)
    if not isinstance(data, dict):
        return data
    txns = data.get("transactions", [])
    return {
        "savingsId": accountId,
        "transactions": [
            {
                "transactionId":   t.get("id"),
                "type":            t.get("transactionType", {}).get("value"),
                "date":            _fmt_date(t.get("date")),
                "amount_USD":      t.get("amount"),
                "runningBalance_USD": t.get("runningBalance"),
            }
            for t in txns
        ],
    }

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
    """Deposit money into a savings account. Validates accountId exists before executing."""
    check = get_savings_account.func(accountId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Savings account ID {accountId} not found. Check get_client_accts to see valid savingsIds."}
    status = check.get("status", {}).get("value", "")
    if "active" not in status.lower():
        return {"error": f"Savings account {accountId} is in status '{status}'. Only Active accounts can accept deposits."}
    return deposit_savings.func(accountId, amount)

@mcp.tool()
def withdraw(accountId: int, amount: float) -> dict:
    """Withdraw money from a savings account. Validates accountId and balance before executing."""
    check = get_savings_account.func(accountId)
    if not isinstance(check, dict) or check.get("httpStatusCode") == 404:
        return {"error": f"Savings account ID {accountId} not found. Check get_client_accts to see valid savingsIds."}
    status = check.get("status", {}).get("value", "")
    if "active" not in status.lower():
        return {"error": f"Savings account {accountId} is in status '{status}'. Only Active accounts can be withdrawn from."}
    balance = (check.get("summary") or {}).get("availableBalance", 0) or 0
    if amount > balance:
        return {"error": f"Insufficient funds: requested ${amount:.2f} but available balance is ${balance:.2f} in account {accountId}."}
    return withdraw_savings.func(accountId, amount)

@mcp.tool()
def apply_savings_fee(accountId: int, amount: float, chargeId: int = 1) -> dict:
    """Apply a charge/block to a savings account"""
    return apply_savings_charge.func(accountId, amount, chargeId)

@mcp.tool()
def calc_post_interest(accountId: int) -> dict:
    """Calculate and post interest to a savings account"""
    return calculate_and_post_interest.func(accountId)


# --- STAFF & OFFICES ---

@mcp.tool()
def list_all_staff(officeId: int = None, status: str = "all") -> dict:
    """List bank staff members"""
    return list_staff.func(officeId, status)

@mcp.tool()
def get_staff(staffId: int) -> dict:
    """Get details for a staff member"""
    return get_staff_details.func(staffId)

@mcp.tool()
def list_all_offices() -> dict:
    """List all bank offices/branches"""
    return list_offices.func()

@mcp.tool()
def get_office(officeId: int) -> dict:
    """Get details for an office"""
    return get_office_details.func(officeId)


# --- ACCOUNTING ---

@mcp.tool()
def list_accounts(type: int = None) -> dict:
    """List GL accounts (Chart of Accounts). Types: 1=Asset, 2=Liability, 3=Equity, 4=Income, 5=Expense"""
    return list_gl_accounts.func(type)

@mcp.tool()
def list_journal_entries(glAccountId: int = None, transactionId: str = None) -> dict:
    """List journal entries for an account or transaction ID"""
    return get_journal_entries.func(glAccountId, transactionId)

@mcp.tool()
def record_journal_entry(officeId: int, date: str, credits: list, debits: list, comment: str = "") -> dict:
    """Record a manual journal entry. Date format: 'dd MMMM yyyy' e.g. '10 March 2026'"""
    return create_journal_entry.func(officeId, date, credits, debits, comment)


# --- REPORTS ---

@mcp.tool()
def list_all_reports(reportType: str = None) -> dict:
    """List all Fineract report definitions. Optionally filter by type: 'Table', 'Chart', 'SMS', 'Text', 'Pentaho'."""
    return list_reports.func(reportType)

@mcp.tool()
def get_report_definition(reportId: int) -> dict:
    """Get the full definition (SQL, parameters, type) for a specific report by ID."""
    return get_report.func(reportId)

@mcp.tool()
def run_fineract_report(reportName: str, params: dict = None) -> dict:
    """Run a Fineract report by its exact name and return the results.
    Example: run_fineract_report('Active Loans - Summary', {'officeId': '1'})"""
    return run_report.func(reportName, params)

@mcp.tool()
def create_report_definition(reportName: str, reportType: str, reportSql: str, description: str = "") -> dict:
    """Register a new report definition in Fineract.
    reportType: 'Table' | 'Chart' | 'SMS' | 'Text' | 'Pentaho'"""
    return create_report.func(reportName, reportType, reportSql, description)

@mcp.tool()
def update_report_definition(reportId: int, reportName: str = None, reportType: str = None, reportSql: str = None, description: str = None) -> dict:
    """Update an existing report definition. Only provided fields are changed."""
    return update_report.func(reportId, reportName, reportType, reportSql, description)


# --- PRODUCTS ---

@mcp.tool()
def list_available_loan_products() -> dict:
    """List all loan products with their principal ranges, interest rates, and repayment terms.
    Call this before create_new_loan to find a valid productId."""
    return list_loan_products.func()

@mcp.tool()
def get_loan_product_details(productId: int) -> dict:
    """Get full details for a loan product including charges, interest rules, and amortization type."""
    return get_loan_product.func(productId)

@mcp.tool()
def list_available_savings_products() -> dict:
    """List all savings products with their interest rates and minimum balances.
    Call this before create_savings to find a valid productId."""
    return list_savings_products.func()

@mcp.tool()
def get_savings_product_details(productId: int) -> dict:
    """Get full details for a savings product including interest compounding rules and charges."""
    return get_savings_product.func(productId)


# 5. START SERVER
if __name__ == "__main__":
    logger.info("Initializing Mifos FastMCP Server on STDIO Transport")
    mcp.run()
