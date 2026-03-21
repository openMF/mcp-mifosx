# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.accounting.fineract_client")
def test_list_gl_accounts(mock_client):
    from tools.domains.accounting import list_gl_accounts

    mock_client.execute_get.return_value = [{"id": 1, "name": "Cash"}]
    result = list_gl_accounts.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("glaccounts")


@patch("tools.domains.accounting.fineract_client")
def test_list_gl_accounts_by_type(mock_client):
    from tools.domains.accounting import list_gl_accounts

    mock_client.execute_get.return_value = [{"id": 1, "name": "Cash", "type": {"value": "ASSET"}}]
    result = list_gl_accounts.func(type=1)
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("glaccounts?type=1")


@patch("tools.domains.accounting.fineract_client")
def test_get_journal_entries(mock_client):
    from tools.domains.accounting import get_journal_entries

    mock_client.execute_get.return_value = [{"id": 1, "amount": 100}]
    result = get_journal_entries.func(account_id=5)
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("journalentries?glAccountId=5")


@patch("tools.domains.accounting.fineract_client")
def test_get_journal_entries_no_params(mock_client):
    from tools.domains.accounting import get_journal_entries

    mock_client.execute_get.return_value = []
    result = get_journal_entries.func()
    assert result == []
    mock_client.execute_get.assert_called_once_with("journalentries")


@patch("tools.domains.accounting.fineract_client")
def test_create_journal_entry(mock_client):
    from tools.domains.accounting import create_journal_entry

    mock_client.execute_post.return_value = {"resourceId": 1}
    credits = [{"glAccountId": 1, "amount": 100}]
    debits = [{"glAccountId": 2, "amount": 100}]
    result = create_journal_entry.func(1, "20 March 2026", credits, debits, "Test entry")
    assert result["resourceId"] == 1
    call_args = mock_client.execute_post.call_args
    assert call_args[0][0] == "journalentries"
    assert call_args[0][1]["credits"] == credits
    assert call_args[0][1]["debits"] == debits
