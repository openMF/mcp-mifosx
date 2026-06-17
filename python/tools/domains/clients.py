# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from typing import Optional

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client
from tools.utils import get_fineract_today

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
    today = get_fineract_today()

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
    today = get_fineract_today()

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
    today = get_fineract_today()

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
    today = get_fineract_today()

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
    today = get_fineract_today()
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

@tool
def update_client(
    client_id: int,
    firstname: Optional[str] = None,
    lastname: Optional[str] = None,
    mobile_no: Optional[str] = None,
    external_id: Optional[str] = None,
):
    """Answers: 'Update client #5 firstname to John' or 'Change mobile number for client 123'"""
    print(f"[Tool] Updating Client #{client_id}...")

    # Validate client_id
    if not isinstance(client_id, int) or client_id <= 0:
        return {"error": f"Invalid client_id: {client_id}. Must be a positive integer."}

    # Validate string inputs are non-empty if provided
    if firstname is not None and (not isinstance(firstname, str) or firstname.strip() == ""):
        return {"error": "firstname must be a non-empty string if provided."}
    if lastname is not None and (not isinstance(lastname, str) or lastname.strip() == ""):
        return {"error": "lastname must be a non-empty string if provided."}
    if mobile_no is not None and (not isinstance(mobile_no, str) or mobile_no.strip() == ""):
        return {"error": "mobile_no must be a non-empty string if provided."}
    if external_id is not None and (not isinstance(external_id, str) or external_id.strip() == ""):
        return {"error": "external_id must be a non-empty string if provided."}

    # Fetch current state to get mandatory fields
    current = fineract_client.execute_get(f"clients/{client_id}")
    if "error" in current:
        return current

    # Build payload with existing values as defaults
    payload = {
        "firstname": firstname.strip() if firstname else current.get("firstname"),
        "lastname": lastname.strip() if lastname else current.get("lastname"),
        "mobileNo": mobile_no.strip() if mobile_no else current.get("mobileNo"),
        "externalId": external_id.strip() if external_id else current.get("externalId"),
        "locale": "en",
        "dateFormat": "dd MMMM yyyy",
    }

    # Remove None values
    payload = {k: v for k, v in payload.items() if v is not None}

    # Ensure payload is not empty (only locale and dateFormat)
    if len(payload) <= 2:
        return {"error": "No valid fields to update. Provide at least one: firstname, lastname, mobile_no, external_id."}

    return fineract_client.execute_put(f"clients/{client_id}", payload)

@tool
def delete_client(client_id: int):
    """Answers: 'Delete client #123' or 'Remove client profile'"""
    # Validate client_id
    if not isinstance(client_id, int) or client_id <= 0:
        return {"error": f"Invalid client_id: {client_id}. Must be a positive integer."}

    # Check client exists and get status
    check = fineract_client.execute_get(f"clients/{client_id}")
    if "error" in check:
        return check

    # Validate client is in deletable state (pending only)
    status = check.get("status", {}).get("value", "").lower()
    if "pending" not in status and "closed" not in status:
        return {"error": f"Client {client_id} is in status '{status}'. Only pending/closed clients can be deleted."}

    return fineract_client.execute_delete(f"clients/{client_id}")
