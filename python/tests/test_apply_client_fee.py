# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""apply_client_fee validates clientId and allows selecting client charges."""

from typing import Any, Dict
from unittest.mock import patch

from fastmcp.exceptions import ToolError

import mcp_server
from tools.mcp_adapter import fineract_client

ACTIVE_CLIENT = {"id": 8, "status": {"value": "Active"}}
CHARGES = [
    {"id": 3, "name": "Client onboarding fee", "amount": 50, "active": True,
     "chargeAppliesTo": {"value": "Client"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 4, "name": "Client verification fee", "amount": 10, "active": True,
     "chargeAppliesTo": {"value": "Client"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 5, "name": "Old client fee", "amount": 25, "active": False,
     "chargeAppliesTo": {"value": "Client"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 6, "name": "Savings maintenance", "amount": 5, "active": True,
     "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Specified due date"}},
    {"id": 7, "name": "Late payment fee", "amount": 15, "active": True,
     "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Specified due date"}},
]


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.apply_client_fee, "fn", mcp_server.apply_client_fee)
    try:
        return fn(**kwargs)
    except ToolError as exc:
        return {"error": str(exc)}


def test_applies_the_requested_charge() -> None:
    with patch.object(mcp_server.get_client_details, "func", return_value=ACTIVE_CLIENT), patch(
        "tools.domains.clients.get_fineract_today", return_value="15 October 2026"
    ), patch.object(fineract_client, "execute_post", return_value={"resourceId": 3}) as post:
        assert _call(clientId=8, amount=50, chargeId=3) == {"resourceId": 3}
    endpoint, payload = post.call_args.args
    assert endpoint == "clients/8/charges"
    assert payload["chargeId"] == 3
    assert payload["amount"] == 50
    assert payload["dueDate"] == "15 October 2026"


def test_without_a_charge_lists_the_options_and_charges_nothing() -> None:
    with patch.object(mcp_server.get_client_details, "func", return_value=ACTIVE_CLIENT), patch.object(
        mcp_server.list_charges_domain, "func", return_value=CHARGES
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(clientId=8, amount=50)
    post.assert_not_called()
    # Only active client charges with a specified due date, and in the message the agent sees
    assert "chargeId is required" in result["error"]
    assert "3 = Client onboarding fee (50)" in result["error"]
    assert "4 = Client verification fee (10)" in result["error"]
    for other in ("Old client fee", "Savings maintenance", "Late payment fee"):
        assert other not in result["error"]


def test_without_a_charge_and_none_available() -> None:
    with patch.object(mcp_server.get_client_details, "func", return_value=ACTIVE_CLIENT), patch.object(
        mcp_server.list_charges_domain, "func", return_value=CHARGES[3:]
    ), patch.object(fineract_client, "execute_post") as post:
        result = _call(clientId=8, amount=50)
    post.assert_not_called()
    assert "no active client charge with a specified due date" in result["error"]


def test_non_existent_client_returns_prefetch_failure() -> None:
    missing = {"error": "Fineract Error: The requested resource is not available.", "status_code": 404}
    with patch.object(mcp_server.get_client_details, "func", return_value=missing), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(clientId=99, amount=50, chargeId=3)
    post.assert_not_called()
    assert result == {"error": "Client ID 99 not found."}


def test_non_existent_client_without_charge_id_validates_client_first() -> None:
    missing = {"error": "Fineract Error: The requested resource is not available.", "status_code": 404}
    with patch.object(mcp_server.get_client_details, "func", return_value=missing), patch.object(
        mcp_server.list_charges_domain, "func"
    ) as list_charges, patch.object(fineract_client, "execute_post") as post:
        result = _call(clientId=99, amount=50)
    post.assert_not_called()
    list_charges.assert_not_called()
    assert result == {"error": "Client ID 99 not found."}


def test_client_prefetch_outage_is_reported_as_itself() -> None:
    outage = {"error": "Connection failed: ('Connection aborted.', RemoteDisconnected())", "status_code": 503}
    with patch.object(mcp_server.get_client_details, "func", return_value=outage), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(clientId=8, amount=50, chargeId=3)
    post.assert_not_called()
    assert "Connection failed" in result["error"]
    assert "not found" not in result["error"]
