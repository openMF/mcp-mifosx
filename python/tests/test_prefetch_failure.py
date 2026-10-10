# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Tests for _prefetch_failure (issue #570).

Tools that look a record up before acting on it used to swap in a friendly
"not found" message unconditionally, so an unreachable backend was reported
as missing data. These tests pin the distinction that motivated the fix: a
genuine miss gets the friendly message, and everything else reports itself.

No test here touches the network — the pre-fetch is replaced with a stub.
"""

from pathlib import Path
from typing import Any, Callable, Dict

import pytest

import mcp_server
from mcp_server import _prefetch_failure

# Strings observed from the real backend and from controlled failure modes.
_REAL_MISSING = "Fineract Error: The requested resource is not available."
_VALIDATION_MISSING = "Validation Error: Loan with identifier 42 does not exist."
_CONNECTION_DOWN = (
    "Connection failed: ('Connection aborted.', "
    "RemoteDisconnected('Remote end closed connection without response'))"
)
_TIMEOUT = "Connection failed: Read timed out. (read timeout=30)"
_SERVER_ERROR = "Error 500: <!DOCTYPE html><html><body>Internal Server Error"


class _Stub:
    """Stand-in for a fastmcp StructuredTool — wrappers only read `.func`."""

    def __init__(self, payload: dict) -> None:
        self.func = lambda *args, **kwargs: payload


def _outcome(fn: Callable[..., Any], **kwargs: Any) -> Dict[str, Any]:
    """Call a tool and return its error payload, whichever way it arrives.

    On `main` a failed tool *returns* ``{"error": ...}``. Once #569 lands,
    its ``safe_tool`` guard instead raises ``ToolError``, which FastMCP
    reports as ``isError: true``. The message is identical either way, so
    these tests read it from whichever mechanism the current tree has.
    """
    try:
        return fn(**kwargs)
    except Exception as exc:
        return {"error": str(exc)}


def test_genuine_miss_gets_the_friendly_message() -> None:
    """The behaviour every caller expects when the record is really absent."""
    assert _prefetch_failure(
        {"error": _REAL_MISSING, "status_code": 404}, "Client ID 7 not found."
    ) == {"error": "Client ID 7 not found."}


def test_fineract_validation_wording_is_recognised_as_a_miss() -> None:
    """Fineract reports some absent records as a 400, not a 404."""
    assert _prefetch_failure({"error": _VALIDATION_MISSING}, "Loan gone") == {
        "error": "Loan gone"
    }


def test_unparseable_404_body_is_still_a_miss() -> None:
    """The adapter renders a 404 as plain text when the body is not JSON."""
    assert _prefetch_failure(
        {"error": "HTTP 404: Failed to parse error response."}, "Client ID 7 not found."
    ) == {"error": "Client ID 7 not found."}


def test_json_404_body_is_still_a_miss() -> None:
    """The adapter's other 404 wording, when the body did parse."""
    assert _prefetch_failure(
        {"error": "Error 404: {'errors': [{'defaultUserMessage': 'not found'}]}"},
        "Client ID 7 not found.",
    ) == {"error": "Client ID 7 not found."}


def test_backend_down_is_reported_as_itself() -> None:
    """The #570 bug: an outage must never become "not found"."""
    result = _prefetch_failure({"error": _CONNECTION_DOWN}, "Client ID 7 not found.")
    assert result == {"error": _CONNECTION_DOWN}
    assert "not found" not in result["error"]


def test_timeout_is_reported_as_itself() -> None:
    assert _prefetch_failure({"error": _TIMEOUT}, "Client ID 7 not found.") == {
        "error": _TIMEOUT
    }


def test_server_error_is_reported_as_itself() -> None:
    """A 5xx body can read like a missing resource; the status wins."""
    result = _prefetch_failure({"error": _SERVER_ERROR}, "Client ID 7 not found.")
    assert result == {"error": _SERVER_ERROR}


def test_service_wording_on_a_503_is_not_a_miss() -> None:
    """CodeRabbit's finding: "Service not available" is an outage, not a
    missing record, even though it overlaps the old missing wording."""
    result = _prefetch_failure(
        {"error": "Fineract Error: Service not available.", "status_code": 503},
        "Client ID 7 not found.",
    )
    assert result == {"error": "Fineract Error: Service not available.", "status_code": 503}
    assert "not found" not in result["error"]


