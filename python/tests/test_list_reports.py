# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

from tools.domains.reports import list_reports
from tools.mcp_adapter import fineract_client

REPORTS = [
    {"id": 1, "reportName": "Client Listing", "reportType": "Table"},
    {"id": 2, "reportName": "Portfolio at Risk", "reportType": "Chart"},
    {"id": 3, "reportName": "Expected Payments", "reportType": "Pentaho"},
]


def test_returns_only_reports_of_the_requested_type() -> None:
    with patch.object(fineract_client, "execute_get", return_value=REPORTS) as get:
        result = list_reports.func("Chart")
    get.assert_called_once_with("reports")
    assert [r["id"] for r in result] == [2]


def test_type_match_ignores_case() -> None:
    with patch.object(fineract_client, "execute_get", return_value=REPORTS):
        result = list_reports.func(" pentaho ")
    assert [r["id"] for r in result] == [3]


def test_returns_every_report_without_a_type() -> None:
    with patch.object(fineract_client, "execute_get", return_value=REPORTS):
        assert list_reports.func() == REPORTS


def test_passes_errors_through() -> None:
    error = {"error": "HTTP 500"}
    with patch.object(fineract_client, "execute_get", return_value=error):
        assert list_reports.func("Table") == error
