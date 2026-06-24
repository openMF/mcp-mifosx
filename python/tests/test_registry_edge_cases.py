# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
Supplementary tests for DomainRegistry that cover domains and edge-cases
not exercised by test_registry.py.
"""

from tools.registry import DomainRegistry


# ── Groups domain ────────────────────────────────────────────────────

def test_route_intent_groups():
    registry = DomainRegistry()
    tools = registry.route_intent("List all groups in the branch")
    tool_names = [t.name for t in tools]
    assert any("group" in n for n in tool_names), (
        f"Expected a group-related tool, got {tool_names}"
    )


def test_route_intent_center():
    registry = DomainRegistry()
    tools = registry.route_intent("Show center details")
    tool_names = [t.name for t in tools]
    assert any("group" in n or "center" in n for n in tool_names), (
        f"Expected a group/center tool, got {tool_names}"
    )


# ── Staff domain ─────────────────────────────────────────────────────

def test_route_intent_staff():
    registry = DomainRegistry()
    tools = registry.route_intent("Who are the loan officers?")
    tool_names = [t.name for t in tools]
    assert "list_staff" in tool_names


def test_route_intent_employee():
    registry = DomainRegistry()
    tools = registry.route_intent("Find employee details")
    tool_names = [t.name for t in tools]
    assert any("staff" in n for n in tool_names), (
        f"Expected a staff-related tool, got {tool_names}"
    )


# ── Products domain ──────────────────────────────────────────────────

def test_route_intent_products():
    registry = DomainRegistry()
    tools = registry.route_intent("Show all loan products")
    tool_names = [t.name for t in tools]
    assert any("product" in n for n in tool_names), (
        f"Expected a product-related tool, got {tool_names}"
    )


# ── Charges domain ───────────────────────────────────────────────────

def test_route_intent_charges():
    registry = DomainRegistry()
    tools = registry.route_intent("What charges apply to this loan?")
    tool_names = [t.name for t in tools]
    assert any("charge" in n or "fee" in n for n in tool_names), (
        f"Expected a charge/fee tool, got {tool_names}"
    )


def test_route_intent_fee():
    registry = DomainRegistry()
    tools = registry.route_intent("List all fees")
    tool_names = [t.name for t in tools]
    assert any("charge" in n or "fee" in n for n in tool_names), (
        f"Expected a charge/fee tool, got {tool_names}"
    )


# ── Code-tables domain ──────────────────────────────────────────────

def test_route_intent_code_tables():
    registry = DomainRegistry()
    tools = registry.route_intent("Show me the code values")
    tool_names = [t.name for t in tools]
    assert any("code" in n for n in tool_names), (
        f"Expected a code-table tool, got {tool_names}"
    )


# ── Case insensitivity ──────────────────────────────────────────────

def test_route_intent_case_insensitive():
    registry = DomainRegistry()
    lower = registry.route_intent("show loan details")
    upper = registry.route_intent("SHOW LOAN DETAILS")
    mixed = registry.route_intent("Show Loan Details")
    lower_names = sorted(t.name for t in lower)
    upper_names = sorted(t.name for t in upper)
    mixed_names = sorted(t.name for t in mixed)
    assert lower_names == upper_names == mixed_names, (
        "Routing should be case-insensitive"
    )


# ── Deduplication ────────────────────────────────────────────────────

def test_route_intent_no_duplicate_tools():
    registry = DomainRegistry()
    tools = registry.route_intent(
        "Show this client's loan, savings, charges, and group info"
    )
    tool_names = [t.name for t in tools]
    assert len(tool_names) == len(set(tool_names)), (
        f"Duplicate tools returned: {tool_names}"
    )


# ── Default / fallback behaviour ────────────────────────────────────

def test_route_intent_empty_string():
    registry = DomainRegistry()
    tools = registry.route_intent("")
    assert len(tools) > 0, "Empty query should still return default tools"


def test_route_intent_whitespace_only():
    registry = DomainRegistry()
    tools = registry.route_intent("   ")
    assert len(tools) > 0, "Whitespace query should still return default tools"


def test_route_intent_numeric_only():
    registry = DomainRegistry()
    tools = registry.route_intent("12345")
    assert len(tools) > 0, "Numeric query should still return default tools"


# ── Utility methods ──────────────────────────────────────────────────

def test_get_domain_returns_list():
    registry = DomainRegistry()
    for domain in ("loans", "clients", "savings", "groups", "staff",
                   "accounting", "reports", "products", "charges",
                   "codetables"):
        tools = registry.get_domain(domain)
        assert isinstance(tools, list), (
            f"get_domain('{domain}') should return a list"
        )


def test_get_all_tools_non_empty():
    registry = DomainRegistry()
    all_tools = registry.get_all_tools()
    assert len(all_tools) > 0, "get_all_tools() should return at least one tool"
