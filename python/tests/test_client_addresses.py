# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

from fastapi.testclient import TestClient

import mcp_server
from core import api_server
from tools.mcp_adapter import fineract_client

ADDRESSES = [{"addressId": 7, "addressLine1": "123 Test Street"}]


def test_mcp_get_addresses_returns_client_addresses():
    tool = getattr(mcp_server.get_addresses, "fn", mcp_server.get_addresses)
    with patch.object(fineract_client, "execute_get", return_value=ADDRESSES) as execute_get:
        result = tool(clientId=42)

    execute_get.assert_called_once_with("client/42/addresses")
    assert result == {"items": ADDRESSES}


def test_rest_route_returns_client_addresses():
    with patch.object(fineract_client, "execute_get", return_value=ADDRESSES) as execute_get:
        response = TestClient(api_server.app).get("/api/clients/42/addresses")

    execute_get.assert_called_once_with("client/42/addresses")
    assert response.status_code == 200
    assert response.json() == ADDRESSES
