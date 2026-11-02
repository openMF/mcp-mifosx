# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""create_lending_group and POST /api/groups used to fall back to office 1 (Head Office)
when no office was given, so a group meant for a branch was quietly created in the wrong one."""

import inspect
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

import mcp_server
from core import api_server
from tools.domains.clients import create_group
from tools.mcp_adapter import fineract_client


def test_mcp_tool_requires_an_office() -> None:
    # FastMCP builds the input schema from the signature, so no default means officeId is required.
    office = inspect.signature(mcp_server.create_lending_group).parameters["officeId"]
    assert office.default is inspect.Parameter.empty


def test_mcp_tool_sends_the_given_office() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"groupId": 9}) as post:
        mcp_server.create_lending_group(name="Alpha", officeId=4)
    endpoint, payload = post.call_args.args
    assert endpoint == "groups"
    assert payload["officeId"] == 4


def test_domain_function_requires_an_office() -> None:
    office = inspect.signature(create_group.func).parameters["office_id"]
    assert office.default is inspect.Parameter.empty


def test_rest_endpoint_without_an_office_returns_422() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post") as post:
        response = client.post("/api/groups", json={"name": "Alpha"})
    post.assert_not_called()
    assert response.status_code == 422


def test_rest_endpoint_sends_the_given_office() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post", return_value={"groupId": 9}) as post:
        response = client.post("/api/groups", json={"name": "Alpha", "office_id": 4})
    assert response.status_code == 200
    assert post.call_args.args[1]["officeId"] == 4


@pytest.mark.parametrize("office_id", [True, 0, -3, "4"])
def test_rest_endpoint_rejects_an_office_that_is_not_a_positive_whole_number(office_id: object) -> None:
    # Without strict checking, true would be read as office 1.
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post") as post:
        response = client.post("/api/groups", json={"name": "Alpha", "office_id": office_id})
    post.assert_not_called()
    assert response.status_code == 422
