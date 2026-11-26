# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import logging
from urllib.parse import quote

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

logger = logging.getLogger(__name__)

# --- REPORT READ OPERATIONS ---

@tool
def list_reports(report_type: str = None):
    """Answers: 'What reports are available in Fineract?' or 'Show me all loan reports'.
    report_type options: 'Table', 'Chart', 'SMS', 'Text', 'Pentaho' (or None for all)."""
    logger.info("[Tool] Fetching all report definitions...")
    # Fineract's GET /reports has no type filter (it ignores ?type=), so filter here.
    reports = fineract_client.execute_get("reports")
    if not report_type or not isinstance(reports, list):
        return reports
    wanted = report_type.strip().lower()
    return [r for r in reports if str(r.get("reportType", "")).lower() == wanted]


@tool
def get_report(report_id: int):
    """Answers: 'Show me the definition for report #5' or 'What parameters does this report need?'"""
    logger.info(f"[Tool] Fetching report definition #{report_id}...")
    return fineract_client.execute_get(f"reports/{report_id}")


@tool
def run_report(report_name: str, params: dict = None):
    """Answers: 'Run the Active Loans Summary report' or 'Generate a client portfolio report'.
    Pass report_name exactly as it appears in Fineract (e.g. 'Active Loans - Summary').
    Pass any required parameters as a dict, e.g. {'officeId': '1', 'currencyId': 'USD'};
    the R_ prefix Fineract expects is added for you."""
    logger.info(f"[Tool] Running report: '{report_name}'...")
    # Fineract only reads report parameters whose names start with R_ (for example
    # R_officeId) and ignores the rest, so add the prefix where it is missing.
    query_params = {
        (key if key.startswith("R_") else f"R_{key}"): value
        for key, value in (params or {}).items()
    }
    # Report names contain spaces and can contain '/', so encode the path segment.
    # exportCSV=true returns raw CSV; omit it to get JSON.
    return fineract_client.execute_get(f"runreports/{quote(report_name, safe='')}", params=query_params)


VALID_REPORT_TYPES = ("Table", "Chart", "SMS", "Text", "Pentaho")
_REPORT_TYPES_BY_LOWER = {rt.lower(): rt for rt in VALID_REPORT_TYPES}


def _normalize_report_type(report_type: str) -> str:
    if not isinstance(report_type, str) or not report_type.strip():
        return ""
    return _REPORT_TYPES_BY_LOWER.get(report_type.strip().lower(), "")


# --- REPORT WRITE OPERATIONS ---

@tool
def create_report(
    report_name: str,
    report_type: str,
    report_sql: str,
    description: str = "",
    use_report: bool = True,
):
    """Answers: 'Register this report template in Fineract' or 'Create a new SQL report definition'.
    report_type: 'Table' | 'Chart' | 'SMS' | 'Text' | 'Pentaho'."""
    if not isinstance(report_name, str) or not report_name.strip():
        return {"error": "report_name is required and cannot be blank."}
    normalized_type = _normalize_report_type(report_type)
    if not normalized_type:
        return {"error": f"Invalid report_type: {report_type!r}. Must be one of: {', '.join(VALID_REPORT_TYPES)}."}
    if not isinstance(report_sql, str) or not report_sql.strip():
        return {"error": "report_sql is required and cannot be blank."}
    logger.info(f"[Tool] Creating report: '{report_name.strip()}'...")
    payload = {
        "reportName": report_name.strip(),
        "reportType": normalized_type,
        "reportSql": report_sql,
        "description": description if description is not None else "",
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
    if not isinstance(report_id, int) or isinstance(report_id, bool) or report_id <= 0:
        return {"error": f"Invalid report_id: {report_id}. Must be a positive integer."}
    logger.info(f"[Tool] Updating report #{report_id}...")
    payload = {}
    if report_name is not None:
        if not isinstance(report_name, str) or not report_name.strip():
            return {"error": "report_name cannot be blank when provided."}
        payload["reportName"] = report_name.strip()
    if report_type is not None:
        normalized_type = _normalize_report_type(report_type)
        if not normalized_type:
            return {"error": f"Invalid report_type: {report_type!r}. Must be one of: {', '.join(VALID_REPORT_TYPES)}."}
        payload["reportType"] = normalized_type
    if report_sql is not None:
        if not isinstance(report_sql, str) or not report_sql.strip():
            return {"error": "report_sql cannot be blank when provided."}
        payload["reportSql"] = report_sql
    if description is not None:
        payload["description"] = description
    if not payload:
        return {"error": "Provide at least one field to update: report_name, report_type, report_sql, or description."}
    return fineract_client.execute_put(f"reports/{report_id}", payload)
