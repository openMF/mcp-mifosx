# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from urllib.parse import quote

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# --- REPORT READ OPERATIONS ---

@tool
def list_reports(report_type: str = None):
    """Answers: 'What reports are available in Fineract?' or 'Show me all loan reports'.
    report_type options: 'Table', 'Chart', 'SMS', 'Text', 'Pentaho' (or None for all)."""
    print("[Tool] Fetching all report definitions...")
    params = {}
    if report_type:
        params["type"] = report_type
    return fineract_client.execute_get("reports", params=params)


@tool
def get_report(report_id: int):
    """Answers: 'Show me the definition for report #5' or 'What parameters does this report need?'"""
    print(f"[Tool] Fetching report definition #{report_id}...")
    return fineract_client.execute_get(f"reports/{report_id}")


@tool
def run_report(report_name: str, params: dict = None):
    """Answers: 'Run the Active Loans Summary report' or 'Generate a client portfolio report'.
    Pass report_name exactly as it appears in Fineract (e.g. 'Active Loans - Summary').
    Pass any required parameters as a dict, e.g. {'officeId': '1', 'currencyId': 'USD'};
    the R_ prefix Fineract expects is added for you."""
    print(f"[Tool] Running report: '{report_name}'...")
    # Fineract only reads report parameters whose names start with R_ (for example
    # R_officeId) and ignores the rest, so add the prefix where it is missing.
    query_params = {
        (key if key.startswith("R_") else f"R_{key}"): value
        for key, value in (params or {}).items()
    }
    # Report names contain spaces and can contain '/', so encode the path segment.
    # exportCSV=true returns raw CSV; omit it to get JSON.
    return fineract_client.execute_get(f"runreports/{quote(report_name, safe='')}", params=query_params)


# --- REPORT WRITE OPERATIONS ---

@tool
def create_report(
    report_name: str,
    report_type: str,
    report_sql: str,
    description: str = "",
    core_report: bool = False,
    use_report: bool = True,
):
    """Answers: 'Register this report template in Fineract' or 'Create a new SQL report definition'.
    report_type: 'Table' | 'Chart' | 'SMS' | 'Text' | 'Pentaho'."""
    print(f"[Tool] Creating report: '{report_name}'...")
    payload = {
        "reportName": report_name,
        "reportType": report_type,
        "reportSql": report_sql,
        "description": description,
        "coreReport": core_report,
        "useReport": use_report,
    }
    return fineract_client.execute_post("reports", payload)


@tool
def update_report(
    report_id: int,
    report_name: str = None,
    report_type: str = None,
    report_sql: str = None,
    description: str = None,
):
    """Answers: 'Update the SQL for report #5' or 'Rename this report'.
    Only the fields you pass will be changed."""
    print(f"[Tool] Updating report #{report_id}...")
    payload = {}
    if report_name is not None:
        payload["reportName"] = report_name
    if report_type is not None:
        payload["reportType"] = report_type
    if report_sql is not None:
        payload["reportSql"] = report_sql
    if description is not None:
        payload["description"] = description
    return fineract_client.execute_put(f"reports/{report_id}", payload)
