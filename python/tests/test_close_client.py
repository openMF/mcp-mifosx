# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""close_client_profile used to send closureReasonId 17, whatever reasons the tenant has."""

from typing import Any, Dict
from unittest.mock import patch

from fastmcp.exceptions import ToolError

import mcp_server
from tools.mcp_adapter import fineract_client

# GET clients/template?commandParam=close, trimmed: the active ClientClosureReason values
TEMPLATE = {"narrations": [
    {"id": 41, "name": "Moved away", "position": 0, "active": True, "mandatory": False},
    {"id": 42, "name": "Deceased", "position": 1, "active": True, "mandatory": False},
]}


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.close_client_profile, "fn", mcp_server.close_client_profile)
    try:
        return fn(**kwargs)
    except ToolError as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_sends_the_requested_reason() -> None:
    with patch("tools.domains.clients.get_fineract_today", return_value="20 October 2026"), patch.object(
        fineract_client, "execute_post", return_value={"clientId": 5, "resourceId": 5}
    ) as post:
        assert _call(clientId=5, closureReasonId=42) == {"clientId": 5, "resourceId": 5}
    post.assert_called_once_with(
        "clients/5?command=close",
        {
            "closureDate": "20 October 2026",
            "closureReasonId": 42,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        },
    )


def test_without_a_reason_lists_the_configured_ones_and_closes_nothing() -> None:
    with patch.object(fineract_client, "execute_get", return_value=TEMPLATE) as get, patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(clientId=5)
    get.assert_called_once_with("clients/template?commandParam=close")
    post.assert_not_called()
    assert "closure_reason_id is required" in result["error"]
    # The list is in the message, which is all an agent gets through safe_tool
    assert "41 = Moved away; 42 = Deceased" in result["error"]


def test_without_a_reason_and_none_configured() -> None:
    # A stock Fineract has the ClientClosureReason code but no values in it
    with patch.object(fineract_client, "execute_get", return_value={"narrations": []}), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(clientId=5)
    post.assert_not_called()
    assert "ClientClosureReason" in result["error"]


def test_without_a_reason_when_the_template_fails() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"error": "boom", "status_code": 503}), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(clientId=5)
    post.assert_not_called()
    assert "could not be loaded: boom" in result["error"]
