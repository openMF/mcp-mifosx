import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel
from typing import Optional

# Import our custom banking domains and AI router
from tools.domains import clients, loans, savings
from tools.registry import router

# ==========================================
# 🚀 1. SERVER INITIALIZATION
# ==========================================
app = FastAPI(
    title="Mifos Headless MCP Server",
    description="Enterprise API bridge for AI Agents and Go CLI to access Apache Fineract.",
    version="1.0.0"
)

# Allow cross-origin requests (Crucial for UI and remote Agent communication)
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # In production, restrict this to your specific frontend URL
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ==========================================
# 📦 2. PYDANTIC DATA MODELS (Strict Validation)
# ==========================================
class IntentQuery(BaseModel):
    prompt: str

class ClientCreate(BaseModel):
    firstname: str
    lastname: str
    mobile_no: Optional[str] = None
    office_id: int = 1
    is_active: bool = True

class ClientMobileUpdate(BaseModel):
    new_mobile_no: str

class ClientClose(BaseModel):
    closure_reason_id: int = 17

class GroupCreate(BaseModel):
    name: str
    office_id: int = 1
    client_members: Optional[list] = None

class LoanCreate(BaseModel):
    client_id: int
    principal: float
    months: int
    product_id: int = 1

class LoanDisburse(BaseModel):
    amount: Optional[float] = None

class LoanReject(BaseModel):
    note: str = "Rejected via AI Agent due to risk profile"

class SavingsCreate(BaseModel):
    client_id: int
    product_id: int = 1

class Transaction(BaseModel):
    amount: float

class SavingsCharge(BaseModel):
    amount: float
    charge_id: int = 1

class LateFee(BaseModel):
    fee_amount: float

class WaiveInterest(BaseModel):
    amount: float
    note: str = "AI Authorized Waiver"


# Helper function to handle Fineract errors gracefully
def handle_response(result):
    if isinstance(result, dict) and "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


# ==========================================
# 🔀 3. AI ROUTER ENDPOINT
# ==========================================
@app.post("/api/router/intent", tags=["AI Router"])
def route_intent(query: IntentQuery):
    """Dynamically returns the list of tools the AI should load based on the prompt."""
    active_tools = router.route_intent(query.prompt)
    return {"status": "success", "tools_loaded": active_tools}


# ==========================================
# 👥 4. CLIENT ENDPOINTS
# ==========================================
@app.get("/api/clients/search", tags=["Clients"])
def search_clients(name: str):
    """Search for clients by name."""
    return handle_response(clients.search_clients_by_name(name))

@app.get("/api/clients/{client_id}", tags=["Clients"])
def get_client(client_id: int):
    """Get full details for a specific client."""
    return handle_response(clients.get_client_details(client_id))

@app.get("/api/clients/{client_id}/accounts", tags=["Clients"])
def get_client_accounts(client_id: int):
    """Get all loans and savings accounts for a client."""
    return handle_response(clients.get_client_accounts(client_id))

@app.post("/api/clients", tags=["Clients"])
def create_client(payload: ClientCreate):
    """Create a new client."""
    return handle_response(clients.create_client(
        payload.firstname, payload.lastname, payload.mobile_no,
        payload.office_id, payload.is_active
    ))

@app.post("/api/clients/{client_id}/activate", tags=["Clients"])
def activate_client(client_id: int):
    """Activate a pending client profile."""
    return handle_response(clients.activate_client(client_id))

@app.put("/api/clients/{client_id}/mobile", tags=["Clients"])
def update_client_mobile(client_id: int, payload: ClientMobileUpdate):
    """Update the mobile number for a client."""
    return handle_response(clients.update_client_mobile(client_id, payload.new_mobile_no))

@app.post("/api/clients/{client_id}/close", tags=["Clients"])
def close_client(client_id: int, payload: ClientClose):
    """Close a client profile."""
    return handle_response(clients.close_client(client_id, payload.closure_reason_id))


# ==========================================
# 🏢 5. GROUP ENDPOINTS
# ==========================================
@app.post("/api/groups", tags=["Groups"])
def create_group(payload: GroupCreate):
    """Create a new lending group."""
    return handle_response(clients.create_group(
        payload.name, payload.office_id, payload.client_members
    ))

@app.get("/api/groups/{group_id}", tags=["Groups"])
def get_group(group_id: int):
    """Get details and members of a group."""
    return handle_response(clients.get_group_details(group_id))


# ==========================================
# 💰 6. LOAN ENDPOINTS
# ==========================================
@app.get("/api/loans/{loan_id}", tags=["Loans"])
def get_loan(loan_id: int):
    """Get full details for a specific loan."""
    return handle_response(loans.get_loan_details(loan_id))

