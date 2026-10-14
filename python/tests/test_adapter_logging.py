# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""The adapter must not write request bodies (client names, phone numbers, ID
document numbers) to the logs. It logs which fields were sent instead."""

import logging
from unittest.mock import MagicMock, patch

import pytest

from tools.mcp_adapter import fineract_client


def test_post_logs_field_names_not_values(caplog: pytest.LogCaptureFixture) -> None:
    response = MagicMock()
    response.json.return_value = {"resourceId": 1}
    payload = {"firstname": "Asha", "mobileNo": "+91 98450 12345", "documentKey": "PASSPORT-K1234567"}

    with caplog.at_level(logging.INFO), patch("tools.mcp_adapter.requests.post", return_value=response):
        assert fineract_client.execute_post("clients", payload) == {"resourceId": 1}

    logged = caplog.text
    for value in ("Asha", "98450", "PASSPORT-K1234567"):
        assert value not in logged
    assert "Payload fields: documentKey, firstname, mobileNo" in logged
