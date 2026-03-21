# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from typing import Optional

import uvicorn
from fastapi import FastAPI, HTTPException
from fastapi.middleware.cors import CORSMiddleware
from pydantic import BaseModel

# Import our custom banking domains and AI router
from tools.domains import accounting, clients, groups, loans, savings, staff
from tools.registry import router

# --- SERVER INITIALIZATION ---
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

# --- PYDANTIC DATA MODELS ---
class IntentQuery(BaseModel):
    prompt: str

class ChatRequest(BaseModel):
    message: str

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

class ClientIdentifierCreate(BaseModel):
    document_type_id: int
    document_key: str

class ClientChargeApply(BaseModel):
    charge_id: int
    amount: float

class GroupCreate(BaseModel):
    name: str
    office_id: int = 1
    client_members: Optional[list] = None

class CenterCreate(BaseModel):
    name: str
    office_id: int
    external_id: Optional[str] = None

class LoanCreate(BaseModel):
    client_id: int
    principal: float
    months: int
    product_id: int = 1

class GroupLoanCreate(BaseModel):
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
    charge_id: int = 2

class WaiveInterest(BaseModel):
    amount: float
    note: str = "AI Authorized Waiver"

class JournalEntryItem(BaseModel):
    glAccountId: int
    amount: float

class JournalEntryCreate(BaseModel):
    office_id: int
    date: str
    credits: list[JournalEntryItem]
    debits: list[JournalEntryItem]
    comment: str = ""

# Helper function to handle Fineract errors gracefully
def handle_response(result):
    if isinstance(result, dict) and "error" in result:
        raise HTTPException(status_code=400, detail=result["error"])
    return result


# --- AI ROUTER ENDPOINT ---
@app.post("/api/router/intent", tags=["AI Router"])
def route_intent(query: IntentQuery):
    """Dynamically returns the list of tools the AI should load based on the prompt."""
    active_tools = router.route_intent(query.prompt)
    # FIX: Extract just the tool names so FastAPI can serialize it to JSON
    tool_names = [tool.name for tool in active_tools]
    return {"status": "success", "tools_loaded": tool_names}

# Global agent instance
banking_agent = None

@app.on_event("startup")
async def startup_event():
    global banking_agent
    print("\nLoading Mifos AI Agent...")
    from agent import get_agent_instance
    banking_agent = await get_agent_instance()
    print("Agent ready.")

@app.post("/api/chat", tags=["Agent"])
async def chat_with_agent(req: ChatRequest):
    """Send a string to the LangGraph AI copilot and get the response back."""
    if banking_agent is None:
        raise HTTPException(status_code=503, detail="Agent not initialized")

    print(f"\n[API] Received chat request: '{req.message}'")

    # In a real app, thread_id should come from the request/session
    thread_id = "default-web-session"
    config = {"configurable": {"thread_id": thread_id}}

    try:
        # Get final response from stream
        final_msg = None
        async for chunk in banking_agent.astream(
            {"messages": [("human", req.message)]},
            config=config,
            stream_mode="values"
        ):
            final_msg = chunk["messages"][-1]

        if final_msg and final_msg.type == "ai" and not final_msg.tool_calls:
            return {"reply": final_msg.content}

        if final_msg:
            return {"reply": final_msg.content or "Finished tool execution."}

        return {"reply": "Failed to get AI response"}
    except Exception as e:
        print(f"Error in chat_with_agent: {e}")
        return {"reply": f"Error thinking: {str(e)}"}


# --- CLIENT ENDPOINTS ---
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

@app.get("/api/clients/{client_id}/identifiers", tags=["Clients"])
def get_client_identifiers(client_id: int):
    return handle_response(clients.get_client_identifiers.func(client_id=client_id))

@app.post("/api/clients/{client_id}/identifiers", tags=["Clients"])
def create_client_identifier(client_id: int, payload: ClientIdentifierCreate):
    return handle_response(clients.create_client_identifier.func(
        client_id=client_id, document_type_id=payload.document_type_id, document_key=payload.document_key
    ))

@app.get("/api/clients/{client_id}/documents", tags=["Clients"])
def get_client_documents(client_id: int):
    return handle_response(clients.get_client_documents.func(client_id=client_id))

@app.get("/api/clients/{client_id}/charges", tags=["Clients"])
def get_client_charges(client_id: int):
    return handle_response(clients.get_client_charges.func(client_id=client_id))

@app.post("/api/clients/{client_id}/charges", tags=["Clients"])
def apply_client_charge(client_id: int, payload: ClientChargeApply):
    return handle_response(clients.apply_client_charge.func(
        client_id=client_id, charge_id=payload.charge_id, amount=payload.amount
    ))

@app.get("/api/clients/{client_id}/transactions", tags=["Clients"])
def get_client_transactions(client_id: int):
    return handle_response(clients.get_client_transactions.func(client_id=client_id))

@app.get("/api/clients/{client_id}/addresses", tags=["Clients"])
def get_client_addresses(client_id: int):
    return handle_response(clients.get_client_addresses.func(client_id=client_id))


# --- GROUP ENDPOINTS ---
@app.post("/api/groups", tags=["Groups"])
def create_group(payload: GroupCreate):
    return handle_response(clients.create_group.func(
        name=payload.name, office_id=payload.office_id, client_members=payload.client_members
    ))

