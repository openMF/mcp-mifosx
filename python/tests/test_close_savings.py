# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Tests for close_savings: validate account exists and is active before closing."""

from typing import Any, Dict
from unittest.mock import patch

import pytest

import mcp_server
from tools.mcp_adapter import fineract_client


def _call(account_id: int = 7) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.close_savings, "fn", mcp_server.close_savings)
    try:
        return fn(accountId=account_id)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_closes_an_active_savings_account() -> None:
    active_account = {
        "id": 7,
        "accountNo": "000000007",
        "status": {"value": "Active"},
    }
    with (
        patch.object(mcp_server.get_savings_account, "func", return_value=active_account),
        patch("tools.domains.savings.get_fineract_today", return_value="15 October 2026"),
        patch.object(fineract_client, "execute_post", return_value={"resourceId": 7, "savingsId": 7}) as post,
    ):
        result = _call(account_id=7)

    assert result == {"resourceId": 7, "savingsId": 7}
    post.assert_called_once_with(
        "savingsaccounts/7?command=close",
        {
            "closedOnDate": "15 October 2026",
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        },
    )


def test_non_existent_savings_account_returns_prefetch_failure() -> None:
    not_found = {
        "error": "Fineract Error: The requested resource is not available.",
        "status_code": 404,
    }
    with (
        patch.object(mcp_server.get_savings_account, "func", return_value=not_found),
        patch.object(fineract_client, "execute_post") as post,
    ):
        result = _call(account_id=7)

    post.assert_not_called()
    assert result == {"error": "Savings account ID 7 not found. Check get_client_accts to see valid savingsIds."}


def test_savings_prefetch_outage_is_reported_as_itself() -> None:
    outage = {
        "error": "Fineract Error: Service not available.",
        "status_code": 503,
    }
    with (
        patch.object(mcp_server.get_savings_account, "func", return_value=outage),
        patch.object(fineract_client, "execute_post") as post,
    ):
        result = _call(account_id=7)

    post.assert_not_called()
    assert result == {"error": "Fineract Error: Service not available."}
    assert "not found" not in result["error"]


@pytest.mark.parametrize(
    "status",
    ["Submitted and pending approval", "Approved", "Closed", "Matured"],
)
def test_does_not_close_a_savings_account_that_is_not_active(status: str) -> None:
    inactive_account = {
        "id": 7,
        "accountNo": "000000007",
        "status": {"value": status},
    }
    with (
        patch.object(mcp_server.get_savings_account, "func", return_value=inactive_account),
        patch.object(fineract_client, "execute_post") as post,
    ):
        result = _call(account_id=7)

    post.assert_not_called()
    assert result == {"error": f"Savings account 7 is in status '{status}'. Only Active accounts can be closed."}
