# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""get_client returns the client's external ID, so an agent can match a client to an identity document."""

from typing import Any, Dict
from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

CLIENT: Dict[str, Any] = {
    "id": 7,
    "displayName": "Ana López",
    "firstname": "Ana",
    "lastname": "López",
    "accountNo": "000000007",
    "status": {"value": "Active"},
    "activationDate": [2026, 10, 1],
    "officeName": "Head Office",
}


def _call(client: Dict[str, Any]) -> Dict[str, Any]:
    fn = getattr(mcp_server.get_client, "fn", mcp_server.get_client)
    with patch.object(fineract_client, "execute_get", return_value=client) as get:
        result = fn(clientId=7)
    get.assert_called_once_with("clients/7")
    return result


def test_returns_the_external_id() -> None:
    result = _call({**CLIENT, "externalId": "LOAA850101MDFPNN09"})
    assert result["externalId"] == "LOAA850101MDFPNN09"
    assert result["clientId"] == 7


def test_external_id_is_none_when_unset() -> None:
    assert _call(CLIENT)["externalId"] is None
