from core.suggestion_engine import generate_suggestions


def test_overdue_loans():
    data = [{"id": 101}]
    result = generate_suggestions("get_overdue_loans", data)

    assert "Apply late fee to loan 101" in result
    assert "Send reminder for loan 101" in result


def test_active_loan():
    data = {"loanId": 102, "status": "active"}
    result = generate_suggestions("get_loan_details", data)

    assert "Make repayment for loan 102" in result


def test_pending_loan():
    data = {"loanId": 103, "status": "pending"}
    result = generate_suggestions("get_loan_details", data)

    assert "Approve loan 103" in result


def test_empty_data():
    result = generate_suggestions("get_overdue_loans", [])
    assert result == []


def test_invalid_data():
    result = generate_suggestions("get_loan_details", [])
    assert result == []


def test_unknown_intent():
    result = generate_suggestions("unknown", {})
    assert result == []