# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""apply_loan_fee used to apply charge 2, whatever fee the user asked for."""

from typing import Any, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

ACTIVE_LOAN = {"status": {"value": "Active"}}
CHARGES = [
    {"id": 2, "name": "Processing fee", "amount": 25, "active": True,
     "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Disbursement"}},
    {"id": 5, "name": "Late payment fee", "amount": 15, "active": True,
     "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 6, "name": "Old late fee", "amount": 10, "active": False,
     "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 7, "name": "Savings maintenance", "amount": 5, "active": True,
     "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Specified due date"}},
]


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.apply_loan_fee, "fn", mcp_server.apply_loan_fee)
    try:
        return fn(**kwargs)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_applies_the_requested_charge() -> None:
    with patch.object(mcp_server.get_loan_details, "func", return_value=ACTIVE_LOAN), patch(
        "tools.domains.loans.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 3}) as post:
        assert _call(loanId=8, feeAmount=15, chargeId=5) == {"resourceId": 3}
    endpoint, payload = post.call_args.args
    assert endpoint == "loans/8/charges"
    assert payload["chargeId"] == 5
    assert payload["amount"] == 15


def test_without_a_charge_lists_the_options_and_charges_nothing() -> None:
    with patch.object(mcp_server.get_loan_details, "func", return_value=ACTIVE_LOAN), patch.object(
        mcp_server.list_charges_domain, "func", return_value=CHARGES
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(loanId=8, feeAmount=15)
    post.assert_not_called()
    # Only active loan charges with a specified due date, and in the message the agent sees
    assert "chargeId is required" in result["error"]
    assert "5 = Late payment fee (15)" in result["error"]
    for other in ("Processing fee", "Old late fee", "Savings maintenance"):
        assert other not in result["error"]


def test_without_a_charge_and_none_available() -> None:
    with patch.object(mcp_server.get_loan_details, "func", return_value=ACTIVE_LOAN), patch.object(
        mcp_server.list_charges_domain, "func", return_value=CHARGES[:1]
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(loanId=8, feeAmount=15)
    post.assert_not_called()
    assert "no active loan charge with a specified due date" in result["error"]
