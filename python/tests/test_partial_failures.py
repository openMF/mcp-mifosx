# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Two-step tools (approve + disburse, approve + activate) that fail half way.

The first step changes the record in Fineract, so a failure in the second step
must say so, and calling the tool again must finish the job instead of
refusing because the record is no longer pending.
"""

from typing import Any, Callable, Dict
from unittest.mock import patch

import mcp_server
from tools.domains.loans import approve_and_disburse_loan
from tools.domains.savings import approve_and_activate_savings
from tools.mcp_adapter import fineract_client


def _call(tool: Any, **kwargs: Any) -> Dict[str, Any]:
    """Call an MCP tool wrapper and return its result or its error message."""
    fn: Callable[..., Any] = getattr(tool, "fn", tool)
    try:
        return fn(**kwargs)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def _post(*responses: dict):
    return patch.object(fineract_client, "execute_post", side_effect=list(responses))


def _status(value: Any):
    """What GET loans/{id} or savingsaccounts/{id} returns after the failed second step."""
    response = {"error": "Connection timed out."} if value is None else {"status": {"value": value}}
    return patch.object(fineract_client, "execute_get", return_value=response)


def test_failed_disbursement_says_the_loan_was_approved() -> None:
    with _post({"resourceId": 8}, {"error": "Validation Error: disburse amount is exceeding approved principal."}),             _status("Approved"):
        result = approve_and_disburse_loan.func(8, 999999)
    assert "Loan 8 was approved, but the disbursement failed" in result["error"]
    assert "exceeding approved principal" in result["error"]
    assert "now 'Approved'" in result["error"]


def test_failed_approval_is_reported_as_it_is() -> None:
    with _post({"error": "Validation Error: approval date is in the future."}) as post:
        result = approve_and_disburse_loan.func(8)
    assert result == {"error": "Validation Error: approval date is in the future."}
    assert post.call_count == 1


def test_already_approved_loan_is_only_disbursed() -> None:
    with _post({"resourceId": 8}) as post:
        assert approve_and_disburse_loan.func(8, already_approved=True) == {"resourceId": 8}
    post.assert_called_once()
    assert post.call_args.args[0] == "loans/8?command=disburse"


def test_retrying_an_approved_loan_finishes_the_disbursement() -> None:
    approved = {"status": {"value": "Approved"}}
    with patch.object(mcp_server.get_loan_details, "func", return_value=approved), _post({"resourceId": 8}) as post:
        assert _call(mcp_server.approve_disburse_loan, loanId=8) == {"resourceId": 8}
    assert [c.args[0] for c in post.call_args_list] == ["loans/8?command=disburse"]


def test_active_loan_is_still_refused() -> None:
    active = {"status": {"value": "Active"}}
    with patch.object(mcp_server.get_loan_details, "func", return_value=active), _post() as post:
        result = _call(mcp_server.approve_disburse_loan, loanId=8)
    assert "status 'Active'" in result["error"]
    post.assert_not_called()


def test_failed_activation_says_the_savings_account_was_approved() -> None:
    with _post({"resourceId": 4}, {"error": "Validation Error: activation date is before approval date."}),             _status("Approved"):
        result = approve_and_activate_savings.func(4)
    assert "Savings account 4 was approved, but the activation failed" in result["error"]


def test_retrying_an_approved_savings_account_finishes_the_activation() -> None:
    approved = {"status": {"value": "Approved"}}
    with patch.object(mcp_server.get_savings_account, "func", return_value=approved), _post({"resourceId": 4}) as post:
        assert _call(mcp_server.approve_activate_savings, accountId=4) == {"resourceId": 4}
    assert [c.args[0] for c in post.call_args_list] == ["savingsaccounts/4?command=activate"]


def test_active_savings_account_is_refused_before_any_write() -> None:
    active = {"status": {"value": "Active"}}
    with patch.object(mcp_server.get_savings_account, "func", return_value=active), _post() as post:
        result = _call(mcp_server.approve_activate_savings, accountId=4)
    assert "status 'Active'" in result["error"]
    post.assert_not_called()


def test_disbursement_error_on_a_loan_that_did_get_disbursed_reports_success() -> None:
    with _post({"resourceId": 8}, {"error": "Read timed out."}), _status("Active"):
        result = approve_and_disburse_loan.func(8)
    assert "error" not in result
    assert result["status"] == "Active"
    assert "was disbursed" in result["note"]


def test_disbursement_error_with_an_unreadable_status_does_not_claim_approved() -> None:
    with _post({"resourceId": 8}, {"error": "Read timed out."}), _status(None):
        result = approve_and_disburse_loan.func(8)
    assert "could not be read" in result["error"]
    assert "now 'Approved'" not in result["error"]


def test_activation_error_on_an_account_that_did_get_activated_reports_success() -> None:
    with _post({"resourceId": 4}, {"error": "Read timed out."}), _status("Active"):
        result = approve_and_activate_savings.func(4)
    assert "error" not in result
    assert "was activated" in result["note"]


def test_activation_error_with_an_unreadable_status_does_not_claim_approved() -> None:
    with _post({"resourceId": 4}, {"error": "Read timed out."}), _status(None):
        result = approve_and_activate_savings.func(4)
    assert "could not be read" in result["error"]
    assert "now 'Approved'" not in result["error"]