def test_ambiguous_wording_without_a_status_passes_through() -> None:
    """Stripped of its status, "not available" no longer proves a miss."""
    raw = "Fineract Error: The requested resource is not available."
    assert _prefetch_failure({"error": raw}, "Client ID 7 not found.") == {
        "error": raw
    }


def test_auth_and_rate_limit_failures_are_not_misses() -> None:
    for status in (401, 403, 408, 429):
        payload = {"error": "Fineract Error: Service not available.", "status_code": status}
        assert _prefetch_failure(payload, "gone") is payload


def test_other_4xx_falls_back_to_the_wording() -> None:
    """Fineract reports some absent records as a 400, so wording still counts."""
    assert _prefetch_failure(
        {"error": _VALIDATION_MISSING, "status": 400}, "Loan gone"
    ) == {"error": "Loan gone"}


def test_fineract_status_object_is_not_mistaken_for_an_http_status() -> None:
    """Fineract payloads carry `status` as an *object*, and wrappers read
    ``.get("status", {}).get("value", "")``. The adapter must not reuse
    that key for the HTTP status or those reads raise AttributeError."""
    payload = {"error": _REAL_MISSING, "status": {"value": "invalid", "code": 400}}
    assert _prefetch_failure(payload, "Client ID 7 not found.") is payload


def test_unrecognised_error_passes_through() -> None:
    """Default to honesty: show the real text rather than guess."""
    raw = "Validation Error: The parameter journalEntries is not supported"
    assert _prefetch_failure({"error": raw}, "Client ID 7 not found.") == {
        "error": raw
    }


def test_extra_keys_are_preserved() -> None:
    """Some results carry more than the error sentinel."""
    payload = {"error": _CONNECTION_DOWN, "suggestions": ["try get_client"]}
    assert _prefetch_failure(payload, "gone") is payload


def test_non_dict_result_falls_back_to_the_friendly_message() -> None:
    """No detail to preserve, so the caller's message is the best answer."""
    assert _prefetch_failure(None, "Client ID 7 not found.") == {
        "error": "Client ID 7 not found."
    }


def test_dict_without_an_error_key_falls_back_to_the_friendly_message() -> None:
    """An unexpected shape should still produce a usable answer."""
    assert _prefetch_failure({"data": {}}, "Client ID 7 not found.") == {
        "error": "Client ID 7 not found."
    }


def test_wrapper_reports_outage_instead_of_not_found(monkeypatch: pytest.MonkeyPatch) -> None:
    """End-to-end through a real tool: #570's headline symptom."""
    monkeypatch.setattr(
        mcp_server, "get_loan_details", _Stub({"error": _CONNECTION_DOWN})
    )
    result = _outcome(mcp_server.approve_disburse_loan, loanId=42)

    assert "Connection failed" in result["error"]
    assert "not found" not in result["error"]


def test_wrapper_still_explains_a_missing_loan(monkeypatch: pytest.MonkeyPatch) -> None:
    """The friendly message must survive for the case it was written for."""
    monkeypatch.setattr(
        mcp_server,
        "get_loan_details",
        _Stub({"error": _REAL_MISSING, "status_code": 404}),
    )
    result = _outcome(mcp_server.approve_disburse_loan, loanId=42)

    assert result == {
        "error": "Loan ID 42 not found. Check get_client_accts to see valid loanIds."
    }


def test_wrapper_reports_outage_for_a_client_pre_fetch(monkeypatch: pytest.MonkeyPatch) -> None:
    """delete_client_profile pre-fetches the client before deleting it."""
    monkeypatch.setattr(
        mcp_server, "get_client_details", _Stub({"error": _CONNECTION_DOWN})
    )
    result = _outcome(mcp_server.delete_client_profile, clientId=7)

    assert "Connection failed" in result["error"]
    assert "not found" not in result["error"]


def test_no_masking_pattern_remains_in_server_source() -> None:
    """Regression guard: a pre-fetch may not discard its own error text.

    Looks for `return {"error": ...}` immediately after a pre-fetch guard,
    which is the shape that caused #570.
    """
    source = Path(mcp_server.__file__).read_text(encoding="utf-8")
    lines = source.split("\n")

    offenders = [
        i + 1
        for i, line in enumerate(lines)
        if line.strip().startswith('return {"error":')
        and i > 0
        and 'or "error" in' in lines[i - 1]
    ]
    assert offenders == [], f"masking still present at lines {offenders}"
