# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""apply_savings_fee and the REST fee endpoints used to apply charge 1 or 2, whatever fee was requested."""

from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

import mcp_server
from core import api_server
from tools.mcp_adapter import fineract_client

CHARGES = [
    {"id": 1, "name": "Withdrawal fee", "amount": 2, "active": True,
     "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Withdrawal Fee"}},
    {"id": 4, "name": "Maintenance fee", "amount": 15, "active": True,
     "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 6, "name": "Old maintenance fee", "amount": 10, "active": False,
     "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 7, "name": "Late payment fee", "amount": 5, "active": True,
     "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Specified due date"}},
]


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.apply_savings_fee, "fn", mcp_server.apply_savings_fee)
    try:
        return fn(**kwargs)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_applies_the_requested_charge() -> None:
    with patch("tools.domains.savings.get_fineract_today", return_value="15 October 2026"), patch.object(
        fineract_client, "execute_post", return_value={"resourceId": 3}
    ) as post:
        assert _call(accountId=8, amount=15, chargeId=4) == {"resourceId": 3}
    endpoint, payload = post.call_args.args
    assert endpoint == "savingsaccounts/8/charges"
    assert payload["chargeId"] == 4
    assert payload["amount"] == 15


def test_without_a_charge_lists_the_options_and_charges_nothing() -> None:
    with patch.object(mcp_server.list_charges_domain, "func", return_value=CHARGES), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(accountId=8, amount=15)
    post.assert_not_called()
    # Only active savings charges with a specified due date, and in the message the agent sees
    assert "chargeId is required" in result["error"]
    assert "4 = Maintenance fee (15)" in result["error"]
    for other in ("Withdrawal fee", "Old maintenance fee", "Late payment fee"):
        assert other not in result["error"]


def test_without_a_charge_and_none_available() -> None:
    with patch.object(mcp_server.list_charges_domain, "func", return_value=CHARGES[:1]), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(accountId=8, amount=15)
    post.assert_not_called()
    assert "no active savings charge with a specified due date" in result["error"]


@pytest.mark.parametrize("endpoint, body", [
    ("/api/savings/8/charge", {"amount": 15}),
    ("/api/loans/8/late-fee", {"fee_amount": 15}),
])
def test_rest_fee_endpoints_need_a_charge_id(endpoint: str, body: Dict[str, Any]) -> None:
    with patch.object(fineract_client, "execute_post") as post:
        response = TestClient(api_server.app).post(endpoint, json=body)
    assert response.status_code == 422
    post.assert_not_called()


@pytest.mark.parametrize("endpoint, body, posted_to", [
    ("/api/savings/8/charge", {"amount": 15, "charge_id": 4}, "savingsaccounts/8/charges"),
    ("/api/loans/8/late-fee", {"fee_amount": 15, "charge_id": 5}, "loans/8/charges"),
])
def test_rest_fee_endpoints_apply_the_requested_charge(endpoint: str, body: Dict[str, Any], posted_to: str) -> None:
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 3}) as post:
        response = TestClient(api_server.app).post(endpoint, json=body)
    assert response.status_code == 200
    sent_to, payload = post.call_args.args
    assert sent_to == posted_to
    assert payload["chargeId"] == body["charge_id"]
