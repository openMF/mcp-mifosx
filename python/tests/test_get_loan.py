# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""get_loan returns the loan's external ID, so an agent can match a loan to an external workflow."""

from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.mcp_adapter import fineract_client

LOAN: Dict[str, Any] = {
    "id": 42,
    "accountNo": "000000042",
    "loanProductName": "Individual Loan",
    "status": {"value": "Active"},
    "loanType": {"value": "Individual"},
    "approvedPrincipal": 5000.0,
    "summary": {"totalOutstanding": 4500.0},
    "currency": {"code": "USD", "displaySymbol": "$"},
    "annualInterestRate": 12.0,
    "timeline": {
        "submittedOnDate": [2026, 1, 15],
        "approvedOnDate": [2026, 1, 16],
        "actualDisbursementDate": [2026, 1, 17],
        "expectedMaturityDate": [2026, 7, 17],
    },
    "numberOfRepayments": 6,
    "repaymentEvery": 1,
    "repaymentFrequencyType": {"value": "Months"},
}


def _call(loan: Dict[str, Any]) -> Dict[str, Any]:
    fn = getattr(mcp_server.get_loan, "fn", mcp_server.get_loan)
    with patch.object(fineract_client, "execute_get", return_value=loan) as get:
        result = fn(loanId=42)
    get.assert_called_once_with("loans/42")
    return result


def test_returns_the_external_id() -> None:
    result = _call({**LOAN, "externalId": "wf-loan-98765"})
    assert result["data"]["externalId"] == "wf-loan-98765"
    assert result["data"]["loanId"] == 42


def test_external_id_is_none_when_unset() -> None:
    result = _call(LOAN)
    assert result["data"]["externalId"] is None
    assert result["data"]["loanId"] == 42


def test_error_raises_tool_error() -> None:
    with pytest.raises(ToolError, match="Loan not found"):
        _call({"error": "Loan not found"})
