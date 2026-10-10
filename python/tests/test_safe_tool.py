# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
Tests for the tool-result guard and Fineract error parsing.

These cover the three failure modes the guard normalises before a result
reaches an MCP client: bare JSON arrays (rejected against the declared
`-> dict` schema), the adapter's `{"error": ...}` sentinel (otherwise
returned as a *successful* response), and `ValidationError` from input
checks. None of them open a network connection.
"""

from inspect import signature
from typing import Any, Dict

import pytest
from fastmcp.exceptions import ToolError

from core.validation_engine import validate_input
from mcp_server import safe_tool
from tools.mcp_adapter import FineractAdapter

# ── bare arrays → keyed dict ─────────────────────────────────────────

def test_bare_list_wrapped_under_given_key():
    """A collection response must satisfy the declared dict schema."""

    @safe_tool("list_things", list_key="things")
    def list_things() -> Dict[str, Any]:
        return [{"id": 1}, {"id": 2}]

    assert list_things() == {"things": [{"id": 1}, {"id": 2}]}


def test_bare_list_defaults_to_items_key():
    @safe_tool("list_things")
    def list_things() -> Dict[str, Any]:
        return [{"id": 1}]

    assert list_things() == {"items": [{"id": 1}]}


def test_empty_list_is_still_wrapped():
    """An empty array is still an array and would fail the same way."""

    @safe_tool("list_things", list_key="things")
    def list_things() -> Dict[str, Any]:
        return []

    assert list_things() == {"things": []}


# ── error sentinel → real MCP error ──────────────────────────────────

def test_error_sentinel_becomes_tool_error():
    """An `{"error": ...}` payload must not look like a successful call."""

    @safe_tool("deposit")
    def deposit() -> Dict[str, Any]:
        return {"error": "Savings account ID 999999 not found."}

    with pytest.raises(ToolError, match="999999"):
        deposit()


def test_error_sentinel_survives_extra_keys():
    """`get_overdue_loans` merges `suggestions` onto the payload, so the
    sentinel can arrive with more than one key. It must still raise."""

    @safe_tool("get_overdue_loans")
    def get_overdue_loans() -> Dict[str, Any]:
        return {"error": "Loan ID 7 not found.", "suggestions": []}

    with pytest.raises(ToolError, match="Loan ID 7"):
        get_overdue_loans()


def test_non_error_dict_passes_through_unchanged():
    @safe_tool("get_office")
    def get_office() -> Dict[str, Any]:
        return {"id": 1, "name": "Head Office", "errors": []}

    assert get_office() == {"id": 1, "name": "Head Office", "errors": []}


def test_unexpected_exception_becomes_tool_error():
    """A crash must surface as isError, not as a normal-looking payload."""

    @safe_tool("boom")
    def boom() -> Dict[str, Any]:
        raise RuntimeError("connection reset")

    with pytest.raises(ToolError, match="Internal tool execution error"):
        boom()


# ── validation actually runs ─────────────────────────────────────────

def test_validation_blocks_negative_amount():
    """`validate_input` was defined but never applied before this."""
    from mcp_server import make_repayment

    with pytest.raises(ToolError, match="amount must be positive"):
        make_repayment(loanId=1, amount=-5)


def test_validation_blocks_non_positive_loan_id():
    from mcp_server import make_repayment

    with pytest.raises(ToolError, match="loanId must be a positive integer"):
        make_repayment(loanId=0, amount=10)


def test_validation_stops_before_any_request():
    """The guard must reject bad input ahead of the domain call."""
    called = []

    @safe_tool("make_repayment")
    def make_repayment(loanId: int, amount: float) -> Dict[str, Any]:
        called.append(True)
        return {"ok": True}

    with pytest.raises(ToolError):
        make_repayment(loanId=1, amount=-5)

    assert called == [], "domain function ran despite invalid input"


def test_validate_input_is_a_no_op_for_uncovered_tools():
    """Only get_loan and make_repayment have rules today; everything
    else must pass through untouched."""
    validate_input("list_all_offices", {"officeId": -5})
    validate_input("delete_client_profile", {"clientId": -5})


# ── signature preservation ──────────────────────────────────────────

def test_guard_preserves_signature_for_schema_generation():
    """FastMCP derives the input schema from the signature, so the
    wrapper must not obscure the parameters."""

    @safe_tool("get_client")
    def get_client(clientId: int, verbose: bool = False) -> Dict[str, Any]:
        return {"clientId": clientId}

    params = signature(get_client).parameters
    assert list(params) == ["clientId", "verbose"]
    assert params["verbose"].default is False
    assert get_client.__name__ == "get_client"


# ── Fineract error parsing ───────────────────────────────────────────

class _FakeResponse:
    """Minimal stand-in for requests.Response."""

    def __init__(self, payload: Any, status_code: int = 400):
        self._payload = payload
        self.status_code = status_code
        self.text = str(payload)

    def json(self) -> Any:
        if isinstance(self._payload, Exception):
            raise self._payload
        return self._payload


@pytest.fixture
def adapter() -> FineractAdapter:
    return FineractAdapter()


def test_specific_detail_wins_over_generic_message(adapter):
    """Fineract sends a generic developerMessage that claims validation
    errors 'are provided' while hiding the actual reason in errors[]."""
    response = _FakeResponse({
        "developerMessage": "The request was invalid. This typically will "
                            "happen due to validation errors which are "
                            "provided.",
        "errors": [
            {"developerMessage": "The parameter journalEntries is not "
                                 "supported."},
        ],
    })

    message = adapter._parse_fineract_error(response)

    assert "journalEntries is not supported" in message


def test_every_listed_error_is_reported(adapter):
    response = _FakeResponse({
        "developerMessage": "The request was invalid.",
        "errors": [
            {"developerMessage": "amount must be positive"},
            {"developerMessage": "loanId is required"},
        ],
    })

    message = adapter._parse_fineract_error(response)

    assert "amount must be positive" in message
    assert "loanId is required" in message


def test_falls_back_to_generic_message(adapter):
    """When no specific reason exists the generic text is still useful."""
    response = _FakeResponse({
        "developerMessage": "The requested resource is not available.",
    })

    message = adapter._parse_fineract_error(response)

    assert "The requested resource is not available" in message


def test_falls_back_to_status_and_body(adapter):
    response = _FakeResponse({"unexpected": "shape"}, status_code=502)

    message = adapter._parse_fineract_error(response)

    assert "502" in message


def test_non_dict_body_still_reports_status(adapter):
    """A JSON array body must not fall into the parse-failure branch and
    lose the status code."""
    response = _FakeResponse(["not", "a", "dict"], status_code=500)

    message = adapter._parse_fineract_error(response)

    assert "500" in message
    assert "Failed to parse" not in message


def test_unparseable_body_reports_status_not_a_crash(adapter):
    response = _FakeResponse(ValueError("no json"), status_code=503)

    message = adapter._parse_fineract_error(response)

    assert "503" in message
    assert "Failed to parse" in message
