# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from tools.registry import DomainRegistry


def test_get_domain_loans():
    registry = DomainRegistry()
    tools = registry.get_domain("loans")
    tool_names = [t.name for t in tools]
    assert "get_loan_details" in tool_names
    assert "create_loan" in tool_names


def test_get_domain_unknown():
    registry = DomainRegistry()
    tools = registry.get_domain("nonexistent")
    assert tools == []


def test_get_all_tools():
    registry = DomainRegistry()
    all_tools = registry.get_all_tools()
    names = [t.name for t in all_tools]
    # Verify no duplicates
    assert len(names) == len(set(names))
    # Verify tools from multiple domains present
    assert "get_loan_details" in names
    assert "get_savings_account" in names
    assert "list_staff" in names


def test_route_intent_loans():
    registry = DomainRegistry()
    tools = registry.route_intent("Show me the repayment schedule for loan 5")
    tool_names = [t.name for t in tools]
    assert "get_repayment_schedule" in tool_names


def test_route_intent_savings():
    registry = DomainRegistry()
    tools = registry.route_intent("What is the savings balance?")
    tool_names = [t.name for t in tools]
    assert "get_savings_account" in tool_names


def test_route_intent_multi_domain():
    registry = DomainRegistry()
    tools = registry.route_intent("Show me this client's loan and savings accounts")
    tool_names = [t.name for t in tools]
    # Should match both clients and loans and savings
    assert "get_client_details" in tool_names or "search_clients_by_name" in tool_names
    assert "get_loan_details" in tool_names
    assert "get_savings_account" in tool_names


def test_route_intent_default_clients():
    registry = DomainRegistry()
    tools = registry.route_intent("hello there")
    tool_names = [t.name for t in tools]
    # Default should be clients domain
    assert "search_clients_by_name" in tool_names


def test_route_intent_reports():
    registry = DomainRegistry()
    tools = registry.route_intent("run the portfolio report")
    tool_names = [t.name for t in tools]
    assert "run_report" in tool_names


def test_route_intent_accounting():
    registry = DomainRegistry()
    tools = registry.route_intent("Show me journal entries")
    tool_names = [t.name for t in tools]
    assert "get_journal_entries" in tool_names
