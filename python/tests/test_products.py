# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch


@patch("tools.domains.products.fineract_client")
def test_list_loan_products(mock_client):
    from tools.domains.products import list_loan_products

    mock_client.execute_get.return_value = [
        {
            "id": 1, "name": "SILVER", "shortName": "SLV",
            "currency": {"code": "USD"}, "minPrincipal": 1000,
            "maxPrincipal": 50000, "principal": 10000,
            "interestRatePerPeriod": 5.0, "minInterestRatePerPeriod": 1.0,
            "maxInterestRatePerPeriod": 10.0, "repaymentEvery": 1,
            "repaymentFrequencyType": {"value": "Months"},
            "numberOfRepayments": 12
        }
    ]
    result = list_loan_products.func()
    assert len(result["loanProducts"]) == 1
    assert result["loanProducts"][0]["name"] == "SILVER"


@patch("tools.domains.products.fineract_client")
def test_list_loan_products_error(mock_client):
    from tools.domains.products import list_loan_products

    mock_client.execute_get.return_value = {"error": "Unauthorized"}
    result = list_loan_products.func()
    assert "error" in result


@patch("tools.domains.products.fineract_client")
def test_get_loan_product(mock_client):
    from tools.domains.products import get_loan_product

    mock_client.execute_get.return_value = {
        "id": 1, "name": "SILVER", "shortName": "SLV", "description": "Silver loan",
        "currency": {"code": "USD"}, "principal": 10000,
        "minPrincipal": 1000, "maxPrincipal": 50000,
        "interestRatePerPeriod": 5.0, "minInterestRatePerPeriod": 1.0,
        "maxInterestRatePerPeriod": 10.0,
        "interestType": {"value": "Flat"}, "amortizationType": {"value": "Equal installments"},
        "repaymentEvery": 1, "repaymentFrequencyType": {"value": "Months"},
        "numberOfRepayments": 12, "charges": []
    }
    result = get_loan_product.func(1)
    assert result["name"] == "SILVER"
    assert result["interestRate_pct"] == 5.0


@patch("tools.domains.products.fineract_client")
def test_list_savings_products(mock_client):
    from tools.domains.products import list_savings_products

    mock_client.execute_get.return_value = [
        {
            "id": 1, "name": "Regular Savings", "shortName": "RS",
            "currency": {"code": "USD"}, "nominalAnnualInterestRate": 3.5,
            "minRequiredOpeningBalance": 100, "withdrawalFeeForTransfers": False
        }
    ]
    result = list_savings_products.func()
    assert len(result["savingsProducts"]) == 1
    assert result["savingsProducts"][0]["name"] == "Regular Savings"


@patch("tools.domains.products.fineract_client")
def test_get_savings_product(mock_client):
    from tools.domains.products import get_savings_product

    mock_client.execute_get.return_value = {
        "id": 2, "name": "Premium", "shortName": "PM", "description": "Premium savings",
        "currency": {"code": "USD"}, "nominalAnnualInterestRate": 5.0,
        "interestCompoundingPeriodType": {"value": "Monthly"},
        "interestPostingPeriodType": {"value": "Monthly"},
        "interestCalculationType": {"value": "Daily Balance"},
        "minRequiredOpeningBalance": 500, "lockinPeriodFrequency": 6,
        "lockinPeriodFrequencyType": {"value": "Months"},
        "withdrawalFeeForTransfers": True, "charges": []
    }
    result = get_savings_product.func(2)
    assert result["name"] == "Premium"
    assert result["nominalAnnualInterestRate"] == 5.0
