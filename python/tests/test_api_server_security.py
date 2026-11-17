# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Security regressions for the REST bridge.

The bridge calls domain functions directly, so it must enforce the API key
and the same tool profile as the MCP server. These tests cover missing and
invalid credentials, profile bypass attempts, fail-closed configuration and
the routes that must keep working when both controls pass.
"""

import ast
from pathlib import Path
from types import SimpleNamespace
from typing import Any, Dict, List

import pytest
from fastapi.routing import APIRoute
from fastapi.testclient import TestClient

from core import api_server
from core.rest_security import (
    API_KEY_ENV,
    CORS_ORIGINS_ENV,
    REST_TOOL_ATTRIBUTE,
    configured_cors_origins,
)
from core.tool_names import DOMAIN_TOOL_TO_MCP_TOOL
from tools.registry import router

KEY = "rest-test-key"
AUTH_HEADERS = {"X-API-Key": KEY}
PYTHON_DIR = Path(__file__).resolve().parent.parent

PROFILES = """
[profiles.read-only]
tools = ["search_clients", "get_client"]

[profiles.writer]
tools = ["search_clients", "get_client", "create_new_client"]
"""


@pytest.fixture
def profiles_file(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    path = tmp_path / "profiles.toml"
    path.write_text(PROFILES, encoding="utf-8")
    monkeypatch.setenv("MCP_PROFILES_FILE", str(path))
    monkeypatch.setenv("MCP_AGENT_PROFILE", "read-only")
    monkeypatch.setenv(API_KEY_ENV, KEY)
    monkeypatch.delenv(CORS_ORIGINS_ENV, raising=False)
    return path


@pytest.fixture
def client(profiles_file: Path) -> TestClient:
    with TestClient(api_server.app) as test_client:
        yield test_client


def _tool(result: Any, calls: List[Dict[str, Any]]) -> SimpleNamespace:
    def func(**kwargs: Any) -> Any:
        calls.append(kwargs)
        return result

    return SimpleNamespace(func=func)


# ── authentication ───────────────────────────────────────────────────


def test_request_without_credentials_is_rejected_before_any_tool_runs(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({"clients": []}, calls))

    response = client.get("/api/clients/search", params={"name": "Ann"})

    assert response.status_code == 401
    assert calls == []
    assert KEY not in response.text


def test_request_with_invalid_credentials_is_rejected(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({"clients": []}, calls))

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers={"X-API-Key": "wrong"}
    )

    assert response.status_code == 401
    assert calls == []


def test_a_non_ascii_key_is_rejected_instead_of_raising(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({"clients": []}, calls))

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers={"X-API-Key": b"cl\xe9"}
    )

    assert response.status_code == 401
    assert calls == []


def test_valid_api_key_can_call_a_tool_in_the_active_profile(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(
        api_server.clients,
        "search_clients_by_name",
        _tool({"clients": [{"id": 1, "displayName": "Ann"}]}, calls),
    )

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 200
    assert response.json() == {"clients": [{"id": 1, "displayName": "Ann"}]}
    assert calls == [{"name_query": "Ann"}]


def test_bearer_token_is_accepted_as_well(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({}, calls))

    response = client.get(
        "/api/clients/search",
        params={"name": "Ann"},
        headers={"Authorization": f"Bearer {KEY}"},
    )

    assert response.status_code == 200
    assert calls == [{"name_query": "Ann"}]


def test_no_configured_key_refuses_every_request(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({}, calls))
    monkeypatch.delenv(API_KEY_ENV)

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 503
    assert calls == []
    assert KEY not in response.text


def test_chat_is_authenticated(client: TestClient) -> None:
    response = client.post("/api/chat", json={"message": "hi"})

    assert response.status_code == 401


# ── profile enforcement ──────────────────────────────────────────────


def test_tool_outside_the_active_profile_is_refused_before_it_runs(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(
        api_server.clients, "create_client", _tool({"clientId": 7}, calls)
    )

    response = client.post(
        "/api/clients",
        json={"firstname": "Ann", "lastname": "Lee", "office_id": 1},
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 403
    assert calls == []
    assert "create_new_client" in response.text


def test_writer_profile_allows_its_write_tool(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(
        api_server.clients, "create_client", _tool({"clientId": 7}, calls)
    )
    monkeypatch.setenv("MCP_AGENT_PROFILE", "writer")

    response = client.post(
        "/api/clients",
        json={"firstname": "Ann", "lastname": "Lee", "office_id": 1},
        headers=AUTH_HEADERS,
    )

    assert response.status_code == 200
    assert response.json() == {"clientId": 7}
    assert calls and calls[0]["firstname"] == "Ann"


def test_unknown_profile_fails_closed(
    client: TestClient, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({}, calls))
    monkeypatch.setenv("MCP_AGENT_PROFILE", "not-a-profile")

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 503
    assert calls == []


def test_unreadable_profiles_file_fails_closed(
    client: TestClient, monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    calls: List[Dict[str, Any]] = []
    monkeypatch.setattr(api_server.clients, "search_clients_by_name", _tool({}, calls))
    monkeypatch.setenv("MCP_PROFILES_FILE", str(tmp_path / "missing.toml"))

    response = client.get(
        "/api/clients/search", params={"name": "Ann"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 503
    assert calls == []


def test_every_tool_route_has_a_profile_dependency() -> None:
    """A new REST route must declare its MCP tool, not silently bypass profiles."""
    missing = []
    for route in api_server.app.routes:
        if not isinstance(route, APIRoute) or route.path in {"/api/chat", "/api/router/intent"}:
            continue
        tools = [
            getattr(dependency.call, REST_TOOL_ATTRIBUTE, None)
            for dependency in route.dependant.dependencies
        ]
        if not any(tools):
            missing.append(route.path)

    assert missing == []


def _registered_mcp_tools() -> set[str]:
    """Public tool names registered on the MCP server."""
    tree = ast.parse((PYTHON_DIR / "mcp_server.py").read_text(encoding="utf-8"))
    registered = set()
    for node in tree.body:
        if not isinstance(node, ast.FunctionDef):
            continue
        if not any(
            isinstance(d, ast.Call) and getattr(d.func, "attr", "") == "tool"
            for d in node.decorator_list
        ):
            continue
        name = node.name
        for decorator in node.decorator_list:
            if (
                isinstance(decorator, ast.Call)
                and getattr(decorator.func, "id", "") == "safe_tool"
                and decorator.args
                and isinstance(decorator.args[0], ast.Constant)
            ):
                name = decorator.args[0].value
        registered.add(name)
    return registered


def test_every_tool_route_declares_a_registered_mcp_tool() -> None:
    registered = _registered_mcp_tools()
    missing = set()
    for route in api_server.app.routes:
        if not isinstance(route, APIRoute) or route.path in {"/api/chat", "/api/router/intent"}:
            continue
        for dependency in route.dependant.dependencies:
            tool = getattr(dependency.call, REST_TOOL_ATTRIBUTE, None)
            if tool and tool not in registered:
                missing.add(tool)

    assert missing == set()


def test_rest_tool_names_exist_in_the_mcp_server() -> None:
    assert set(DOMAIN_TOOL_TO_MCP_TOOL.values()) <= _registered_mcp_tools()


def test_every_registry_tool_has_a_public_name() -> None:
    missing = {tool.name for tool in router.get_all_tools()} - set(DOMAIN_TOOL_TO_MCP_TOOL)
    assert missing == set()


# ── intent suggestions ───────────────────────────────────────────────


def test_intent_suggestions_are_filtered_to_the_active_profile(client: TestClient) -> None:
    response = client.post(
        "/api/router/intent", json={"prompt": "create a new client"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 200
    assert set(response.json()["tools_loaded"]) <= {"search_clients", "get_client"}
    assert "create_new_client" not in response.json()["tools_loaded"]


def test_intent_suggestions_use_public_tool_names(client: TestClient, monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("MCP_AGENT_PROFILE", "writer")

    response = client.post(
        "/api/router/intent", json={"prompt": "create a new client"}, headers=AUTH_HEADERS
    )

    assert response.status_code == 200
    assert "create_new_client" in response.json()["tools_loaded"]
    assert "create_client" not in response.json()["tools_loaded"]


# ── CORS ─────────────────────────────────────────────────────────────


def test_cors_origins_are_opt_in(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(CORS_ORIGINS_ENV, raising=False)
    assert configured_cors_origins() == []

    monkeypatch.setenv(CORS_ORIGINS_ENV, "https://a.example, https://b.example")
    assert configured_cors_origins() == ["https://a.example", "https://b.example"]
