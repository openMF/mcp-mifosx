# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.groups.fineract_client")
def test_list_groups(mock_client):
    from tools.domains.groups import list_groups

    mock_client.execute_get.return_value = [{"id": 1, "name": "Alpha"}]
    result = list_groups.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("groups")


@patch("tools.domains.groups.fineract_client")
def test_list_groups_with_office(mock_client):
    from tools.domains.groups import list_groups

    mock_client.execute_get.return_value = []
    result = list_groups.func(office_id=1)
    mock_client.execute_get.assert_called_once_with("groups?officeId=1")


@patch("tools.domains.groups.fineract_client")
def test_activate_group(mock_client):
    from tools.domains.groups import activate_group

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = activate_group.func(1)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "activate" in call_args[0][0]


@patch("tools.domains.groups.fineract_client")
def test_add_group_member(mock_client):
    from tools.domains.groups import add_group_member

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = add_group_member.func(1, 5)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "associateClients" in call_args[0][0]
    assert call_args[0][1]["clientMembers"] == [5]


@patch("tools.domains.groups.fineract_client")
def test_list_centers(mock_client):
    from tools.domains.groups import list_centers

    mock_client.execute_get.return_value = [{"id": 1, "name": "Main Center"}]
    result = list_centers.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("centers")


@patch("tools.domains.groups.fineract_client")
def test_get_center(mock_client):
    from tools.domains.groups import get_center

    mock_client.execute_get.return_value = {"id": 5, "name": "Center A"}
    result = get_center.func(5)
    assert result["id"] == 5


@patch("tools.domains.groups.fineract_client")
def test_create_center(mock_client):
    from tools.domains.groups import create_center

    mock_client.execute_post.return_value = {"resourceId": 3}
    result = create_center.func("New Center", 1)
    assert result["resourceId"] == 3
    call_args = mock_client.execute_post.call_args
    assert call_args[0][0] == "centers"
    assert call_args[0][1]["name"] == "New Center"
