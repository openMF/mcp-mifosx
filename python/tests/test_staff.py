# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.staff.fineract_client")
def test_list_staff(mock_client):
    from tools.domains.staff import list_staff

    mock_client.execute_get.return_value = [{"id": 1, "displayName": "Admin"}]
    result = list_staff.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("staff?status=all")


@patch("tools.domains.staff.fineract_client")
def test_list_staff_with_office(mock_client):
    from tools.domains.staff import list_staff

    mock_client.execute_get.return_value = [{"id": 1}]
    result = list_staff.func(office_id=1, status="active")
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("staff?officeId=1&status=active")


@patch("tools.domains.staff.fineract_client")
def test_get_staff_details(mock_client):
    from tools.domains.staff import get_staff_details

    mock_client.execute_get.return_value = {"id": 5, "displayName": "Officer"}
    result = get_staff_details.func(5)
    assert result["id"] == 5
    mock_client.execute_get.assert_called_once_with("staff/5")


@patch("tools.domains.staff.fineract_client")
def test_list_offices(mock_client):
    from tools.domains.staff import list_offices

    mock_client.execute_get.return_value = [{"id": 1, "name": "Head Office"}]
    result = list_offices.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("offices")


@patch("tools.domains.staff.fineract_client")
def test_get_office_details(mock_client):
    from tools.domains.staff import get_office_details

    mock_client.execute_get.return_value = {"id": 1, "name": "Head Office"}
    result = get_office_details.func(1)
    assert result["name"] == "Head Office"
    mock_client.execute_get.assert_called_once_with("offices/1")
