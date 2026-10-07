# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Reports domain — query and run Fineract reports.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import safe_result


def list_reports(
    client: FineractClient, report_type: str | None = None
) -> dict[str, Any]:
    """List all Fineract report definitions.

    Parameters
    ----------
    report_type : str, optional
        Filter by type: ``"Table"`` | ``"Chart"`` | ``"SMS"`` | ``"Text"`` | ``"Pentaho"``.
    """
    try:
        result = client.get("reports")
        if report_type and isinstance(result, list):
            result = [r for r in result if r.get("reportType") == report_type]
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_report(client: FineractClient, report_id: int) -> dict[str, Any]:
    """Get the full definition for a specific report.

    Parameters
    ----------
    report_id : int
        Report definition ID.
    """
    try:
        return safe_result(client.get(f"reports/{report_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def run_report(
    client: FineractClient,
    report_name: str,
    params: dict[str, str] | None = None,
) -> dict[str, Any]:
    """Run a Fineract report by name and return the results.

    Parameters
    ----------
    report_name : str
        Exact report name (e.g. ``"Active Loans - Summary"``).
    params : dict, optional
        Report parameters. Keys must use the required Fineract ``R_`` prefix (e.g. ``{"R_officeId": "1"}``).
    """
    try:
        endpoint = f"runreports/{report_name}"
        result = client.get(endpoint, params=params)
        if isinstance(result, str):
            return safe_result({"content": result})
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_report(
    client: FineractClient,
    report_name: str,
    report_type: str,
    report_sql: str,
    description: str = "",
) -> dict[str, Any]:
    """Register a new report definition in Fineract.

    Parameters
    ----------
    report_name : str
        Display name.
    report_type : str
        ``"Table"`` | ``"Chart"`` | ``"SMS"`` | ``"Text"`` | ``"Pentaho"``.
    report_sql : str
        The SQL query for the report.
    description : str
        Optional description.
    """
    payload = {
        "reportName": report_name,
        "reportType": report_type,
        "reportSql": report_sql,
        "description": description,
    }
    try:
        result = client.post("reports", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def update_report(
    client: FineractClient,
    report_id: int,
    *,
    report_name: str | None = None,
    report_type: str | None = None,
    report_sql: str | None = None,
    description: str | None = None,
) -> dict[str, Any]:
    """Update an existing report definition.

    Only the provided fields are changed.
    """
    payload: dict[str, Any] = {}
    if report_name is not None:
        payload["reportName"] = report_name
    if report_type is not None:
        payload["reportType"] = report_type
    if report_sql is not None:
        payload["reportSql"] = report_sql
    if description is not None:
        payload["description"] = description

    if not payload:
        return safe_result(None, error="No fields to update.")

    try:
        result = client.put(f"reports/{report_id}", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))
