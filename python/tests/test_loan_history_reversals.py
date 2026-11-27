# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, you can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import mcp_server
from tools.domains.loans import get_loan_history
from tools.mcp_adapter import fineract_client


def test_loan_history_excludes_reversed_repayments():
    repayment_type = {"value": "Repayment"}
    response = {
        "transactions": [
            {"id": 3, "type": repayment_type, "amount": 100, "manuallyReversed": True},
            {"id": 4, "type": repayment_type, "amount": 200, "manuallyReversed": False},
            {"id": 5, "type": repayment_type, "amount": 300, "reversed": True, "manuallyReversed": False},
        ],
    }
    tool = getattr(mcp_server.get_loan_hist, "fn", mcp_server.get_loan_hist)
    with patch.object(fineract_client, "execute_get", return_value=response):
        history = get_loan_history.func(9)
        result = tool(loanId=9)

    assert history["transactionCount"] == 1
    assert [transaction["id"] for transaction in history["transactions"]] == [4]
    assert [(transaction["transactionId"], transaction["amount"]) for transaction in result["transactions"]] == [(4, 200)]
