# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import datetime

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# --- GROUP OPERATIONS ---

@tool
def list_groups(office_id: int = None):
    """Answers: 'Show me all lending groups' or 'List groups in office #1'"""
    endpoint = "groups"
    if office_id:
        endpoint += f"?officeId={office_id}"
    print("[Tool] Fetching Groups...")
    return fineract_client.execute_get(endpoint)

@tool
def get_group(group_id: int):
    """Answers: 'Show me details for group #12'"""
    print(f"[Tool] Fetching details for Group #{group_id}...")
    return fineract_client.execute_get(f"groups/{group_id}?associations=all")

@tool
def create_group(name: str, office_id: int, external_id: str = None):
    """Answers: 'Create a new lending group named "Alpha Group" in office 1'"""
    print(f"[Tool] Creating Group '{name}'...")
    payload = {
        "name": name,
        "officeId": office_id,
        "active": False,
        "externalId": external_id
    }
    return fineract_client.execute_post("groups", payload)

@tool
def activate_group(group_id: int):
    """Answers: 'Activate this pending group'"""
    print(f"[Tool] Activating Group #{group_id}...")
    today = datetime.datetime.now().strftime("%d %B %Y")
    payload = {
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"groups/{group_id}?command=activate", payload)

@tool
def add_group_member(group_id: int, client_id: int):
    """Answers: 'Add client #5 to group #12'"""
    print(f"[Tool] Adding Client #{client_id} to Group #{group_id}...")
    payload = {"clientMembers": [client_id]}
    return fineract_client.execute_post(f"groups/{group_id}?command=associateClients", payload)

# --- CENTER OPERATIONS ---

@tool
def list_centers(office_id: int = None):
    """Answers: 'List all centers'"""
    endpoint = "centers"
    if office_id:
        endpoint += f"?officeId={office_id}"
    print("[Tool] Fetching Centers...")
    return fineract_client.execute_get(endpoint)

@tool
def get_center(center_id: int):
    """Answers: 'Show me details for center #5'"""
    print(f"[Tool] Fetching details for Center #{center_id}...")
    return fineract_client.execute_get(f"centers/{center_id}?associations=groupMembers,collectionSheet")

@tool
def create_center(name: str, office_id: int, external_id: str = None):
    """Answers: 'Create a new center named "Main Center" in office 1'"""
    print(f"[Tool] Creating Center '{name}'...")
    payload = {
        "name": name,
        "officeId": office_id,
        "active": False,
        "externalId": external_id
    }
    return fineract_client.execute_post("centers", payload)
