# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import pytest

from tools.domains.loans import create_group_loan, create_loan
from tools.mcp_adapter import fineract_client


@pytest.fixture
def flat_product_template() -> dict:
    # A product that charges 1.5% flat with a different repayment strategy: none of
    # these may be replaced by the old hardcoded 5% declining balance terms.
    return {
        "interestRatePerPeriod": 1.5,
        "interestType": {"id": 1, "value": "Flat"},
        "amortizationType": {"id": 0, "value": "Equal principal payments"},
        "interestCalculationPeriodType": {"id": 0, "value": "Daily"},
        "transactionProcessingStrategyCode": "principal-interest-penalties-fees-order-strategy",
    }


def test_individual_loan_uses_the_product_terms(flat_product_template: dict) -> None:
    with patch("tools.domains.loans.get_fineract_today", return_value="14 October 2026"), patch.object(
        fineract_client, "execute_get", return_value=flat_product_template
    ) as get, patch.object(fineract_client, "execute_post", return_value={"loanId": 9}) as post:
        assert create_loan.func(3, 10000, 12, product_id=4) == {"loanId": 9}
    get.assert_called_once_with("loans/template?templateType=individual&clientId=3&productId=4")
    endpoint, payload = post.call_args.args
    assert endpoint == "loans"
    assert payload["interestRatePerPeriod"] == 1.5
    assert payload["interestType"] == 1
    assert payload["amortizationType"] == 0
    assert payload["interestCalculationPeriodType"] == 0
    assert payload["transactionProcessingStrategyCode"] == "principal-interest-penalties-fees-order-strategy"
    # The schedule still follows the tool's months convention
    assert payload["loanTermFrequency"] == 12
    assert payload["numberOfRepayments"] == 12
    assert payload["repaymentFrequencyType"] == 2
    assert payload["clientId"] == 3 and payload["productId"] == 4


def test_group_loan_uses_the_group_template(flat_product_template: dict) -> None:
    with patch.object(fineract_client, "execute_get", return_value=flat_product_template) as get, patch.object(
        fineract_client, "execute_post", return_value={"loanId": 10}
    ) as post:
        create_group_loan.func(5, 6000, 6, product_id=2)
    get.assert_called_once_with("loans/template?templateType=group&groupId=5&productId=2")
    payload = post.call_args.args[1]
    assert payload["groupId"] == 5
    assert payload["interestRatePerPeriod"] == 1.5
    assert payload["interestType"] == 1


def test_no_loan_is_created_when_the_product_terms_cannot_be_read() -> None:
    with patch.object(
        fineract_client, "execute_get", return_value={"error": "Loan product not found", "status_code": 404}
    ), patch.object(fineract_client, "execute_post") as post:
        result = create_loan.func(3, 10000, 12, product_id=99)
    post.assert_not_called()
    assert "Could not load the terms of loan product 99" in result["error"]


def test_no_loan_is_created_when_the_product_has_no_rate(flat_product_template: dict) -> None:
    flat_product_template["interestRatePerPeriod"] = None
    with patch.object(fineract_client, "execute_get", return_value=flat_product_template), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = create_loan.func(3, 10000, 12, product_id=4)
    post.assert_not_called()
    assert "interestRatePerPeriod" in result["error"]
