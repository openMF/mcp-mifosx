# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""The REST API server used to crash on startup because the agent module doesn't exist."""

import sys
import types
from unittest.mock import patch

import pytest
from fastapi.testclient import TestClient

from core import api_server


@pytest.fixture(autouse=True)
def _reset_agent():
    api_server.banking_agent = None
    yield
    api_server.banking_agent = None


def test_starts_without_the_agent_module() -> None:
    with patch.dict(sys.modules, {"agent": None}):  # None makes the import fail
        with TestClient(api_server.app) as client:
            response = client.post("/api/chat", json={"message": "hi"})
    assert response.status_code == 503
    assert response.json()["detail"] == "Agent not initialized"


def test_a_restart_without_the_module_clears_the_old_agent() -> None:
    api_server.banking_agent = object()
    with patch.dict(sys.modules, {"agent": None}):
        with TestClient(api_server.app) as client:
            response = client.post("/api/chat", json={"message": "hi"})
    assert response.status_code == 503


def test_loads_the_agent_when_the_module_exists() -> None:
    agent = object()

    async def get_agent_instance():
        return agent

    fake_module = types.ModuleType("agent")
    fake_module.get_agent_instance = get_agent_instance
    with patch.dict(sys.modules, {"agent": fake_module}):
        with TestClient(api_server.app):
            assert api_server.banking_agent is agent


def test_a_broken_agent_module_still_fails_startup() -> None:
    def broken_import(name, *args, **kwargs):
        if name == "agent":
            raise ModuleNotFoundError("No module named 'langgraph'", name="langgraph")
        return real_import(name, *args, **kwargs)

    real_import = __import__
    with patch("builtins.__import__", side_effect=broken_import):
        with pytest.raises(ModuleNotFoundError, match="langgraph"):
            with TestClient(api_server.app):
                pass
