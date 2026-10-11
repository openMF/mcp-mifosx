# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import pytest

from tools.domains.loans import update_loan
from tools.mcp_adapter import fineract_client


@pytest.fixture
def pending_loan():
    # A weekly flat-interest loan on a product other than the default. These
    # fields must not be reset when the caller changes only the principal.
    return {
        "loanProductId": 17,
        "status": {"value": "Submitted and pending approval"},
        "principal": 1000,
        "termFrequency": 12,
        "termPeriodFrequencyType": {"id": 1, "value": "Weeks"},
        "numberOfRepayments": 6,
        "repaymentEvery": 2,
        "repaymentFrequencyType": {"id": 1, "value": "Weeks"},
        "amortizationType": {"id": 0},
        "interestType": {"id": 1},
        "interestCalculationPeriodType": {"id": 0},
        "interestRatePerPeriod": 1.5,
        "timeline": {"submittedOnDate": [2026, 1, 2], "expectedDisbursementDate": [2026, 1, 3]},
    }


@pytest.mark.parametrize("changes, expected", [
    ({"principal": 2500}, {"locale": "en", "principal": "2500"}),
    ({"product_id": 22}, {"locale": "en", "productId": 22}),
    ({"months": 8}, {"locale": "en", "loanTermFrequency": 8, "loanTermFrequencyType": 2,
                     "numberOfRepayments": 8, "repaymentEvery": 1, "repaymentFrequencyType": 2}),
    ({"principal": 2500, "product_id": 22}, {"locale": "en", "principal": "2500", "productId": 22}),
])
def test_updates_only_requested_terms(pending_loan, changes, expected):
    with patch.object(fineract_client, "execute_get", return_value=pending_loan), patch.object(
        fineract_client, "execute_put", return_value={"resourceId": 5}
    ) as put:
        assert update_loan.func(5, **changes) == {"resourceId": 5}
    put.assert_called_once_with("loans/5", expected)


def test_no_changes_does_not_write(pending_loan):
    with patch.object(fineract_client, "execute_get", return_value=pending_loan), patch.object(fineract_client, "execute_put") as put:
        assert "error" in update_loan.func(5)
    put.assert_not_called()


def test_active_loan_remains_uneditable(pending_loan):
    pending_loan["status"] = {"value": "Active"}
    with patch.object(fineract_client, "execute_get", return_value=pending_loan), patch.object(fineract_client, "execute_put") as put:
        assert "error" in update_loan.func(5, principal=2500)
    put.assert_not_called()


def test_fetch_error_is_preserved():
    error = {"error": "Connection failed"}
    with patch.object(fineract_client, "execute_get", return_value=error), patch.object(fineract_client, "execute_put") as put:
        assert update_loan.func(5, principal=2500) == error
    put.assert_not_called()
