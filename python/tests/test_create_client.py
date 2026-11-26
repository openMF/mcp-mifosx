# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""create_new_client supports externalId so agents can link clients to external documents/workflows."""

import inspect
from unittest.mock import patch

from fastapi.testclient import TestClient

import mcp_server
from core import api_server
from tools.domains.clients import create_client
from tools.mcp_adapter import fineract_client


def test_domain_create_client_includes_external_id_when_provided() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"clientId": 10}) as post:
        res = create_client.func(
            firstname="John",
            lastname="Doe",
            office_id=1,
            external_id="LOAA850101MDFPNN09",
        )
    assert res == {"clientId": 10}
    endpoint, payload = post.call_args.args
    assert endpoint == "clients"
    assert payload["externalId"] == "LOAA850101MDFPNN09"
    assert payload["officeId"] == 1
    assert payload["firstname"] == "John"
    assert payload["lastname"] == "Doe"


def test_domain_create_client_omits_external_id_when_unset() -> None:
    with patch.object(fineract_client, "execute_post", return_value={"clientId": 11}) as post:
        create_client.func(firstname="John", lastname="Doe", office_id=1)
    endpoint, payload = post.call_args.args
    assert endpoint == "clients"
    assert "externalId" not in payload


def test_mcp_create_new_client_passes_external_id() -> None:
    fn = getattr(mcp_server.create_new_client, "fn", mcp_server.create_new_client)
    with patch.object(fineract_client, "execute_post", return_value={"clientId": 12}) as post:
        fn(
            firstname="Ada",
            lastname="Lovelace",
            officeId=2,
            externalId="EXT-12345",
        )
    endpoint, payload = post.call_args.args
    assert endpoint == "clients"
    assert payload["externalId"] == "EXT-12345"
    assert payload["officeId"] == 2


def test_mcp_create_new_client_signature_has_optional_external_id() -> None:
    param = inspect.signature(mcp_server.create_new_client).parameters["externalId"]
    assert param.default is None


def test_rest_endpoint_passes_external_id() -> None:
    client = TestClient(api_server.app)
    with patch.object(fineract_client, "execute_post", return_value={"clientId": 13}) as post:
        response = client.post(
            "/api/clients",
            json={
                "firstname": "Grace",
                "lastname": "Hopper",
                "office_id": 3,
                "external_id": "EXT-GH-001",
            },
        )
    assert response.status_code == 200
    assert post.call_args.args[1]["externalId"] == "EXT-GH-001"
