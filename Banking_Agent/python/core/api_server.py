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

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], 
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# ==========================================
# 📦 2. PYDANTIC DATA MODELS
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
    # FIX: Extract just the tool names so FastAPI can serialize it to JSON
    tool_names = [tool.name for tool in active_tools]
    return {"status": "success", "tools_loaded": tool_names}

class ChatRequest(BaseModel):
    message: str

@app.post("/api/chat", tags=["Agent"])
def chat_with_agent(req: ChatRequest):
    """Send a string to the LangGraph AI copilot and get the response back."""
    from agent import banking_copilot
    
    print(f"\\n📞 [API] Received chat request: '{req.message}'")
    
    try:
        events = banking_copilot.stream(
            {"messages": [("human", req.message)]}, 
            stream_mode="values"
        )
        final_msg = None
        for event in events:
            final_msg = event["messages"][-1]
            
        if final_msg and final_msg.type == "ai" and not final_msg.tool_calls:
            return {"reply": final_msg.content}
            
        if final_msg:
            return {"reply": final_msg.content or "Finished tool execution."}
            
        return {"reply": "Failed to get AI response"}
    except Exception as e:
        print(f"Error in chat_with_agent: {e}")
        return {"reply": f"Error thinking: {str(e)}"}


# ==========================================
# 👥 4. CLIENT ENDPOINTS (Using .func bypass)
# ==========================================
@app.get("/api/clients/search", tags=["Clients"])
def search_clients(name: str):
    return handle_response(clients.search_clients_by_name.func(name_query=name))

@app.get("/api/clients/{client_id}", tags=["Clients"])
def get_client(client_id: int):
    return handle_response(clients.get_client_details.func(client_id=client_id))

@app.get("/api/clients/{client_id}/accounts", tags=["Clients"])
def get_client_accounts(client_id: int):
    return handle_response(clients.get_client_accounts.func(client_id=client_id))

@app.post("/api/clients", tags=["Clients"])
def create_client(payload: ClientCreate):
    return handle_response(clients.create_client.func(
        firstname=payload.firstname, lastname=payload.lastname, 
        mobile_no=payload.mobile_no, office_id=payload.office_id, is_active=payload.is_active
    ))

@app.post("/api/clients/{client_id}/activate", tags=["Clients"])
def activate_client(client_id: int):
    return handle_response(clients.activate_client.func(client_id=client_id))

@app.put("/api/clients/{client_id}/mobile", tags=["Clients"])
def update_client_mobile(client_id: int, payload: ClientMobileUpdate):
    return handle_response(clients.update_client_mobile.func(client_id=client_id, new_mobile_no=payload.new_mobile_no))

@app.post("/api/clients/{client_id}/close", tags=["Clients"])
def close_client(client_id: int, payload: ClientClose):
    return handle_response(clients.close_client.func(client_id=client_id, closure_reason_id=payload.closure_reason_id))


# ==========================================
# 🏢 5. GROUP ENDPOINTS
# ==========================================
@app.post("/api/groups", tags=["Groups"])
def create_group(payload: GroupCreate):
    return handle_response(clients.create_group.func(
        name=payload.name, office_id=payload.office_id, client_members=payload.client_members
    ))

@app.get("/api/groups/{group_id}", tags=["Groups"])
def get_group(group_id: int):
    return handle_response(clients.get_group_details.func(group_id=group_id))


# ==========================================
# 💰 6. LOAN ENDPOINTS
# ==========================================
@app.get("/api/loans/{loan_id}", tags=["Loans"])
def get_loan(loan_id: int):
    return handle_response(loans.get_loan_details.func(loan_id=loan_id))

@app.get("/api/loans/{loan_id}/schedule", tags=["Loans"])
def get_repayment_schedule(loan_id: int):
    return handle_response(loans.get_repayment_schedule.func(loan_id=loan_id))

