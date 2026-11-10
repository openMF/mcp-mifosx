# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.domains.reports import create_report
from tools.mcp_adapter import fineract_client

# ReportCommandFromApiJsonDeserializer.SUPPORTED_PARAMETERS in apache/fineract
SUPPORTED_PARAMETERS = {
    "reportName",
    "reportType",
    "reportSubType",
    "reportCategory",
    "description",
    "reportSql",
    "useReport",
    "reportParameters",
}


def _fineract_post(endpoint: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    """Answer POST /reports the way Fineract does: reject parameters it does not support."""
    unsupported = sorted(set(payload) - SUPPORTED_PARAMETERS)
    if unsupported:
        return {"error": f"The parameter `{unsupported[0]}` is not supported.", "status_code": 400}
    return {"resourceId": 77}


def _create_report_definition(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does."""
    return getattr(mcp_server.create_report_definition, "fn", mcp_server.create_report_definition)(**kwargs)


def test_sends_only_parameters_fineract_supports() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 77}) as post:
        create_report.func("Arrears by office", "Table", "select 1", "Loans in arrears")
    post.assert_called_once_with(
        "reports",
        {
            "reportName": "Arrears by office",
            "reportType": "Table",
            "reportSql": "select 1",
            "description": "Loans in arrears",
            "useReport": True,
        },
    )


def test_create_report_definition_registers_the_report() -> None:
    with patch.object(fineract_client, "execute_post", side_effect=_fineract_post):
        result = _create_report_definition(reportName="Arrears by office", reportType="Table", reportSql="select 1")
    assert result == {"resourceId": 77}


def test_create_report_definition_still_reports_a_rejection() -> None:
    rejected = {"error": "Report name already exists", "status_code": 403}
    with patch.object(fineract_client, "execute_post", return_value=rejected):
        with pytest.raises(ToolError, match="Report name already exists"):
            _create_report_definition(reportName="Arrears by office", reportType="Table", reportSql="select 1")
