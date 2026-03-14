# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import datetime
from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

# --- CLIENT SEARCH & READ OPERATIONS ---

@tool
def search_clients_by_name(name_query: str):
    """Answers: 'Find the client ID for John Doe' or 'Search for clients named Maria'"""
    print(f"[Tool] Searching for clients matching: '{name_query}'...")
    # Fineract's global search endpoint, filtered strictly to clients
    return fineract_client.execute_get(f"search?query={name_query}&resource=clients&exactMatch=false")

@tool
def get_client_details(client_id: int):
    """Answers: 'Who is this client?' or 'Show me details for client ID 844'"""
    print(f"[Tool] Fetching details for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}")

@tool
def get_client_accounts(client_id: int):
    """Answers: 'Show me all loans and savings accounts for this client'"""
    print(f"[Tool] Fetching all accounts for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/accounts")


# --- CLIENT LIFECYCLE OPERATIONS (WRITE) ---

@tool
def create_client(firstname: str, lastname: str, mobile_no: str = None, office_id: int = 1, is_active: bool = True):
    """Answers: 'Create a new client named John Doe'"""
    print(f"[Tool] Creating client: {firstname} {lastname}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "officeId": office_id,
        "firstname": firstname,
        "lastname": lastname,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "legalFormId": 1
    }

    if is_active is True or str(is_active).lower() == 'true':
        payload["activationDate"] = today
        payload["active"] = True
    else:
        payload["active"] = False
    if mobile_no:
        payload["mobileNo"] = mobile_no

    return fineract_client.execute_post("clients", payload)

@tool
def activate_client(client_id: int):
    """Answers: 'Activate this pending client profile'"""
    print(f"[Tool] Activating Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"clients/{client_id}?command=activate", payload)

@tool
def update_client_mobile(client_id: int, new_mobile_no: str):
    """Answers: 'Update this client's phone number to 555-0199'"""
    print(f"[Tool] Updating mobile number for Client #{client_id}...")
    payload = {
        "mobileNo": new_mobile_no
    }
    # Notice this uses PUT instead of POST for updates
    return fineract_client.execute_put(f"clients/{client_id}", payload)

@tool
def close_client(client_id: int, closure_reason_id: int = 17):
    """Answers: 'Close this client's profile, they are leaving the bank'"""
    print(f"[Tool] Closing Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "closureDate": today,
        "closureReasonId": closure_reason_id,  # Requires a valid code table ID from Fineract
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"clients/{client_id}?command=close", payload)


# --- GROUP & CENTER OPERATIONS ---

@tool
def create_group(name: str, office_id: int = 1, client_members: list = None):
    """Answers: 'Create a new lending group called The Innovators'"""
    print(f"[Tool] Creating Group: {name}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "officeId": office_id,
        "name": name,
        "active": True,
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }

    if client_members:
        # Fineract expects a list of client IDs to attach to the group
        payload["clientMembers"] = client_members

    return fineract_client.execute_post("groups", payload)

@tool
def get_group_details(group_id: int):
    """Answers: 'Show me details and members of group ID 5'"""
    print(f"[Tool] Fetching details for Group #{group_id}...")
    # Using associations=clientMembers to fetch all the people inside the group
    return fineract_client.execute_get(f"groups/{group_id}?associations=clientMembers")


# --- CLIENT SUB-RESOURCES (KYC & FINANCES) ---

@tool
def get_client_identifiers(client_id: int):
    """Answers: 'What ID documents does this client have on file?' or 'Show me the passport number for client 844'"""
    print(f"[Tool] Fetching identifiers for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/identifiers")

@tool
def create_client_identifier(client_id: int, document_type_id: int, document_key: str):
    """Answers: 'Add a new social security number for this client'"""
    print(f"[Tool] Creating identifier for Client #{client_id}...")
    payload = {
        "documentTypeId": document_type_id,
        "documentKey": document_key,
        "description": "Added via AI Agent"
    }
    return fineract_client.execute_post(f"clients/{client_id}/identifiers", payload)

@tool
def get_client_documents(client_id: int):
    """Answers: 'Show me all files uploaded for this client'"""
    print(f"[Tool] Fetching documents/files for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/documents")

@tool
def get_client_charges(client_id: int):
    """Answers: 'Are there any penalties or fees on this client's profile?'"""
    print(f"[Tool] Fetching charges for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/charges")

@tool
def apply_client_charge(client_id: int, charge_id: int, amount: float):
    """Answers: 'Apply a one-time onboarding fee of $50 to this client'"""
    print(f"[Tool] Applying charge {charge_id} to Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")
    payload = {
        "chargeId": charge_id,
        "amount": amount,
        "dueDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"clients/{client_id}/charges", payload)

@tool
def get_client_transactions(client_id: int):
    """Answers: 'Show me all financial transactions linked to this client'"""
    print(f"[Tool] Fetching financial transactions for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/transactions")

@tool
def get_client_addresses(client_id: int):
    """Answers: 'Where does this client live?' or 'Show me the home address for client 844'"""
    print(f"[Tool] Fetching addresses for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/addresses")