@app.post("/api/loans", tags=["Loans"])
def create_loan(payload: LoanCreate):
    return handle_response(loans.create_loan.func(
        client_id=payload.client_id, principal=payload.principal, 
        months=payload.months, product_id=payload.product_id
    ))

@app.post("/api/loans/{loan_id}/approve-disburse", tags=["Loans"])
def approve_and_disburse_loan(loan_id: int, payload: LoanDisburse):
    return handle_response(loans.approve_and_disburse_loan.func(loan_id=loan_id, amount=payload.amount))

@app.post("/api/loans/{loan_id}/reject", tags=["Loans"])
def reject_loan(loan_id: int, payload: LoanReject):
    return handle_response(loans.reject_loan_application.func(loan_id=loan_id, note=payload.note))

@app.post("/api/loans/{loan_id}/repayment", tags=["Loans"])
def make_loan_repayment(loan_id: int, payload: Transaction):
    return handle_response(loans.make_loan_repayment.func(loan_id=loan_id, amount=payload.amount))

@app.post("/api/loans/{loan_id}/late-fee", tags=["Loans"])
def apply_late_fee(loan_id: int, payload: LateFee):
    return handle_response(loans.apply_late_fee.func(loan_id=loan_id, fee_amount=payload.fee_amount))

@app.post("/api/loans/{loan_id}/waive-interest", tags=["Loans"])
def waive_interest(loan_id: int, payload: WaiveInterest):
    return handle_response(loans.waive_interest.func(loan_id=loan_id, amount=payload.amount, note=payload.note))


# ==========================================
# 🏦 7. SAVINGS ENDPOINTS
# ==========================================
@app.get("/api/savings/{account_id}", tags=["Savings"])
def get_savings(account_id: int):
    return handle_response(savings.get_savings_account.func(account_id=account_id))

@app.get("/api/savings/{account_id}/transactions", tags=["Savings"])
def get_savings_transactions(account_id: int):
    return handle_response(savings.get_savings_transactions.func(account_id=account_id))

@app.post("/api/savings", tags=["Savings"])
def create_savings_account(payload: SavingsCreate):
    return handle_response(savings.create_savings_account.func(client_id=payload.client_id, product_id=payload.product_id))

@app.post("/api/savings/{account_id}/approve-activate", tags=["Savings"])
def approve_and_activate_savings(account_id: int):
    return handle_response(savings.approve_and_activate_savings.func(account_id=account_id))

@app.post("/api/savings/{account_id}/close", tags=["Savings"])
def close_savings_account(account_id: int):
    return handle_response(savings.close_savings_account.func(account_id=account_id))

@app.post("/api/savings/{account_id}/deposit", tags=["Savings"])
def deposit_savings(account_id: int, payload: Transaction):
    return handle_response(savings.deposit_savings.func(account_id=account_id, amount=payload.amount))

@app.post("/api/savings/{account_id}/withdraw", tags=["Savings"])
def withdraw_savings(account_id: int, payload: Transaction):
    return handle_response(savings.withdraw_savings.func(account_id=account_id, amount=payload.amount))

@app.post("/api/savings/{account_id}/charge", tags=["Savings"])
def apply_savings_charge(account_id: int, payload: SavingsCharge):
    return handle_response(savings.apply_savings_charge.func(account_id=account_id, amount=payload.amount, charge_id=payload.charge_id))

@app.post("/api/savings/{account_id}/post-interest", tags=["Savings"])
def post_savings_interest(account_id: int):
    return handle_response(savings.calculate_and_post_interest.func(account_id=account_id))


# ==========================================
# 🏃‍♂️ 8. SERVER RUNNER
# ==========================================
if __name__ == "__main__":
    print("\n" + "="*50)
    print("🚀 STARTING HEADLESS MCP SERVER 🚀")
    print("="*50)
    print("🌐 API Docs available at: http://localhost:8000/docs")
    uvicorn.run("api_server:app", host="0.0.0.0", port=8000, reload=True)