@app.get("/api/groups/{group_id}", tags=["Groups"])
def get_group(group_id: int):
    return handle_response(clients.get_group_details.func(group_id=group_id))

@app.get("/api/groups", tags=["Groups"])
def list_groups(office_id: Optional[int] = None):
    return handle_response(groups.list_groups.func(office_id=office_id))

@app.post("/api/groups/{group_id}/activate", tags=["Groups"])
def activate_group(group_id: int):
    return handle_response(groups.activate_group.func(group_id=group_id))

@app.post("/api/groups/{group_id}/members/{client_id}", tags=["Groups"])
def add_group_member(group_id: int, client_id: int):
    return handle_response(groups.add_group_member.func(group_id=group_id, client_id=client_id))

# --- CENTER ENDPOINTS ---
@app.get("/api/centers", tags=["Centers"])
def list_centers(office_id: Optional[int] = None):
    return handle_response(groups.list_centers.func(office_id=office_id))

@app.get("/api/centers/{center_id}", tags=["Centers"])
def get_center(center_id: int):
    return handle_response(groups.get_center.func(center_id=center_id))

@app.post("/api/centers", tags=["Centers"])
def create_center(payload: CenterCreate):
    return handle_response(groups.create_center.func(
        name=payload.name, office_id=payload.office_id, external_id=payload.external_id
    ))


# --- LOAN ENDPOINTS ---
@app.get("/api/loans/{loan_id}", tags=["Loans"])
def get_loan(loan_id: int):
    return handle_response(loans.get_loan_details.func(loan_id=loan_id))

@app.get("/api/loans/{loan_id}/schedule", tags=["Loans"])
def get_repayment_schedule(loan_id: int):
    return handle_response(loans.get_repayment_schedule.func(loan_id=loan_id))

@app.get("/api/loans/{loan_id}/history", tags=["Loans"])
def get_loan_history(loan_id: int):
    """Full transaction history for a loan: repayments, disbursements, charges, outstanding balance"""
    return handle_response(loans.get_loan_history.func(loan_id=loan_id))

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
    return handle_response(loans.apply_late_fee.func(loan_id=loan_id, fee_amount=payload.fee_amount, charge_id=payload.charge_id))

@app.post("/api/loans/{loan_id}/waive-interest", tags=["Loans"])
def waive_interest(loan_id: int, payload: WaiveInterest):
    return handle_response(loans.waive_interest.func(loan_id=loan_id, amount=payload.amount, note=payload.note))

@app.get("/api/clients/{client_id}/overdue-loans", tags=["Loans"])
def get_overdue_loans(client_id: int):
    """Get all overdue/in-arrears loans for a specific client"""
    return handle_response(loans.get_overdue_loans.func(client_id=client_id))

@app.post("/api/groups/{group_id}/loans", tags=["Loans"])
def create_group_loan(group_id: int, payload: GroupLoanCreate):
    """Create a new group loan application"""
    return handle_response(loans.create_group_loan.func(
        group_id=group_id, principal=payload.principal,
        months=payload.months, product_id=payload.product_id
    ))


# --- SAVINGS ENDPOINTS ---
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


# --- STAFF ENDPOINTS ---
@app.get("/api/staff", tags=["Staff"])
def list_staff(office_id: Optional[int] = None, status: str = "all"):
    return handle_response(staff.list_staff.func(office_id=office_id, status=status))

@app.get("/api/staff/{staff_id}", tags=["Staff"])
def get_staff(staff_id: int):
    return handle_response(staff.get_staff_details.func(staff_id=staff_id))

@app.get("/api/offices", tags=["Staff"])
def list_offices():
    return handle_response(staff.list_offices.func())

@app.get("/api/offices/{office_id}", tags=["Staff"])
def get_office(office_id: int):
    return handle_response(staff.get_office_details.func(office_id=office_id))


# --- ACCOUNTING ENDPOINTS ---
@app.get("/api/accounting/gl-accounts", tags=["Accounting"])
def list_gl_accounts(type: Optional[int] = None):
    return handle_response(accounting.list_gl_accounts.func(type=type))

@app.get("/api/accounting/journal-entries", tags=["Accounting"])
def get_journal_entries(account_id: Optional[int] = None, transaction_id: Optional[str] = None):
    return handle_response(accounting.get_journal_entries.func(account_id=account_id, transaction_id=transaction_id))

@app.post("/api/accounting/journal-entries", tags=["Accounting"])
def create_journal_entry(payload: JournalEntryCreate):
    # Convert Pydantic models to dicts for the tool function
    credits_dict = [item.dict() for item in payload.credits]
    debits_dict = [item.dict() for item in payload.debits]
    return handle_response(accounting.create_journal_entry.func(
        office_id=payload.office_id, date=payload.date,
        credits=credits_dict, debits=debits_dict, comment=payload.comment
    ))


# --- SERVER RUNNER ---
if __name__ == "__main__":
    print("\n" + "-"*50)
    print("STARTING HEADLESS MCP SERVER")
    print("-"*50)
    print("API Docs available at: http://localhost:8000/docs")
    uvicorn.run("api_server:app", host="0.0.0.0", port=8000, reload=True)
