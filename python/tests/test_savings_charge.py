# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

from tools.domains.savings import apply_savings_charge
from tools.mcp_adapter import fineract_client


def test_apply_savings_charge_payload() -> None:
    with patch("tools.domains.savings.get_fineract_today", return_value="14 October 2026"), patch.object(
        fineract_client, "execute_post", return_value={"resourceId": 1}
    ) as post:
        assert apply_savings_charge.func(7, 15, charge_id=3) == {"resourceId": 1}
    post.assert_called_once_with(
        "savingsaccounts/7/charges",
        {
            "chargeId": 3,
            "amount": 15,
            "dueDate": "14 October 2026",
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        },
    )


def test_apply_savings_charge_leaves_fee_month_day_to_the_charge() -> None:
    # Fineract's add-charge validator parses feeOnMonthDay without a monthDayFormat,
    # so any value fails with HTTP 500. Left out, Fineract uses the date set on the
    # charge definition (needed only for annual fees).
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 1}) as post:
        apply_savings_charge.func(7, 15)
    assert "feeOnMonthDay" not in post.call_args.args[1]
