# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""withdraw compared the amount with availableBalance alone, so it refused every
withdrawal that used an account's overdraft. Fineract lets an account with
allowOverdraft go below zero, down to its overdraftLimit."""

from typing import Any, Dict, Optional
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client


def _account(available: float, allow_overdraft: bool = False, limit: Optional[float] = None) -> Dict[str, Any]:
    account: Dict[str, Any] = {
        "status": {"value": "Active"},
        "summary": {"accountBalance": available, "availableBalance": available},
        "allowOverdraft": allow_overdraft,
    }
    if limit is not None:
        account["overdraftLimit"] = limit
    return account


def _withdraw(account: Dict[str, Any], amount: float) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.withdraw, "fn", mcp_server.withdraw)
    with (
        patch.object(mcp_server.get_savings_account, "func", return_value=account),
        patch("tools.domains.savings.get_fineract_today", return_value="15 October 2026"),
        patch.object(fineract_client, "execute_post", return_value={"resourceId": 6}) as post,
    ):
        try:
            result = fn(accountId=7, amount=amount, paymentTypeId=4)
        except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
            result = {"error": str(exc)}
    if "error" in result:
        post.assert_not_called()
    else:
        assert post.call_args.args[0] == "savingsaccounts/7/transactions?command=withdrawal"
    return result


def test_withdrawal_within_the_overdraft_limit_is_sent() -> None:
    assert _withdraw(_account(100, allow_overdraft=True, limit=500), 400) == {"resourceId": 6}


def test_withdrawal_from_an_overdrawn_account_within_the_limit_is_sent() -> None:
    assert _withdraw(_account(-200, allow_overdraft=True, limit=500), 300) == {"resourceId": 6}


def test_withdrawal_beyond_the_overdraft_limit_is_refused() -> None:
    result = _withdraw(_account(100, allow_overdraft=True, limit=500), 600.01)
    assert result["error"] == (
        "Insufficient funds: requested $600.01 but available balance is $100.00 and the overdraft limit is $500.00 in account 7."
    )


def test_withdrawal_of_exactly_balance_plus_limit_is_sent() -> None:
    # As floats, 0.01 + 0.06 < 0.07.
    assert _withdraw(_account(0.01, allow_overdraft=True, limit=0.06), 0.07) == {"resourceId": 6}
    assert "Insufficient funds" in _withdraw(_account(0.01, allow_overdraft=True, limit=0.06), 0.08)["error"]


def test_overdraft_limit_is_ignored_when_overdraft_is_not_allowed() -> None:
    result = _withdraw(_account(100, allow_overdraft=False, limit=500), 400)
    assert result["error"] == "Insufficient funds: requested $400.00 but available balance is $100.00 in account 7."


def test_overdraft_account_without_a_limit_cannot_go_below_zero() -> None:
    # Fineract leaves overdraftLimit out of the response when it is zero.
    assert "Insufficient funds" in _withdraw(_account(100, allow_overdraft=True), 150)["error"]
    assert _withdraw(_account(100, allow_overdraft=True), 100) == {"resourceId": 6}
