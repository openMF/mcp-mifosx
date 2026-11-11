# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""get_savings_txns summarises each transaction, but it only did so when the domain tool
returned a dict. The domain tool returns the list of transactions, so the summary never
ran and the agent got Fineract's raw transaction objects under 'items'."""

from typing import Any, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

ACCOUNT: Dict[str, Any] = {
    "id": 7,
    "accountNo": "000000007",
    "transactions": [
        {
            "id": 12,
            "transactionType": {"id": 2, "code": "savingsAccountTransactionType.withdrawal", "value": "Withdrawal"},
            "accountId": 7,
            "date": [2026, 11, 5],
            "currency": {"code": "USD", "name": "US Dollar", "decimalPlaces": 2},
            "amount": 40.0,
            "runningBalance": 60.0,
            "reversed": False,
            "submittedOnDate": [2026, 11, 5],
        },
        {
            "id": 11,
            "transactionType": {"id": 1, "code": "savingsAccountTransactionType.deposit", "value": "Deposit"},
            "accountId": 7,
            "date": [2026, 11, 3],
            "currency": {"code": "USD", "name": "US Dollar", "decimalPlaces": 2},
            "amount": 100.0,
            "runningBalance": 100.0,
            "reversed": False,
            "submittedOnDate": [2026, 11, 3],
        },
    ],
}


def _call(response: Dict[str, Any]) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.get_savings_txns, "fn", mcp_server.get_savings_txns)
    with patch.object(fineract_client, "execute_get", return_value=response) as get:
        try:
            result = fn(accountId=7)
        except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
            result = {"error": str(exc)}
    get.assert_called_once_with("savingsaccounts/7?associations=transactions")
    return result


def test_transactions_are_summarised() -> None:
    assert _call(ACCOUNT) == {
        "savingsId": 7,
        "currency": "USD",
        "transactions": [
            {
                "transactionId": 12,
                "type": "Withdrawal",
                "date": "5 November 2026",
                "amount": 40.0,
                "runningBalance": 60.0,
            },
            {
                "transactionId": 11,
                "type": "Deposit",
                "date": "3 November 2026",
                "amount": 100.0,
                "runningBalance": 100.0,
            },
        ],
    }


def test_account_without_transactions() -> None:
    # Fineract leaves the association out when the account has no transactions.
    assert _call({"id": 7, "accountNo": "000000007"}) == {"savingsId": 7, "currency": None, "transactions": []}


def test_unknown_account_is_an_error() -> None:
    result = _call({"error": "Savings account with identifier 7 does not exist", "status_code": 404})
    assert result == {"error": "Savings account with identifier 7 does not exist"}
