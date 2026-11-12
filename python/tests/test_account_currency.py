# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""The loan and savings read tools used to label every amount as USD (principal_USD,
balance_USD, ...), whatever the account's real currency, so a 20,000 peso loan could be
reported as $20,000. Now amounts have plain names and the real currency is returned."""

from typing import Any, Callable, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

MXN = {"code": "MXN", "name": "Mexican Peso", "decimalPlaces": 2}


def _call(tool: Callable[..., Any], response: Dict[str, Any], **kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does."""
    fn = getattr(tool, "fn", tool)
    with patch.object(fineract_client, "execute_get", return_value=response):
        return fn(**kwargs)


def _keys(value: Any) -> set:
    """Every dict key anywhere in the value."""
    if isinstance(value, dict):
        return set(value) | {k for v in value.values() for k in _keys(v)}
    if isinstance(value, list):
        return {k for v in value for k in _keys(v)}
    return set()


def _assert_no_usd_labels(result: Dict[str, Any]) -> None:
    assert not [k for k in _keys(result) if k.endswith("_USD")], result


def test_client_accounts_show_each_accounts_currency() -> None:
    result = _call(mcp_server.get_client_accts, {
        "loanAccounts": [{"id": 1, "accountNo": "01", "status": {"value": "Active"},
                          "loanBalance": 20000.0, "currency": MXN}],
        "savingsAccounts": [{"id": 2, "accountNo": "02", "status": {"value": "Active"},
                             "accountBalance": 500.0, "currency": {"code": "KES"}}],
    }, clientId=5)
    _assert_no_usd_labels(result)
    assert result["loanAccounts"][0]["outstandingBalance"] == 20000.0
    assert result["loanAccounts"][0]["currency"] == "MXN"
    assert result["savingsAccounts"][0]["outstandingBalance"] == 500.0
    assert result["savingsAccounts"][0]["currency"] == "KES"


def test_get_loan_shows_the_loans_currency() -> None:
    result = _call(mcp_server.get_loan, {
        "id": 9, "status": {"value": "Active"}, "approvedPrincipal": 20000.0,
        "summary": {"totalOutstanding": 18000.0}, "currency": MXN,
    }, loanId=9)["data"]
    _assert_no_usd_labels(result)
    assert result["principal"] == 20000.0
    assert result["outstandingBalance"] == 18000.0
    assert result["currency"] == "MXN"


def test_repayment_schedule_shows_the_loans_currency() -> None:
    result = _call(mcp_server.get_repayment_sched, {
        "repaymentSchedule": {
            "currency": MXN,
            "periods": [
                {"period": None, "dueDate": [2026, 1, 1]},
                {"period": 1, "dueDate": [2026, 2, 1], "principalDue": 1600.0, "interestDue": 200.0,
                 "feeChargesDue": 0.0, "totalDueForPeriod": 1800.0, "totalPaidForPeriod": 0.0},
            ],
        },
    }, loanId=9)
    _assert_no_usd_labels(result)
    assert result["currency"] == "MXN"
    assert result["periods"][0]["principalDue"] == 1600.0
    assert result["periods"][0]["totalDue"] == 1800.0


def test_loan_history_shows_the_loans_currency() -> None:
    result = _call(mcp_server.get_loan_hist, {
        "accountNo": "01", "status": {"value": "Active"}, "currency": MXN,
        "transactions": [{"id": 3, "type": {"value": "Repayment"}, "date": [2026, 2, 1],
                          "amount": 1800.0, "outstandingLoanBalance": 18200.0}],
    }, loanId=9)
    _assert_no_usd_labels(result)
    assert result["currency"] == "MXN"
    assert result["transactions"][0]["amount"] == 1800.0
    assert result["transactions"][0]["runningBalance"] == 18200.0


def test_get_savings_shows_the_accounts_currency() -> None:
    result = _call(mcp_server.get_savings, {
        "id": 2, "status": {"value": "Active"}, "currency": MXN,
        "summary": {"accountBalance": 500.0, "availableBalance": 450.0},
    }, accountId=2)
    _assert_no_usd_labels(result)
    assert result["balance"] == 500.0
    assert result["availableBalance"] == 450.0
    assert result["currency"] == "MXN"


def test_savings_transactions_show_the_accounts_currency() -> None:
    result = _call(mcp_server.get_savings_txns, {
        "transactions": [{"id": 4, "transactionType": {"value": "Deposit"}, "date": [2026, 2, 1],
                          "currency": MXN, "amount": 500.0, "runningBalance": 500.0}],
    }, accountId=2)
    _assert_no_usd_labels(result)
    assert result["currency"] == "MXN"
    assert result["transactions"][0]["amount"] == 500.0
    assert result["transactions"][0]["runningBalance"] == 500.0


def test_missing_currency_is_none_not_a_guess() -> None:
    result = _call(mcp_server.get_loan, {"id": 9, "status": {"value": "Active"}}, loanId=9)["data"]
    assert result["currency"] is None
