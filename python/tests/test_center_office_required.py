# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""CenterCreate and create_new_center must enforce a strict positive integer for office_id."""

import inspect
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient
from fastmcp.exceptions import ToolError
from pydantic import StrictInt

import mcp_server
from core import api_server
from tools.domains.groups import create_center
from tools.mcp_adapter import fineract_client


def test_mcp_tool_requires_an_office() -> None:
    # FastMCP builds the input schema from the signature, so no default means officeId is required.
    office = inspect.signature(mcp_server.create_new_center).parameters["officeId"]
    assert office.default is inspect.Parameter.empty
    assert office.annotation is StrictInt


@pytest.mark.parametrize("office_id", [True, 0, -3, "3"])
def test_mcp_tool_rejects_non_positive_or_boolean_office(office_id: object) -> None:
    with pytest.raises(ToolError):
        mcp_server.create_new_center(name="Central", officeId=office_id)


def test_mcp_tool_sends_the_given_office() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 5}) as post:
        mcp_server.create_new_center(name="Central", officeId=3)
    endpoint, payload = post.call_args.args
    assert endpoint == "centers"
    assert payload["officeId"] == 3


def test_domain_function_requires_an_office() -> None:
    office = inspect.signature(create_center.func).parameters["office_id"]
    assert office.default is inspect.Parameter.empty


def test_rest_endpoint_without_an_office_returns_422() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post") as post:
        response = client.post("/api/centers", json={"name": "Central"})
    post.assert_not_called()
    assert response.status_code == 422


def test_rest_endpoint_sends_the_given_office() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 5}) as post:
        response = client.post("/api/centers", json={"name": "Central", "office_id": 3})
    assert response.status_code == 200
    assert post.call_args.args[1]["officeId"] == 3


@pytest.mark.parametrize("office_id", [True, 0, -3, "3"])
def test_rest_endpoint_rejects_an_office_that_is_not_a_positive_whole_number(office_id: object) -> None:
    # Without strict checking, True would be read as office 1.
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post") as post:
        response = client.post("/api/centers", json={"name": "Central", "office_id": office_id})
    post.assert_not_called()
    assert response.status_code == 422

