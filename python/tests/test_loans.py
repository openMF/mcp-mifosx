# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.loans.fineract_client")
def test_get_loan_details(mock_client):
    from tools.domains.loans import get_loan_details

    mock_client.execute_get.return_value = {"id": 1, "status": {"value": "Active"}}
    result = get_loan_details.func(1)
    assert result["id"] == 1
    mock_client.execute_get.assert_called_once_with("loans/1")


@patch("tools.domains.loans.fineract_client")
def test_get_repayment_schedule(mock_client):
    from tools.domains.loans import get_repayment_schedule

    mock_client.execute_get.return_value = {
        "repaymentSchedule": {"periods": [{"period": 1, "totalDueForPeriod": 100}]}
    }
    result = get_repayment_schedule.func(1)
    assert "periods" in result


@patch("tools.domains.loans.fineract_client")
def test_get_repayment_schedule_error(mock_client):
    from tools.domains.loans import get_repayment_schedule

    mock_client.execute_get.return_value = {"error": "Not found"}
    result = get_repayment_schedule.func(999)
    assert "error" in result


@patch("tools.domains.loans.fineract_client")
def test_get_loan_history(mock_client):
    from tools.domains.loans import get_loan_history

    mock_client.execute_get.return_value = {
        "status": {"value": "Active"},
        "summary": {"totalRepaymentTransaction": 500, "totalOutstanding": 1500, "totalOverdue": 0},
        "timeline": {"actualDisbursementDate": [2026, 1, 15]},
        "transactions": [
            {"id": 1, "type": {"value": "Disbursement"}, "date": [2026, 1, 15], "amount": 2000, "reversed": False},
            {"id": 2, "type": {"value": "Repayment"}, "date": [2026, 2, 15], "amount": 500, "reversed": False},
            {"id": 3, "type": {"value": "Voided"}, "date": [2026, 2, 16], "amount": 100, "reversed": True},
        ],
        "charges": [],
        "accountNo": "000001",
        "principal": 2000,
        "interestRatePerPeriod": 5,
        "termFrequency": 12,
        "loanProductName": "SILVER",
    }
    result = get_loan_history.func(1)
    assert result["transactionCount"] == 2  # reversed transaction excluded
    assert result["status"] == "Active"


@patch("tools.domains.loans.fineract_client")
def test_get_overdue_loans(mock_client):
    from tools.domains.loans import get_overdue_loans

    mock_client.execute_get.return_value = [
        {"id": 1, "accountNo": "001", "status": {"value": "Active"}, "inArrears": True,
         "summary": {"totalOverdue": 200, "principalOutstanding": 1000}},
        {"id": 2, "accountNo": "002", "status": {"value": "Active"}, "inArrears": False,
         "summary": {"totalOverdue": 0, "principalOutstanding": 500}},
    ]
    result = get_overdue_loans.func(1)
    assert result["count"] == 1
    assert result["overdueLoans"][0]["loanId"] == 1


@patch("tools.domains.loans.fineract_client")
def test_create_loan(mock_client):
    from tools.domains.loans import create_loan

    mock_client.execute_post.return_value = {"resourceId": 10}
    result = create_loan.func(1, 5000.0, 12, 1)
    assert result["resourceId"] == 10
    mock_client.execute_post.assert_called_once()
    call_args = mock_client.execute_post.call_args
    assert call_args[0][0] == "loans"
    assert call_args[0][1]["clientId"] == 1
    assert call_args[0][1]["principal"] == "5000.0"


@patch("tools.domains.loans.fineract_client")
def test_approve_and_disburse_loan(mock_client):
    from tools.domains.loans import approve_and_disburse_loan

    mock_client.execute_post.side_effect = [
        {"resourceId": 1},  # approve
        {"resourceId": 1},  # disburse
    ]
    result = approve_and_disburse_loan.func(1)
    assert result["resourceId"] == 1
    assert mock_client.execute_post.call_count == 2


@patch("tools.domains.loans.fineract_client")
def test_approve_and_disburse_loan_approval_fails(mock_client):
    from tools.domains.loans import approve_and_disburse_loan

    mock_client.execute_post.return_value = {"error": "Already approved"}
    result = approve_and_disburse_loan.func(1)
    assert "error" in result
    assert mock_client.execute_post.call_count == 1  # should not attempt disburse


@patch("tools.domains.loans.fineract_client")
def test_reject_loan_application(mock_client):
    from tools.domains.loans import reject_loan_application

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = reject_loan_application.func(1, "Bad credit")
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "reject" in call_args[0][0]
    assert call_args[0][1]["note"] == "Bad credit"


@patch("tools.domains.loans.fineract_client")
def test_make_loan_repayment(mock_client):
    from tools.domains.loans import make_loan_repayment

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = make_loan_repayment.func(1, 150.0)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "repayment" in call_args[0][0]
    assert call_args[0][1]["transactionAmount"] == 150.0
