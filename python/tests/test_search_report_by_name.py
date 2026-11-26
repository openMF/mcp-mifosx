# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Tests for search_report_by_name MCP tool."""

from typing import Any, Dict, List
from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.mcp_adapter import fineract_client

MOCK_REPORTS: List[Dict[str, Any]] = [
    {
        "id": 1,
        "reportName": "Client Listing",
        "reportType": "Table",
        "reportCategory": "Client",
        "coreReport": True,
        "reportSql": "SELECT * FROM m_client",
        "description": "Lists all clients",
    },
    {
        "id": 2,
        "reportName": "Active Loans - Summary",
        "reportType": "Table",
        "reportCategory": "Loan",
        "coreReport": True,
        "reportSql": "SELECT * FROM m_loan",
    },
    {
        "id": 3,
        "reportName": "Expected Payments By Date",
        "reportType": "Pentaho",
        "reportCategory": "Loan",
        "coreReport": False,
    },
]


def _call_tool(report_name: Any) -> Dict[str, Any]:
    """Call the MCP-registered search_report_by_name tool wrapper."""
    return getattr(mcp_server.search_report_by_name, "fn", mcp_server.search_report_by_name)(reportName=report_name)


def test_search_report_by_name_matches_and_projects_five_fields() -> None:
    """Case-insensitive substring match returns only the five projected fields."""
    with patch.object(fineract_client, "execute_get", return_value=MOCK_REPORTS) as mock_get:
        result = _call_tool("  client ")

    mock_get.assert_called_once_with("reports")
    assert result == {
        "reports": [
            {
                "id": 1,
                "reportName": "Client Listing",
                "reportType": "Table",
                "reportCategory": "Client",
                "coreReport": True,
            }
        ]
    }


def test_search_report_by_name_multiple_matches() -> None:
    """Substring matching across multiple report names returns all matches."""
    with patch.object(fineract_client, "execute_get", return_value=MOCK_REPORTS):
        result = _call_tool("e")

    assert [r["id"] for r in result["reports"]] == [1, 2, 3]


def test_search_report_by_name_no_matches_returns_empty_list() -> None:
    """No matching reports returns an empty reports list."""
    with patch.object(fineract_client, "execute_get", return_value=MOCK_REPORTS):
        result = _call_tool("nonexistent-report")

    assert result == {"reports": []}


@pytest.mark.parametrize("bad_name", ["", "   ", None, 123])
def test_search_report_by_name_rejects_blank_or_invalid_input(bad_name: Any) -> None:
    """Validation rejects blank or non-string reportName before calling Fineract."""
    with patch.object(fineract_client, "execute_get") as mock_get:
        with pytest.raises(ToolError, match="reportName is required and cannot be blank"):
            _call_tool(bad_name)

    mock_get.assert_not_called()


def test_search_report_by_name_propagates_adapter_error() -> None:
    """Backend error dicts from execute_get are converted to ToolError by safe_tool."""
    with patch.object(fineract_client, "execute_get", return_value={"error": "HTTP 500: Server error", "status_code": 500}):
        with pytest.raises(ToolError, match="HTTP 500: Server error"):
            _call_tool("Client")


def test_search_report_by_name_handles_malformed_response_shapes() -> None:
    """Malformed list items or non-string reportName values do not crash filtering."""
    malformed_reports: List[Any] = [
        "not-a-dict",
        {"id": 10, "reportName": None, "reportType": "Table"},
        {"id": 11, "reportName": 500, "reportType": "Table"},
        {"id": 12, "reportName": "Valid Report", "reportType": "Table", "reportCategory": "Client", "coreReport": False},
    ]
    with patch.object(fineract_client, "execute_get", return_value=malformed_reports):
        result = _call_tool("Valid")

    assert result == {
        "reports": [
            {
                "id": 12,
                "reportName": "Valid Report",
                "reportType": "Table",
                "reportCategory": "Client",
                "coreReport": False,
            }
        ]
    }


def test_search_report_by_name_rejects_non_list_backend_payload() -> None:
    """Non-list, non-error backend responses return an error rather than crashing."""
    with patch.object(fineract_client, "execute_get", return_value={"unexpected": "dict"}):
        with pytest.raises(ToolError, match="Unexpected response format from Fineract reports endpoint"):
            _call_tool("Client")
