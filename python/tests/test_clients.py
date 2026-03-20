# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.clients.fineract_client")
def test_search_clients_by_name(mock_client):
    from tools.domains.clients import search_clients_by_name

    mock_client.execute_get.return_value = [{"entityId": 1, "entityName": "John Doe"}]
    result = search_clients_by_name.func("John")
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with(
        "search?query=John&resource=clients&exactMatch=false"
    )


@patch("tools.domains.clients.fineract_client")
def test_get_client_details(mock_client):
    from tools.domains.clients import get_client_details

    mock_client.execute_get.return_value = {"id": 1, "firstname": "John"}
    result = get_client_details.func(1)
    assert result["id"] == 1
    mock_client.execute_get.assert_called_once_with("clients/1")


@patch("tools.domains.clients.fineract_client")
def test_get_client_accounts(mock_client):
    from tools.domains.clients import get_client_accounts

    mock_client.execute_get.return_value = {"loanAccounts": [], "savingsAccounts": []}
    result = get_client_accounts.func(1)
    assert "loanAccounts" in result
    mock_client.execute_get.assert_called_once_with("clients/1/accounts")


@patch("tools.domains.clients.fineract_client")
def test_create_client(mock_client):
    from tools.domains.clients import create_client

    mock_client.execute_post.return_value = {"resourceId": 5}
    result = create_client.func("John", "Doe", "555-0199")
    assert result["resourceId"] == 5
    call_args = mock_client.execute_post.call_args
    assert call_args[0][0] == "clients"
    assert call_args[0][1]["firstname"] == "John"
    assert call_args[0][1]["lastname"] == "Doe"
    assert call_args[0][1]["mobileNo"] == "555-0199"
    assert call_args[0][1]["active"] is True


@patch("tools.domains.clients.fineract_client")
def test_create_client_inactive(mock_client):
    from tools.domains.clients import create_client

    mock_client.execute_post.return_value = {"resourceId": 6}
    result = create_client.func("Jane", "Doe", is_active=False)
    assert result["resourceId"] == 6
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["active"] is False
    assert "activationDate" not in call_args[0][1]


@patch("tools.domains.clients.fineract_client")
def test_activate_client(mock_client):
    from tools.domains.clients import activate_client

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = activate_client.func(1)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "activate" in call_args[0][0]


@patch("tools.domains.clients.fineract_client")
def test_update_client_mobile(mock_client):
    from tools.domains.clients import update_client_mobile

    mock_client.execute_put.return_value = {"resourceId": 1}
    result = update_client_mobile.func(1, "555-9999")
    assert result["resourceId"] == 1
    mock_client.execute_put.assert_called_once_with(
        "clients/1", {"mobileNo": "555-9999"}
    )


@patch("tools.domains.clients.fineract_client")
def test_close_client(mock_client):
    from tools.domains.clients import close_client

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = close_client.func(1)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert "close" in call_args[0][0]


@patch("tools.domains.clients.fineract_client")
def test_get_client_identifiers(mock_client):
    from tools.domains.clients import get_client_identifiers

    mock_client.execute_get.return_value = [{"id": 1, "documentType": {"name": "Passport"}}]
    result = get_client_identifiers.func(1)
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("clients/1/identifiers")


@patch("tools.domains.clients.fineract_client")
def test_create_client_identifier(mock_client):
    from tools.domains.clients import create_client_identifier

    mock_client.execute_post.return_value = {"resourceId": 10}
    result = create_client_identifier.func(1, 4, "ABC123")
    assert result["resourceId"] == 10
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["documentTypeId"] == 4
    assert call_args[0][1]["documentKey"] == "ABC123"


@patch("tools.domains.clients.fineract_client")
def test_get_client_documents(mock_client):
    from tools.domains.clients import get_client_documents

    mock_client.execute_get.return_value = []
    result = get_client_documents.func(1)
    assert result == []
    mock_client.execute_get.assert_called_once_with("clients/1/documents")


@patch("tools.domains.clients.fineract_client")
def test_get_client_charges(mock_client):
    from tools.domains.clients import get_client_charges

    mock_client.execute_get.return_value = []
    result = get_client_charges.func(1)
    assert result == []


@patch("tools.domains.clients.fineract_client")
def test_apply_client_charge(mock_client):
    from tools.domains.clients import apply_client_charge

    mock_client.execute_post.return_value = {"resourceId": 1}
    result = apply_client_charge.func(1, 2, 50.0)
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert call_args[0][1]["chargeId"] == 2
    assert call_args[0][1]["amount"] == 50.0


@patch("tools.domains.clients.fineract_client")
def test_get_client_transactions(mock_client):
    from tools.domains.clients import get_client_transactions

    mock_client.execute_get.return_value = []
    result = get_client_transactions.func(1)
    assert result == []


@patch("tools.domains.clients.fineract_client")
def test_get_client_addresses(mock_client):
    from tools.domains.clients import get_client_addresses

    mock_client.execute_get.return_value = [{"addressType": "Home"}]
    result = get_client_addresses.func(1)
    assert len(result) == 1
