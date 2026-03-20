# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.reports.fineract_client")
def test_list_reports(mock_client):
    from tools.domains.reports import list_reports

    mock_client.execute_get.return_value = [{"id": 1, "reportName": "Active Loans"}]
    result = list_reports.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("reports", params={})


@patch("tools.domains.reports.fineract_client")
def test_list_reports_by_type(mock_client):
    from tools.domains.reports import list_reports

    mock_client.execute_get.return_value = []
    list_reports.func(report_type="Table")
    mock_client.execute_get.assert_called_once_with("reports", params={"type": "Table"})


@patch("tools.domains.reports.fineract_client")
def test_get_report(mock_client):
    from tools.domains.reports import get_report

    mock_client.execute_get.return_value = {"id": 5, "reportName": "Loan Summary"}
    result = get_report.func(5)
    assert result["reportName"] == "Loan Summary"
    mock_client.execute_get.assert_called_once_with("reports/5")


@patch("tools.domains.reports.fineract_client")
def test_run_report(mock_client):
    from tools.domains.reports import run_report

    mock_client.execute_get.return_value = {"data": [{"col1": "val1"}]}
    result = run_report.func("Active Loans - Summary", {"officeId": "1"})
    assert "data" in result
    mock_client.execute_get.assert_called_once_with(
        "runreports/Active Loans - Summary", params={"officeId": "1"}
    )


@patch("tools.domains.reports.fineract_client")
def test_create_report(mock_client):
    from tools.domains.reports import create_report

    mock_client.execute_post.return_value = {"resourceId": 10}
    result = create_report.func("My Report", "Table", "SELECT 1")
    assert result["resourceId"] == 10
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["reportName"] == "My Report"
    assert call_args[0][1]["reportType"] == "Table"
    assert call_args[0][1]["reportSql"] == "SELECT 1"


@patch("tools.domains.reports.fineract_client")
def test_update_report(mock_client):
    from tools.domains.reports import update_report

    mock_client.execute_put.return_value = {"resourceId": 5}
    result = update_report.func(5, report_name="Updated Report")
    assert result["resourceId"] == 5
    mock_client.execute_put.assert_called_once_with(
        "reports/5", {"reportName": "Updated Report"}
    )
