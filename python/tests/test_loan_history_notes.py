# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import pytest

import mcp_server
from tools.mcp_adapter import fineract_client


def test_loan_history_matches_notes_to_transactions_by_id():
    response = {
        "transactions": [
            {"id": 3, "type": {"value": "Repayment"}},
            {"id": 4, "type": {"value": "Repayment"}},
            {"id": 5, "type": {"value": "Repayment"}, "reversed": True},
            {"id": 6, "type": {"value": "Repayment"}},
        ],
        "notes": [
            {"loanTransactionId": 6, "note": "Paid by bank transfer"},
            {"loanTransactionId": 5, "note": "Voided payment"},
            {"loanTransactionId": None, "note": "Loan approved"},
            {"loanTransactionId": 3, "note": "Paid in branch"},
            {"loanTransactionId": 3, "note": "Older note"},
        ],
    }
    tool = getattr(mcp_server.get_loan_hist, "fn", mcp_server.get_loan_hist)
    with patch.object(fineract_client, "execute_get", return_value=response) as execute_get:
        result = tool(loanId=9)

    assert [(txn["transactionId"], txn["note"]) for txn in result["transactions"]] == [
        (3, "Paid in branch"),
        (4, None),
        (6, "Paid by bank transfer"),
    ]
    execute_get.assert_called_once_with("loans/9?associations=transactions,repaymentSchedule,charges,notes")


@pytest.mark.parametrize("notes_field", [{}, {"notes": None}, {"notes": []}], ids=["omitted", "null", "empty"])
def test_loan_history_returns_null_when_there_are_no_notes(notes_field):
    response = {"transactions": [{"id": 3}], **notes_field}
    tool = getattr(mcp_server.get_loan_hist, "fn", mcp_server.get_loan_hist)
    with patch.object(fineract_client, "execute_get", return_value=response):
        result = tool(loanId=9)

    assert result["transactions"][0]["note"] is None
