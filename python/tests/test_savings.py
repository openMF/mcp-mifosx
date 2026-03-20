# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.savings.fineract_client")
def test_get_savings_account(mock_client):
    from tools.domains.savings import get_savings_account

    mock_client.execute_get.return_value = {"id": 1, "accountBalance": 5000}
    result = get_savings_account.func(1)
    assert result["id"] == 1
    mock_client.execute_get.assert_called_once_with("savingsaccounts/1")


@patch("tools.domains.savings.fineract_client")
def test_get_savings_transactions(mock_client):
    from tools.domains.savings import get_savings_transactions

    mock_client.execute_get.return_value = {
        "transactions": [{"id": 1, "amount": 100}]
    }
    result = get_savings_transactions.func(1)
    assert len(result) == 1
    assert result[0]["amount"] == 100


@patch("tools.domains.savings.fineract_client")
def test_get_savings_transactions_error(mock_client):
    from tools.domains.savings import get_savings_transactions

    mock_client.execute_get.return_value = {"error": "Not found"}
    result = get_savings_transactions.func(999)
    assert "error" in result


@patch("tools.domains.savings.fineract_client")
def test_create_savings_account(mock_client):
    from tools.domains.savings import create_savings_account

    mock_client.execute_post.return_value = {"resourceId": 10}
    result = create_savings_account.func(1, 2)
    assert result["resourceId"] == 10
    call_args = mock_client.execute_post.call_args
    assert call_args[0][0] == "savingsaccounts"
    assert call_args[0][1]["clientId"] == 1
    assert call_args[0][1]["productId"] == 2


@patch("tools.domains.savings.fineract_client")
def test_approve_and_activate_savings(mock_client):
    from tools.domains.savings import approve_and_activate_savings

    mock_client.execute_post.side_effect = [
        {"resourceId": 1},  # approve
        {"resourceId": 1},  # activate
    ]
    result = approve_and_activate_savings.func(1)
    assert result["resourceId"] == 1
    assert mock_client.execute_post.call_count == 2


@patch("tools.domains.savings.fineract_client")
def test_approve_and_activate_savings_approval_fails(mock_client):
    from tools.domains.savings import approve_and_activate_savings

    mock_client.execute_post.return_value = {"error": "Already approved"}
    result = approve_and_activate_savings.func(1)
    assert "error" in result
    assert mock_client.execute_post.call_count == 1


@patch("tools.domains.savings.fineract_client")
def test_close_savings_account(mock_client):
    from tools.domains.savings import close_savings_account

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = close_savings_account.func(1)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "close" in call_args[0][0]


@patch("tools.domains.savings.fineract_client")
def test_deposit_savings(mock_client):
    from tools.domains.savings import deposit_savings

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = deposit_savings.func(1, 500.0)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "deposit" in call_args[0][0]
    assert call_args[0][1]["transactionAmount"] == 500.0


@patch("tools.domains.savings.fineract_client")
def test_withdraw_savings(mock_client):
    from tools.domains.savings import withdraw_savings

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = withdraw_savings.func(1, 100.0)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "withdrawal" in call_args[0][0]
    assert call_args[0][1]["transactionAmount"] == 100.0


@patch("tools.domains.savings.fineract_client")
def test_apply_savings_charge(mock_client):
    from tools.domains.savings import apply_savings_charge

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = apply_savings_charge.func(1, 15.0)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["chargeId"] == 1
    assert call_args[0][1]["amount"] == 15.0


@patch("tools.domains.savings.fineract_client")
def test_calculate_and_post_interest(mock_client):
    from tools.domains.savings import calculate_and_post_interest

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = calculate_and_post_interest.func(1)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "postInterest" in call_args[0][0]
