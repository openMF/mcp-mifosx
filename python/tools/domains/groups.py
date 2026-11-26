# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.


import logging

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client
from tools.utils import get_fineract_today

logger = logging.getLogger(__name__)

# --- GROUP OPERATIONS ---

@tool
def list_groups(office_id: int = None):
    """Answers: 'Show me all lending groups' or 'List groups in office #1'"""
    endpoint = "groups"
    if office_id:
        endpoint += f"?officeId={office_id}"
    logger.info("[Tool] Fetching Groups...")
    return fineract_client.execute_get(endpoint)

@tool
def get_group(group_id: int):
    """Answers: 'Show me details for group #12'"""
    logger.info(f"[Tool] Fetching details for Group #{group_id}...")
    return fineract_client.execute_get(f"groups/{group_id}?associations=all")

def _build_entity_payload(entity_label: str, name: str, office_id: int, external_id: str = None) -> dict:
    """Validate and build the Fineract POST payload for creating a group or center."""
    if not isinstance(name, str) or not name.strip():
        return {"error": f"{entity_label} name is required and cannot be blank."}
    if isinstance(office_id, bool) or not isinstance(office_id, int) or office_id <= 0:
        return {"error": f"Invalid office_id: {office_id}. Must be a positive integer."}
    payload = {
        "name": name.strip(),
        "officeId": office_id,
        "active": False,
    }
    if external_id is not None:
        if not isinstance(external_id, str) or not external_id.strip():
            return {"error": "external_id cannot be blank when provided."}
        payload["externalId"] = external_id.strip()
    return payload


@tool
def create_group(name: str, office_id: int, external_id: str = None):
    """Answers: 'Create a new lending group named "Alpha Group" in office 1'"""
    logger.info(f"[Tool] Creating Group '{name}'...")
    payload = _build_entity_payload("Group", name, office_id, external_id)
    if "error" in payload:
        return payload
    return fineract_client.execute_post("groups", payload)

@tool
def activate_group(group_id: int):
    """Answers: 'Activate this pending group'"""
    logger.info(f"[Tool] Activating Group #{group_id}...")
    today = get_fineract_today()
    payload = {
        "activationDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en"
    }
    return fineract_client.execute_post(f"groups/{group_id}?command=activate", payload)

@tool
def add_group_member(group_id: int, client_id: int):
    """Answers: 'Add client #5 to group #12'"""
    logger.info(f"[Tool] Adding Client #{client_id} to Group #{group_id}...")
    payload = {"clientMembers": [client_id]}
    return fineract_client.execute_post(f"groups/{group_id}?command=associateClients", payload)

# --- CENTER OPERATIONS ---

@tool
def list_centers(office_id: int = None):
    """Answers: 'List all centers'"""
    endpoint = "centers"
    if office_id:
        endpoint += f"?officeId={office_id}"
    logger.info("[Tool] Fetching Centers...")
    return fineract_client.execute_get(endpoint)

@tool
def get_center(center_id: int):
    """Answers: 'Show me details for center #5'"""
    logger.info(f"[Tool] Fetching details for Center #{center_id}...")
    return fineract_client.execute_get(f"centers/{center_id}?associations=groupMembers,collectionMeetingCalendar")

@tool
def create_center(name: str, office_id: int, external_id: str = None):
    """Answers: 'Create a new center named "Main Center" in office 1'"""
    logger.info(f"[Tool] Creating Center '{name}'...")
    payload = _build_entity_payload("Center", name, office_id, external_id)
    if "error" in payload:
        return payload
    return fineract_client.execute_post("centers", payload)
