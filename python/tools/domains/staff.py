# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

@tool
def list_staff(office_id: int = None, status: str = "all"):
    """Answers: 'Who are the staff members?' or 'List active staff in office 1'"""
    endpoint = "staff"
    params = []
    if office_id: params.append(f"officeId={office_id}")
    if status: params.append(f"status={status}")
    if params: endpoint += "?" + "&".join(params)
    
    print(f"[Tool] Fetching Staff...")
    return fineract_client.execute_get(endpoint)

@tool
def get_staff_details(staff_id: int):
    """Answers: 'Show me technical details for staff member #5'"""
    print(f"[Tool] Fetching Staff #{staff_id}...")
    return fineract_client.execute_get(f"staff/{staff_id}")

@tool
def list_offices():
    """Answers: 'Show me all bank branches/offices'"""
    print(f"[Tool] Fetching Offices...")
    return fineract_client.execute_get("offices")

@tool
def get_office_details(office_id: int):
    """Answers: 'Show me details for office #1'"""
    print(f"[Tool] Fetching Office #{office_id}...")
    return fineract_client.execute_get(f"offices/{office_id}")
