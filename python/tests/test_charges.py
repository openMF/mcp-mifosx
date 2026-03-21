# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.charges.fineract_client")
def test_list_charges(mock_client):
    from tools.domains.charges import list_charges

    mock_client.execute_get.return_value = [{"id": 1, "name": "Late Fee"}]
    result = list_charges.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("charges")


@patch("tools.domains.charges.fineract_client")
def test_get_charge(mock_client):
    from tools.domains.charges import get_charge

    mock_client.execute_get.return_value = {"id": 5, "name": "Disbursement Fee"}
    result = get_charge.func(5)
    assert result["name"] == "Disbursement Fee"
    mock_client.execute_get.assert_called_once_with("charges/5")


@patch("tools.domains.charges.fineract_client")
def test_create_charge(mock_client):
    from tools.domains.charges import create_charge

    mock_client.execute_post.return_value = {"resourceId": 3}
    result = create_charge.func("Late Payment", 50.0)
    assert result["resourceId"] == 3
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["name"] == "Late Payment"
    assert call_args[0][1]["amount"] == 50.0
    assert call_args[0][1]["chargeAppliesTo"] == 1  # Loan default


@patch("tools.domains.charges.fineract_client")
def test_update_charge(mock_client):
    from tools.domains.charges import update_charge

    mock_client.execute_put.return_value = {"resourceId": 5}
    result = update_charge.func(5, amount=75.0, is_active=False)
    assert result["resourceId"] == 5
    call_args = mock_client.execute_put.call_args
    assert call_args[0][1]["amount"] == 75.0
    assert call_args[0][1]["active"] is False
