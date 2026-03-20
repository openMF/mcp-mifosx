# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.codetables.fineract_client")
def test_list_codes(mock_client):
    from tools.domains.codetables import list_codes

    mock_client.execute_get.return_value = [{"id": 1, "name": "Gender"}]
    result = list_codes.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("codes")


@patch("tools.domains.codetables.fineract_client")
def test_get_code_values(mock_client):
    from tools.domains.codetables import get_code_values

    mock_client.execute_get.return_value = [{"id": 1, "name": "Male"}, {"id": 2, "name": "Female"}]
    result = get_code_values.func(4)
    assert len(result) == 2
    mock_client.execute_get.assert_called_once_with("codes/4/codevalues")


@patch("tools.domains.codetables.fineract_client")
def test_list_datatables(mock_client):
    from tools.domains.codetables import list_datatables

    mock_client.execute_get.return_value = [{"registeredTableName": "m_client_extra"}]
    result = list_datatables.func()
    assert len(result) == 1
    mock_client.execute_get.assert_called_once_with("datatables")
