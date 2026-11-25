# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Tests for get_client_holistic_view MCP composite tool."""

import threading
from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.domains.clients import (
    get_client_accounts,
    get_client_charges,
    get_client_details,
    get_client_identifiers,
)

MOCK_DETAILS = {
    "id": 1,
    "displayName": "Octavio Paz",
    "firstname": "Octavio",
    "lastname": "Paz",
    "status": {"id": 300, "value": "Active"},
    "officeName": "Head Office",
}

MOCK_ACCOUNTS = {
    "loanAccounts": [{"id": 101, "productName": "Individual Loan"}],
    "savingsAccounts": [{"id": 201, "productName": "Savings"}],
}

MOCK_CHARGES = [
    {"id": 10, "name": "Maintenance Fee", "amount": 25.0}
]

MOCK_IDENTIFIERS = [
    {"id": 5, "documentKey": "ABC12345", "documentType": {"name": "National ID"}}
]


def _call_tool(client_id: Any) -> Dict[str, Any]:
    """Call the MCP-registered get_client_holistic_view tool wrapper."""
    return mcp_server.get_client_holistic_view(clientId=client_id)


# ── Success Case & Output Structure ──────────────────────────────────────────

def test_get_client_holistic_view_success_all_four_requests() -> None:
    """Test successful parallel fetch of all four client sub-resources."""
    with patch.object(get_client_details, "func", return_value=MOCK_DETAILS) as mock_details, \
         patch.object(get_client_accounts, "func", return_value=MOCK_ACCOUNTS) as mock_accounts, \
         patch.object(get_client_charges, "func", return_value=MOCK_CHARGES) as mock_charges, \
         patch.object(get_client_identifiers, "func", return_value=MOCK_IDENTIFIERS) as mock_identifiers:

        result = _call_tool(1)

        # Verify four-key structure matching Go composite tool
        assert set(result.keys()) == {"details", "accounts", "charges", "identifiers"}
        assert result["details"] == MOCK_DETAILS
        assert result["accounts"] == MOCK_ACCOUNTS
        assert result["charges"] == MOCK_CHARGES
        assert result["identifiers"] == MOCK_IDENTIFIERS

        # Verify all four domain callables were called with clientId 1
        mock_details.assert_called_once_with(1)
        mock_accounts.assert_called_once_with(1)
        mock_charges.assert_called_once_with(1)
        mock_identifiers.assert_called_once_with(1)


# ── Invalid Client ID Validation ─────────────────────────────────────────────

@pytest.mark.parametrize("bad_id", [0, -1, -99])
def test_get_client_holistic_view_rejects_non_positive_client_id(bad_id: int) -> None:
    """Existing resource ID validation blocks non-positive IDs before any domain call."""
    with patch.object(get_client_details, "func") as mock_details:
        with pytest.raises(ToolError, match="clientId must be a positive integer"):
            _call_tool(bad_id)

        mock_details.assert_not_called()


# ── Partial Failure Handling (Individual & Multiple) ─────────────────────────

def test_get_client_holistic_view_individual_endpoint_failure() -> None:
    """A failure in charges preserves useful error info while other keys succeed."""
    charge_error = {"error": "HTTP 500: Internal server error", "status_code": 500}

    with patch.object(get_client_details, "func", return_value=MOCK_DETAILS), \
         patch.object(get_client_accounts, "func", return_value=MOCK_ACCOUNTS), \
         patch.object(get_client_charges, "func", return_value=charge_error), \
         patch.object(get_client_identifiers, "func", return_value=MOCK_IDENTIFIERS):

        result = _call_tool(1)

        assert set(result.keys()) == {"details", "accounts", "charges", "identifiers"}
        assert result["details"] == MOCK_DETAILS
        assert result["accounts"] == MOCK_ACCOUNTS
        assert result["identifiers"] == MOCK_IDENTIFIERS
        assert result["charges"] == charge_error


def test_get_client_holistic_view_multiple_endpoint_failures() -> None:
    """Multiple failing endpoints capture errors under their keys without crashing."""
    account_error = {"error": "HTTP 404: Accounts not found", "status_code": 404}
    identifier_error = {"error": "Connection timed out"}

    with patch.object(get_client_details, "func", return_value=MOCK_DETAILS), \
         patch.object(get_client_accounts, "func", return_value=account_error), \
         patch.object(get_client_charges, "func", return_value=MOCK_CHARGES), \
         patch.object(get_client_identifiers, "func", return_value=identifier_error):

        result = _call_tool(1)

        assert result["details"] == MOCK_DETAILS
        assert result["accounts"] == account_error
        assert result["charges"] == MOCK_CHARGES
        assert result["identifiers"] == identifier_error


def test_get_client_holistic_view_subrequest_exception_isolation() -> None:
    """Unexpected exception in one sub-request is safely isolated to that key."""
    def _exploding_accounts(client_id: int):
        raise RuntimeError("Unexpected adapter crash")

    with patch.object(get_client_details, "func", return_value=MOCK_DETAILS), \
         patch.object(get_client_accounts, "func", side_effect=_exploding_accounts), \
         patch.object(get_client_charges, "func", return_value=MOCK_CHARGES), \
         patch.object(get_client_identifiers, "func", return_value=MOCK_IDENTIFIERS):

        result = _call_tool(1)

        assert result["details"] == MOCK_DETAILS
        assert result["charges"] == MOCK_CHARGES
        assert result["identifiers"] == MOCK_IDENTIFIERS
        assert "Unexpected adapter crash" in result["accounts"]["error"]


# ── Concurrency Verification (Without Wall-Clock Thresholds) ─────────────────

def test_get_client_holistic_view_executes_concurrently() -> None:
    """Verify execution occurs across concurrent threads using a threading barrier."""
    # A barrier of 4 requires all 4 threads to be actively executing simultaneously.
    # If calls were executed sequentially, a barrier of 4 would hang or timeout.
    barrier = threading.Barrier(4, timeout=3.0)
    thread_ids = set()
    lock = threading.Lock()

    def _concurrent_worker(client_id: int, payload: Any):
        with lock:
            thread_ids.add(threading.get_ident())
        # All 4 workers must reach this barrier concurrently to pass
        barrier.wait()
        return payload

    with patch.object(get_client_details, "func", side_effect=lambda cid: _concurrent_worker(cid, MOCK_DETAILS)), \
         patch.object(get_client_accounts, "func", side_effect=lambda cid: _concurrent_worker(cid, MOCK_ACCOUNTS)), \
         patch.object(get_client_charges, "func", side_effect=lambda cid: _concurrent_worker(cid, MOCK_CHARGES)), \
         patch.object(get_client_identifiers, "func", side_effect=lambda cid: _concurrent_worker(cid, MOCK_IDENTIFIERS)):

        result = _call_tool(1)

        assert set(result.keys()) == {"details", "accounts", "charges", "identifiers"}
        # Verify execution spanned multiple concurrent worker threads
        assert len(thread_ids) > 1
        assert barrier.broken is False
