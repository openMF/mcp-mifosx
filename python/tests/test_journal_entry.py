# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Journal entries used to be posted without a currencyCode, which Fineract always rejects."""

from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from fastmcp.exceptions import ToolError

import mcp_server
from core import api_server
from tools.domains import accounting
from tools.mcp_adapter import fineract_client

CREDITS = [{"glAccountId": 1, "amount": 100}]
DEBITS = [{"glAccountId": 2, "amount": 100}]


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(mcp_server.record_journal_entry, "fn", mcp_server.record_journal_entry)
    try:
        return fn(**kwargs)
    except ToolError as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_mcp_tool_sends_the_currency() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"transactionId": "MAN1"}) as post:
        result = _call(officeId=1, date="10 March 2026", credits=CREDITS, debits=DEBITS,
                       currencyCode="USD", comment="Opening balance")
    assert result == {"transactionId": "MAN1"}
    endpoint, payload = post.call_args.args
    assert endpoint == "journalentries"
    assert payload["currencyCode"] == "USD"
    assert payload["comments"] == "Opening balance"


@pytest.mark.parametrize("currency", [{}, {"currencyCode": ""}, {"currencyCode": "   "}])
def test_mcp_tool_without_a_currency_posts_nothing(currency: Dict[str, str]) -> None:
    with patch.object(fineract_client, "execute_post") as post:
        result = _call(officeId=1, date="10 March 2026", credits=CREDITS, debits=DEBITS, **currency)
    post.assert_not_called()
    assert "currencyCode is required" in result["error"]


@pytest.mark.parametrize("currency", ["", "   "])
def test_domain_tool_rejects_a_blank_currency(currency: str) -> None:
    with patch.object(fineract_client, "execute_post") as post:
        result = accounting.create_journal_entry.func(1, "10 March 2026", CREDITS, DEBITS, currency)
    post.assert_not_called()
    assert "currency_code is required" in result["error"]


def test_rest_endpoint_sends_the_currency() -> None:
    body = {"office_id": 1, "date": "10 March 2026", "credits": CREDITS, "debits": DEBITS, "currency_code": "INR"}
    with patch.object(fineract_client, "execute_post", return_value={"transactionId": "MAN2"}) as post:
        response = TestClient(api_server.app).post("/api/accounting/journal-entries", json=body)
    assert response.status_code == 200
    assert post.call_args.args[1]["currencyCode"] == "INR"


@pytest.mark.parametrize("currency", [{}, {"currency_code": ""}, {"currency_code": "   "}])
def test_rest_endpoint_needs_a_currency(currency: Dict[str, str]) -> None:
    body = {"office_id": 1, "date": "10 March 2026", "credits": CREDITS, "debits": DEBITS, **currency}
    with patch.object(fineract_client, "execute_post") as post:
        response = TestClient(api_server.app).post("/api/accounting/journal-entries", json=body)
    assert response.status_code == 422
    post.assert_not_called()
