# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Shared configuration for tests that call the REST bridge.

The bridge refuses every request when no API key is configured, so tests that
exercise endpoint behaviour must present one. They also need the tools they
call: the shipped default profile is read-only, while these tests are about
validation and response shapes, not about profile denial. Profile enforcement
has its own dedicated tests in ``test_api_server_security.py``.
"""

import pytest
from fastapi.testclient import TestClient

from core import api_server

REST_API_KEY = "test-rest-api-key"
AUTH_HEADERS = {"X-API-Key": REST_API_KEY}


@pytest.fixture(autouse=True)
def _rest_bridge_defaults(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MCP_REST_API_KEY", REST_API_KEY)
    monkeypatch.setenv("MCP_AGENT_PROFILE", "all")


@pytest.fixture
def client() -> TestClient:
    """A REST client that authenticates with the test API key."""
    return TestClient(api_server.app, headers=AUTH_HEADERS)
