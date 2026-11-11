# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""get_client_accts used to call any client with an ID above 1000 and no accounts
'likely hallucinated'. Fineract answers 404 for a client that does not exist, so a 200
with no accounts is a real client, for example one that was just created."""

from typing import Any, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

NOT_FOUND = {"error": "Client with identifier 99999 does not exist", "status_code": 404}


def _call(response: Dict[str, Any], client_id: int) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.get_client_accts, "fn", mcp_server.get_client_accts)
    with patch.object(fineract_client, "execute_get", return_value=response) as get:
        try:
            result = fn(clientId=client_id)
        except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
            result = {"error": str(exc)}
    get.assert_called_once_with(f"clients/{client_id}/accounts")
    return result


def test_client_without_accounts_is_not_an_error() -> None:
    # Fineract leaves both lists out of the response when the client has no accounts.
    assert _call({}, 1500) == {"resolvedClientId": 1500, "loanAccounts": [], "savingsAccounts": []}


def test_client_without_accounts_reads_the_same_below_and_above_1000() -> None:
    assert _call({}, 7) == {"resolvedClientId": 7, "loanAccounts": [], "savingsAccounts": []}
    assert _call({"loanAccounts": [], "savingsAccounts": []}, 1001)["loanAccounts"] == []


def test_accounts_of_a_client_above_1000_are_listed() -> None:
    result = _call(
        {"savingsAccounts": [{"id": 31, "accountNo": "000000031", "status": {"value": "Active"}, "accountBalance": 250.0}]},
        2400,
    )
    assert result["loanAccounts"] == []
    assert result["savingsAccounts"] == [
        {"savingsId": 31, "accountNo": "000000031", "status": "Active", "outstandingBalance": 250.0, "currency": None}
    ]


def test_unknown_client_is_still_an_error() -> None:
    assert "error" in _call(NOT_FOUND, 99999)
