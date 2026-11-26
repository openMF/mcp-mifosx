# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""create_new_charge never sent chargePaymentMode, which Fineract requires for loan charges,
and it created every charge in USD unless told otherwise."""

import inspect
from typing import Any, Dict
from unittest.mock import patch

import pytest
from fastmcp.exceptions import ToolError

import mcp_server
from tools.domains import charges
from tools.mcp_adapter import fineract_client


def _call(**kwargs: Any) -> Dict[str, Any]:
    """Call the MCP tool the way an agent does and return the payload it posts."""
    fn = getattr(mcp_server.create_new_charge, "fn", mcp_server.create_new_charge)
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 4}) as post:
        assert fn(**kwargs) == {"resourceId": 4}
    endpoint, payload = post.call_args.args
    assert endpoint == "charges"
    return payload


def test_loan_charge_is_sent_with_a_payment_mode() -> None:
    # The tool's defaults create a loan charge with a specified due date.
    # Without chargePaymentMode Fineract answers 400 (validation.msg.charge.chargePaymentMode.cannot.be.blank).
    payload = _call(name="Late payment fee", amount=15, currencyCode="USD")
    assert payload["chargeAppliesTo"] == 1
    assert payload["chargeTimeType"] == 2
    assert payload["chargePaymentMode"] == 0  # regular: paid with the loan's repayments


def test_savings_and_client_charges_are_sent_without_one() -> None:
    # Fineract only reads chargePaymentMode for loan charges, so other charges are left as they were
    assert "chargePaymentMode" not in _call(name="Activation fee", amount=5, currencyCode="USD", chargeAppliesTo=2,
                                             chargeTimeType=3)
    assert "chargePaymentMode" not in _call(name="Onboarding fee", amount=50, currencyCode="USD", chargeAppliesTo=3,
                                             chargeTimeType=2)


def test_documented_savings_time_types_match_fineract() -> None:
    # The docstring is the tool description the agent reads to choose chargeTimeType. Fineract's
    # ChargeTimeType has 3 = savings activation and 5 = withdrawal fee; 8 and 9 are the
    # loan instalment and overdue fees, which Fineract rejects for a savings charge.
    doc = mcp_server.create_new_charge.__doc__ or ""
    assert "3=Savings Activation" in doc
    assert "5=Withdrawal Fee" in doc
    assert "8=Savings Activation" not in doc
    assert "9=Withdrawal Fee" not in doc


def test_charge_is_created_in_the_currency_given() -> None:
    # Fineract only attaches a charge to a loan or savings account in the same currency
    # ("Charge and Loan must have the same currency."), so a KES tenant needs a KES charge.
    assert _call(name="Late payment fee", amount=15, currencyCode=" KES ")["currencyCode"] == "KES"


def test_currency_has_no_default() -> None:
    assert inspect.signature(mcp_server.create_new_charge).parameters["currencyCode"].default is inspect.Parameter.empty
    assert inspect.signature(charges.create_charge.func).parameters["currency_code"].default is inspect.Parameter.empty


@pytest.mark.parametrize("currency", ["", "   "])
def test_blank_currency_posts_nothing(currency: str) -> None:
    fn = getattr(mcp_server.create_new_charge, "fn", mcp_server.create_new_charge)
    with patch.object(fineract_client, "execute_post") as post:
        with pytest.raises(ToolError, match="currencyCode"):
            fn(name="Late payment fee", amount=15, currencyCode=currency)
        assert "currency_code is required" in charges.create_charge.func("Late payment fee", 15, currency)["error"]
    post.assert_not_called()
