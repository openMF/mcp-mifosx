# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""create_savings, create_new_loan and create_group_loan_app used to fall back to product 1
when no productId was given, whatever product 1 was on the tenant."""

from typing import Any, Dict
from unittest.mock import patch

from fastapi.testclient import TestClient

import mcp_server
from core import api_server
from tools.domains.loans import create_group_loan, create_loan
from tools.domains.savings import create_savings_account
from tools.mcp_adapter import fineract_client

LOAN_PRODUCTS = [{"id": 3, "name": "Small business loan"}, {"id": 5, "name": "Farm loan"}]
SAVINGS_PRODUCTS = [{"id": 2, "name": "Basic savings"}, {"id": 4, "name": "Student savings"}]


def _call(tool: Any, **kwargs: Any) -> Dict[str, Any]:
    """Call an MCP tool the way an agent does, returning the result or the error message."""
    fn = getattr(tool, "fn", tool)
    try:
        return fn(**kwargs)
    except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
        return {"error": str(exc)}


def test_savings_account_without_a_product_lists_the_products() -> None:
    with patch.object(fineract_client, "execute_get", return_value=SAVINGS_PRODUCTS) as get, patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = create_savings_account.func(7)
    post.assert_not_called()
    get.assert_called_once_with("savingsproducts")
    assert "2 = Basic savings; 4 = Student savings" in result["error"]
    assert result["savings_products"] == [{"id": 2, "name": "Basic savings"}, {"id": 4, "name": "Student savings"}]


def test_loans_without_a_product_list_the_products() -> None:
    with patch.object(fineract_client, "execute_get", return_value=LOAN_PRODUCTS) as get, patch.object(
        fineract_client, "execute_post"
    ) as post:
        individual = create_loan.func(3, 10000, 12)
        group = create_group_loan.func(5, 6000, 6)
    post.assert_not_called()
    assert all(c.args == ("loanproducts",) for c in get.call_args_list)
    for result in (individual, group):
        assert "3 = Small business loan; 5 = Farm loan" in result["error"]


def test_savings_account_with_a_product_is_created() -> None:
    with patch("tools.domains.savings.get_fineract_today", return_value="30 October 2026"), patch.object(
        fineract_client, "execute_post", return_value={"savingsId": 11}
    ) as post:
        assert create_savings_account.func(7, product_id=4) == {"savingsId": 11}
    assert post.call_args.args[1]["productId"] == 4


def test_no_products_configured() -> None:
    with patch.object(fineract_client, "execute_get", return_value=[]), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = create_savings_account.func(7)
    post.assert_not_called()
    assert "no savings products are configured" in result["error"]


def test_products_cannot_be_loaded() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"error": "Fineract is down"}), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = create_loan.func(3, 10000, 12)
    post.assert_not_called()
    assert "could not be loaded: Fineract is down" in result["error"]


def test_mcp_tools_pass_the_product_list_on() -> None:
    with patch.object(fineract_client, "execute_get", return_value=SAVINGS_PRODUCTS), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = _call(mcp_server.create_savings, clientId=7)
    post.assert_not_called()
    assert "2 = Basic savings" in result["error"]

    with patch.object(fineract_client, "execute_get", return_value=LOAN_PRODUCTS), patch.object(
        fineract_client, "execute_post"
    ) as post:
        loan = _call(mcp_server.create_new_loan, clientId=3, principal=1000, months=6)
        group = _call(mcp_server.create_group_loan_app, groupId=5, principal=1000, months=6)
    post.assert_not_called()
    assert "3 = Small business loan" in loan["error"]
    assert "3 = Small business loan" in group["error"]


def test_rest_endpoint_without_a_product_returns_400() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_get", return_value=SAVINGS_PRODUCTS), patch.object(
        fineract_client, "execute_post"
    ) as post:
        response = client.post("/api/savings", json={"client_id": 7})
    post.assert_not_called()
    assert response.status_code == 400
    assert "2 = Basic savings" in response.json()["detail"]
