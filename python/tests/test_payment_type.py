# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""The tools used to post a payment type id nobody asked for: 1 on a repayment and a
deposit, 2 on a withdrawal. On a stock Fineract those are 'Money Transfer' and
'Repayment Adjustment Chargeback', not the cash the code claimed."""

from typing import Any, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

ACTIVE_LOAN = {"status": {"value": "Active"}}
ACTIVE_SAVINGS = {"status": {"value": "Active"}, "summary": {"availableBalance": 5000}}
PAYMENT_TYPES = [
    {"id": 1, "name": "Money Transfer", "isCashPayment": False},
    {"id": 4, "name": "Cash", "isCashPayment": True},
]


def _call(tool: Any, **kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(tool, "fn", tool)
    try:
        return fn(**kwargs)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_repayment_without_a_payment_type_sends_nothing() -> None:
    with patch.object(mcp_server.get_loan_details, "func", return_value=ACTIVE_LOAN), patch(
        "tools.domains.loans.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 9}) as post:
        assert _call(mcp_server.make_repayment, loanId=8, amount=150) == {"resourceId": 9}
    endpoint, payload = post.call_args.args
    assert endpoint == "loans/8/transactions?command=repayment"
    assert "paymentTypeId" not in payload


def test_repayment_with_a_payment_type_sends_it() -> None:
    with patch.object(mcp_server.get_loan_details, "func", return_value=ACTIVE_LOAN), patch(
        "tools.domains.loans.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 9}) as post:
        assert _call(mcp_server.make_repayment, loanId=8, amount=150, paymentTypeId=4) == {"resourceId": 9}
    assert post.call_args.args[1]["paymentTypeId"] == 4


def test_deposit_without_a_payment_type_lists_the_options_and_deposits_nothing() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch(
        "tools.domains.savings.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_get", return_value=PAYMENT_TYPES) as get, patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(mcp_server.deposit, accountId=7, amount=100)
    post.assert_not_called()
    assert get.call_args.args[0] == "paymenttypes"
    # The options are in the message itself: safe_tool passes on only the message
    assert "payment_type_id is required for a deposit" in result["error"]
    assert "1 = Money Transfer" in result["error"]
    assert "4 = Cash" in result["error"]
    # The system payment types a stock Fineract ships are named, not assumed to be cash
    assert "Money Transfer" in result["error"]


def test_deposit_with_a_payment_type_sends_it() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch(
        "tools.domains.savings.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 5}) as post:
        assert _call(mcp_server.deposit, accountId=7, amount=100, paymentTypeId=4) == {"resourceId": 5}
    endpoint, payload = post.call_args.args
    assert endpoint == "savingsaccounts/7/transactions?command=deposit"
    assert payload["paymentTypeId"] == 4


def test_withdraw_with_a_payment_type_sends_it() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch(
        "tools.domains.savings.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 6}) as post:
        assert _call(mcp_server.withdraw, accountId=7, amount=100, paymentTypeId=4) == {"resourceId": 6}
    endpoint, payload = post.call_args.args
    assert endpoint == "savingsaccounts/7/transactions?command=withdrawal"
    assert payload["paymentTypeId"] == 4


def test_withdraw_without_a_payment_type_withdraws_nothing() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch(
        "tools.domains.savings.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_get", return_value=PAYMENT_TYPES), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(mcp_server.withdraw, accountId=7, amount=100)
    post.assert_not_called()
    assert "payment_type_id is required for a withdrawal" in result["error"]


def test_deposit_when_no_payment_types_are_configured() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch.object(
        fineract_client, "execute_get", return_value=[]
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(mcp_server.deposit, accountId=7, amount=100)
    post.assert_not_called()
    assert "no payment types are configured" in result["error"]
    assert "Add a payment type in Fineract first" in result["error"]


def test_deposit_when_the_payment_types_cannot_be_loaded() -> None:
    with patch.object(mcp_server.get_savings_account, "func", return_value=ACTIVE_SAVINGS), patch.object(
        fineract_client, "execute_get", return_value={"error": "Timed out"}
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(mcp_server.deposit, accountId=7, amount=100)
    post.assert_not_called()
    assert "the payment types could not be loaded" in result["error"]
    assert "Timed out" in result["error"]
