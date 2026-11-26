# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Tests for client-search failure propagation (issue #710).

``search_clients`` and the name resolution behind ``get_client_accts``
used to unpack the search result blindly, so a backend outage read as
zero matches: an agent told there is no client may go create a
duplicate. These tests pin the distinction that motivated the fix: a
search that could not run reports itself, a genuine miss stays a miss.

Same failure class as issue #570 ("an outage must never become 'not
found'"), which pinned the pre-fetch lookups in test_prefetch_failure.

No test here touches the network — the search is replaced with a stub.
"""

from collections.abc import Callable
from typing import Any

import pytest
from fastmcp.exceptions import ToolError

import mcp_server

# Strings observed from the real backend and from controlled failure modes.
_EMPTY_PAGE = {"pageItems": []}
_CONNECTION_DOWN = (
    "Connection failed: ('Connection aborted.', "
    "RemoteDisconnected('Remote end closed connection without response'))"
)
_TIMEOUT = "Connection failed: Read timed out. (read timeout=30)"
_SERVICE_OUT = {"error": "Fineract Error: Service not available.", "status_code": 503}


class _Stub:
    """Stand-in for a fastmcp StructuredTool — wrappers only read `.func`."""

    func: Callable[..., Any]

    def __init__(self, payload: Any) -> None:
        self.func = lambda *args, **kwargs: payload




# ── _resolve_client_id: the sentinel must survive resolution ──────────


def test_resolve_client_id_propagates_the_sentinel(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub(_SERVICE_OUT))
    assert mcp_server._resolve_client_id("Maria") is _SERVICE_OUT


def test_resolve_client_id_still_resolves_a_match(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        mcp_server, "search_clients_by_name", _Stub([{"entityId": 42}])
    )
    assert mcp_server._resolve_client_id("Maria") == 42


def test_resolve_client_id_still_reports_a_genuine_miss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub(_EMPTY_PAGE))
    assert mcp_server._resolve_client_id("Maria") is None


# ── search_clients: an outage is not an empty match ───────────────────


def test_outage_is_not_an_empty_match(monkeypatch: pytest.MonkeyPatch) -> None:
    """The #710 headline symptom: a failed search reporting zero clients.

    ``safe_tool`` turns the propagated sentinel into a ``ToolError`` (FastMCP
    reports ``isError: true``) instead of the old successful empty list.
    """
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub({"error": _CONNECTION_DOWN}))

    with pytest.raises(ToolError) as exc_info:
        mcp_server.search_clients(nameQuery="Maria")

    assert "Connection failed" in str(exc_info.value)


def test_timeout_and_service_outage_surface_too(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    for payload in ({"error": _TIMEOUT}, _SERVICE_OUT):
        monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub(payload))

        with pytest.raises(ToolError) as exc_info:
            mcp_server.search_clients(nameQuery="Maria")

        assert str(exc_info.value) == payload["error"]


def test_genuine_miss_still_returns_no_clients(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The friendly case must survive: an empty page is a real miss."""
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub(_EMPTY_PAGE))
    assert mcp_server.search_clients(nameQuery="Maria") == {"clients": []}


def test_bare_empty_array_is_still_a_miss(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub([]))
    assert mcp_server.search_clients(nameQuery="Maria") == {"clients": []}


def test_matches_are_still_returned(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        mcp_server,
        "search_clients_by_name",
        _Stub([{"entityId": 42, "entityName": "Maria Lopez"}]),
    )
    result = mcp_server.search_clients(nameQuery="Maria")

    assert result == {
        "clients": [
            {
                "clientId": 42,
                "displayName": "Maria Lopez",
                "accountNo": None,
                "status": None,
            }
        ]
    }


# ── get_client_accts: name resolution must not mask an outage ─────────


def test_name_lookup_outage_is_not_a_missing_client(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """An outage mid-resolution must not answer 'No client found'."""
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub({"error": _CONNECTION_DOWN}))

    with pytest.raises(ToolError) as exc_info:
        mcp_server.get_client_accts(nameQuery="Maria")

    assert "Connection failed" in str(exc_info.value)
    assert "No client found" not in str(exc_info.value)


def test_name_lookup_genuine_miss_keeps_the_friendly_message(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A genuine miss keeps its friendly wording — ``safe_tool`` delivers it
    as a ``ToolError`` too, because the tool reports it as an error dict."""
    monkeypatch.setattr(mcp_server, "search_clients_by_name", _Stub(_EMPTY_PAGE))

    with pytest.raises(ToolError, match="No client found for name 'Maria'"):
        mcp_server.get_client_accts(nameQuery="Maria")


def test_name_lookup_resolves_and_fetches_accounts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The happy path is unchanged: a match resolves and fetches accounts."""
    monkeypatch.setattr(
        mcp_server, "search_clients_by_name", _Stub([{"entityId": 42}])
    )
    fetched: list[int] = []

    def _record(client_id: int) -> dict[str, Any]:
        fetched.append(client_id)
        return {"loanAccounts": [], "savingsAccounts": []}

    accounts = _Stub(None)
    accounts.func = _record
    monkeypatch.setattr(mcp_server, "get_client_accounts", accounts)

    result = mcp_server.get_client_accts(nameQuery="Maria")

    assert result == {"resolvedClientId": 42, "loanAccounts": [], "savingsAccounts": []}
    assert fetched == [42]
