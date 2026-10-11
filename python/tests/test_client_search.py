# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import Mock, patch
from urllib.parse import parse_qs, urlsplit

import pytest
import requests

from tools.domains.clients import search_clients_by_name
from tools.mcp_adapter import fineract_client


@pytest.mark.parametrize("name", ["John Doe", "A&B", "C++", "Name#1", "Zoë", "A&resource=loans&exactMatch=true"])
def test_search_preserves_name_and_client_filter(name):
    response = Mock()
    response.json.return_value = [{"entityId": 42}]
    with patch.object(fineract_client, "base_url", "https://example.test/fineract-provider/api/v1"), patch(
        "tools.mcp_adapter.requests.get", return_value=response
    ) as get:
        assert search_clients_by_name.func(name) == [{"entityId": 42}]

    args, kwargs = get.call_args
    prepared = requests.Request("GET", args[0], params=kwargs["params"]).prepare()
    url = urlsplit(prepared.url)
    assert url.path.endswith("/search")
    assert url.fragment == ""
    assert parse_qs(url.query) == {"query": [name], "resource": ["clients"], "exactMatch": ["false"]}
    response.raise_for_status.assert_called_once()
