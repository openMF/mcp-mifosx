# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
Tests for the domain-aware action mapper and generate_suggestions.
"""

from core.action_mapper import get_actions_for_status
from core.suggestion_engine import generate_suggestions

# ── get_actions_for_status ──────────────────────────────────────────

def test_actions_loan_active():
    assert get_actions_for_status("loan", "Active") == ["repay", "view_schedule"]


def test_actions_loan_pending_approval():
    # Fineract returns the value string "Pending Approval", not "pending"
    assert get_actions_for_status("loan", "Pending Approval") == ["approve", "reject"]


def test_actions_client_inactive():
    assert get_actions_for_status("client", "Inactive") == ["activate"]


def test_actions_case_insensitive():
    assert get_actions_for_status("LOAN", "ACTIVE") == ["repay", "view_schedule"]


def test_actions_unknown_domain_returns_empty():
    assert get_actions_for_status("portfolio", "Active") == []


def test_actions_unknown_status_returns_empty():
    assert get_actions_for_status("loan", "Overpaid") == []


def test_actions_empty_inputs_return_empty():
    assert get_actions_for_status("", "Active") == []
    assert get_actions_for_status("loan", "") == []


# ── generate_suggestions: loan details ──────────────────────────────

def test_active_loan_suggests_repayment():
    suggestions = generate_suggestions(
        "get_loan_details", {"loanId": 456, "status": "Active"}
    )
    assert "Make a repayment for loan 456" in suggestions
    assert "View repayment schedule for loan 456" in suggestions


def test_pending_loan_suggests_approval():
    suggestions = generate_suggestions(
        "get_loan_details", {"loanId": 7, "status": "Pending Approval"}
    )
    assert "Approve loan 7" in suggestions
    assert "Reject loan 7" in suggestions


def test_closed_loan_has_no_suggestions():
    suggestions = generate_suggestions(
        "get_loan_details", {"loanId": 7, "status": "Closed"}
    )
    assert suggestions == []


def test_loan_details_rejects_non_dict_data():
    assert generate_suggestions("get_loan_details", ["not", "a", "dict"]) == []


# ── generate_suggestions: client details ────────────────────────────

def test_inactive_client_suggests_activation():
    suggestions = generate_suggestions(
        "get_client_details", {"clientId": 12, "status": "Inactive"}
    )
    assert "Activate client 12" in suggestions
    assert "View accounts for client 12" in suggestions


def test_active_client_suggests_view_accounts_only():
    suggestions = generate_suggestions(
        "get_client_details", {"clientId": 12, "status": "Active"}
    )
    assert suggestions == ["View accounts for client 12"]


# ── regressions: paths that already worked ──────────────────────────

def test_overdue_loans_suggestions_unchanged():
    suggestions = generate_suggestions(
        "get_overdue_loans", {"overdueLoans": [{"loanId": 9}]}
    )
    assert any("loan 9" in s for s in suggestions)


def test_savings_account_suggestions_unchanged():
    suggestions = generate_suggestions("get_savings_account", {"savingsId": 5})
    assert any("account 5" in s for s in suggestions)


def test_empty_data_returns_no_suggestions():
    assert generate_suggestions("get_loan_details", None) == []
    assert generate_suggestions("get_loan_details", {}) == []


def test_unknown_intent_returns_no_suggestions():
    assert generate_suggestions("no_such_intent", {"loanId": 1}) == []
