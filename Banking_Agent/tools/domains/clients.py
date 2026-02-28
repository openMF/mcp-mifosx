import datetime
from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

# ==========================================
# 🔍 1. CLIENT SEARCH & READ OPERATIONS
# ==========================================

@tool
def search_clients_by_name(name_query: str):
    """Answers: 'Find the client ID for John Doe' or 'Search for clients named Maria'"""
    print(f"📡 [Tool] Searching for clients matching: '{name_query}'...")
    # Fineract's global search endpoint, filtered strictly to clients
    return fineract_client.execute_get(f"search?query={name_query}&resource=clients&exactMatch=false")

@tool
def get_client_details(client_id: int):
    """Answers: 'Who is this client?' or 'Show me details for client ID 844'"""
    print(f"📡 [Tool] Fetching details for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}")

@tool
def get_client_accounts(client_id: int):
    """Answers: 'Show me all loans and savings accounts for this client'"""
    print(f"📡 [Tool] Fetching all accounts for Client #{client_id}...")
    return fineract_client.execute_get(f"clients/{client_id}/accounts")


# ==========================================
# ✍️ 2. CLIENT LIFECYCLE OPERATIONS (WRITE)
# ==========================================

@tool
def create_client(firstname: str, lastname: str, mobile_no: str = None, office_id: int = 1, is_active: bool = True):
    """Answers: 'Create a new client named John Doe'"""
    print(f"📡 [Tool] Creating client: {firstname} {lastname}...")
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
    print(f"📡 [Tool] Activating Client #{client_id}...")
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
    print(f"📡 [Tool] Updating mobile number for Client #{client_id}...")
    payload = {
        "mobileNo": new_mobile_no
    }
    # Notice this uses PUT instead of POST for updates
    return fineract_client.execute_put(f"clients/{client_id}", payload)

@tool
def close_client(client_id: int, closure_reason_id: int = 17):
    """Answers: 'Close this client's profile, they are leaving the bank'"""
    print(f"📡 [Tool] Closing Client #{client_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")

    payload = {
        "closureDate": today,
        "closureReasonId": closure_reason_id,  # Requires a valid code table ID from Fineract
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"clients/{client_id}?command=close", payload)


# ==========================================
# 🏢 3. GROUP & CENTER OPERATIONS
# ==========================================

@tool
def create_group(name: str, office_id: int = 1, client_members: list = None):
    """Answers: 'Create a new lending group called The Innovators'"""
    print(f"📡 [Tool] Creating Group: {name}...")
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
    print(f"📡 [Tool] Fetching details for Group #{group_id}...")
    # Using associations=clientMembers to fetch all the people inside the group
    return fineract_client.execute_get(f"groups/{group_id}?associations=clientMembers")