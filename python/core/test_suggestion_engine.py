import pytest
from core.suggestion_engine import generate_suggestions


def test_overdue_loans():
    data = [{"id": 101}]
    result = generate_suggestions("get_overdue_loans", data)

    # Text suggestions
    assert len(result["suggestions"]) > 0
    assert any("loan 101" in s for s in result["suggestions"])

    # Structured suggestions
    assert len(result["suggestions_structured"]) > 0
    assert any(item["loanId"] == 101 for item in result["suggestions_structured"])


def test_active_loan():
    data = {"loanId": 102, "status": "active"}
    result = generate_suggestions("get_loan_details", data)

    # Text
    assert any("repayment" in s.lower() for s in result["suggestions"])

    # Structured
    assert any(item["action"] == "make_repayment" for item in result["suggestions_structured"])


def test_pending_loan():
    data = {"loanId": 103, "status": "pending"}
    result = generate_suggestions("get_loan_details", data)

    # Text
    assert any("approve" in s.lower() for s in result["suggestions"])

    # Structured
    assert any(item["action"] == "approve_loan" for item in result["suggestions_structured"])


def test_savings_account():
    data = {"accountId": 201}
    result = generate_suggestions("get_savings_account", data)

    # Text
    assert any("deposit" in s.lower() for s in result["suggestions"])

    # Structured
    assert any(item["action"] == "deposit_savings" for item in result["suggestions_structured"])


def test_client_details():
    data = {"clientId": 301}
    result = generate_suggestions("get_client_details", data)

    # Text
    assert any("accounts" in s.lower() for s in result["suggestions"])

    # Structured
    assert any(item["action"] == "get_client_accounts" for item in result["suggestions_structured"])


def test_empty_data():
    result = generate_suggestions("get_overdue_loans", [])

    assert result["suggestions"] == []
    assert result["suggestions_structured"] == []


def test_invalid_data_type():
    result = generate_suggestions("get_overdue_loans", {})

    assert result["suggestions"] == []
    assert result["suggestions_structured"] == []


def test_unknown_intent():
    result = generate_suggestions("unknown", {})

    assert result["suggestions"] == []
    assert result["suggestions_structured"] == []


def test_max_limit():
    data = [{"id": i} for i in range(10)]
    result = generate_suggestions("get_overdue_loans", data)

    assert len(result["suggestions"]) <= 5
    assert len(result["suggestions_structured"]) <= 5


def test_invalid_savings_data():
    result = generate_suggestions("get_savings_account", {})

    assert result["suggestions"] == []
    assert result["suggestions_structured"] == []


def test_invalid_client_data():
    result = generate_suggestions("get_client_details", {})

    assert result["suggestions"] == []
    assert result["suggestions_structured"] == []