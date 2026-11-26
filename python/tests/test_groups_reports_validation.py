# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Validation and payload hygiene tests for groups, centers, reports, and date formatting."""

from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.domains import groups, reports
from tools.mcp_adapter import fineract_client


def test_create_group_and_center_omit_null_external_id_and_trim_fields() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 11}) as post:
        groups.create_group.func("  Alpha Group  ", 2)
    post.assert_called_once_with(
        "groups",
        {"name": "Alpha Group", "officeId": 2, "active": False},
    )

    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 12}) as post:
        groups.create_center.func("  North Center ", 3, "  EXT-9 ")
    post.assert_called_once_with(
        "centers",
        {"name": "North Center", "officeId": 3, "active": False, "externalId": "EXT-9"},
    )


@pytest.mark.parametrize("bad_name", ["", "   "])
def test_create_group_and_center_reject_blank_names(bad_name: str) -> None:
    with patch.object(fineract_client, "execute_post") as post:
        assert "error" in groups.create_group.func(bad_name, 1)
        assert "error" in groups.create_center.func(bad_name, 1)
        with pytest.raises(ToolError, match="name is required"):
            mcp_server.create_lending_group(name=bad_name, officeId=1)
        with pytest.raises(ToolError, match="name is required"):
            mcp_server.create_new_center(name=bad_name, officeId=1)
    post.assert_not_called()


def test_create_group_rejects_blank_external_id_without_posting() -> None:
    with patch.object(fineract_client, "execute_post") as post:
        assert "error" in groups.create_group.func("Alpha", 1, external_id="   ")
        assert "error" in groups.create_center.func("Center", 1, external_id="")
    post.assert_not_called()


def test_create_and_update_report_validate_inputs_and_block_empty_put() -> None:
    with patch.object(fineract_client, "execute_post") as post:
        assert "error" in reports.create_report.func("  ", "Table", "select 1")
        assert "error" in reports.create_report.func("Test", "InvalidType", "select 1")
        assert "error" in reports.create_report.func("Test", "Table", "   ")
        with pytest.raises(ToolError, match="reportSql is required"):
            mcp_server.create_report_definition(reportName="Test", reportType="Table", reportSql="   ")
    post.assert_not_called()

    with patch.object(fineract_client, "execute_put") as put:
        result = reports.update_report.func(5)
        assert "at least one field" in result["error"]
        with pytest.raises(ToolError, match="at least one field"):
            mcp_server.update_report_definition(reportId=5)
        assert "Invalid report_type" in reports.update_report.func(5, report_type="Bogus")["error"]
    put.assert_not_called()


def test_update_report_normalizes_type_and_posts_only_given_fields() -> None:
    with patch.object(fineract_client, "execute_put", return_value={"resourceId": 5}) as put:
        assert reports.update_report.func(5, report_name="  New Name ", report_type="chart") == {"resourceId": 5}
    put.assert_called_once_with("reports/5", {"reportName": "New Name", "reportType": "Chart"})


def test_fmt_date_handles_out_of_range_month_gracefully() -> None:
    assert mcp_server._fmt_date([2026, 3, 15]) == "15 March 2026"
    assert mcp_server._fmt_date([2026, 0, 15]) == [2026, 0, 15]
    assert mcp_server._fmt_date([2026, 13, 15]) == [2026, 13, 15]