@app.get("/api/loans/{loan_id}/schedule", tags=["Loans"])
def get_repayment_schedule(loan_id: int):
    """Get the repayment schedule for a loan."""
    return handle_response(loans.get_repayment_schedule(loan_id))

@app.post("/api/loans", tags=["Loans"])
def create_loan(payload: LoanCreate):
    """Apply for a new loan."""
    return handle_response(loans.create_loan(
        payload.client_id, payload.principal, payload.months, payload.product_id
    ))

@app.post("/api/loans/{loan_id}/approve-disburse", tags=["Loans"])
def approve_and_disburse_loan(loan_id: int, payload: LoanDisburse):
    """Approve and disburse a loan."""
    return handle_response(loans.approve_and_disburse_loan(loan_id, payload.amount))

@app.post("/api/loans/{loan_id}/reject", tags=["Loans"])
def reject_loan(loan_id: int, payload: LoanReject):
    """Reject a loan application."""
    return handle_response(loans.reject_loan_application(loan_id, payload.note))

@app.post("/api/loans/{loan_id}/repayment", tags=["Loans"])
def make_loan_repayment(loan_id: int, payload: Transaction):
    """Make a repayment on a loan."""
    return handle_response(loans.make_loan_repayment(loan_id, payload.amount))

@app.post("/api/loans/{loan_id}/late-fee", tags=["Loans"])
def apply_late_fee(loan_id: int, payload: LateFee):
    """Apply a late fee to a loan."""
    return handle_response(loans.apply_late_fee(loan_id, payload.fee_amount))

@app.post("/api/loans/{loan_id}/waive-interest", tags=["Loans"])
def waive_interest(loan_id: int, payload: WaiveInterest):
    """Waive interest on a loan."""
    return handle_response(loans.waive_interest(loan_id, payload.amount, payload.note))


# ==========================================
# 🏦 7. SAVINGS ENDPOINTS
# ==========================================
@app.get("/api/savings/{account_id}", tags=["Savings"])
def get_savings(account_id: int):
    """Get details of a savings account."""
    return handle_response(savings.get_savings_account(account_id))

@app.get("/api/savings/{account_id}/transactions", tags=["Savings"])
def get_savings_transactions(account_id: int):
    """Get transaction history for a savings account."""
    return handle_response(savings.get_savings_transactions(account_id))

@app.post("/api/savings", tags=["Savings"])
def create_savings_account(payload: SavingsCreate):
    """Open a new savings account for a client."""
    return handle_response(savings.create_savings_account(payload.client_id, payload.product_id))

@app.post("/api/savings/{account_id}/approve-activate", tags=["Savings"])
def approve_and_activate_savings(account_id: int):
    """Approve and activate a pending savings account."""
    return handle_response(savings.approve_and_activate_savings(account_id))

@app.post("/api/savings/{account_id}/close", tags=["Savings"])
def close_savings_account(account_id: int):
    """Close a savings account."""
    return handle_response(savings.close_savings_account(account_id))

@app.post("/api/savings/{account_id}/deposit", tags=["Savings"])
def deposit_savings(account_id: int, payload: Transaction):
    """Deposit money into a savings account."""
    return handle_response(savings.deposit_savings(account_id, payload.amount))

@app.post("/api/savings/{account_id}/withdraw", tags=["Savings"])
def withdraw_savings(account_id: int, payload: Transaction):
    """Withdraw money from a savings account."""
    return handle_response(savings.withdraw_savings(account_id, payload.amount))

@app.post("/api/savings/{account_id}/charge", tags=["Savings"])
def apply_savings_charge(account_id: int, payload: SavingsCharge):
    """Apply a charge/fee to a savings account."""
    return handle_response(savings.apply_savings_charge(account_id, payload.amount, payload.charge_id))

@app.post("/api/savings/{account_id}/post-interest", tags=["Savings"])
def post_savings_interest(account_id: int):
    """Calculate and post accrued interest for a savings account."""
    return handle_response(savings.calculate_and_post_interest(account_id))


# ==========================================
# 🏃‍♂️ 8. SERVER RUNNER
# ==========================================
if __name__ == "__main__":
    print("\n" + "="*50)
    print("🚀 STARTING HEADLESS MCP SERVER 🚀")
    print("="*50)
    print("🌐 API Docs available at: http://localhost:8000/docs")
    uvicorn.run("core.api_server:app", host="0.0.0.0", port=8000, reload=True)