# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import pytest

import mcp_server
from tools.domains.loans import get_loan_history
from tools.mcp_adapter import fineract_client


@pytest.mark.parametrize(
    ("raw_date", "expected"),
    [([2026, 11, 5], "2026-11-5"), ("2026-11-05", "2026-11-05")],
    ids=["array", "iso-string"],
)
def test_loan_history_accepts_both_fineract_date_formats(raw_date, expected):
    response = {
        "transactions": [{"id": 3, "date": raw_date}],
        "charges": [{"chargeId": 7, "dueDate": raw_date}],
        "timeline": {"actualDisbursementDate": raw_date},
    }
    tool = getattr(mcp_server.get_loan_hist, "fn", mcp_server.get_loan_hist)
    with patch.object(fineract_client, "execute_get", return_value=response):
        history = get_loan_history.func(9)
        result = tool(loanId=9)

    assert history["transactions"][0]["date"] == expected
    assert history["charges"][0]["dueDate"] == expected
    assert history["disbursedOn"] == expected
    assert result["transactions"][0]["date"] == "5 November 2026"


@pytest.mark.parametrize("include_nulls", [False, True], ids=["omitted", "null"])
def test_missing_dates_do_not_hide_valid_loan_transactions(include_nulls):
    transaction = {"id": 3}
    charge = {"chargeId": 7}
    timeline = {}
    if include_nulls:
        transaction["date"] = None
        charge["dueDate"] = None
        timeline["actualDisbursementDate"] = None
    response = {
        "transactions": [transaction, {"id": 4, "date": [2026, 11, 5]}],
        "charges": [charge],
        "timeline": timeline,
    }
    tool = getattr(mcp_server.get_loan_hist, "fn", mcp_server.get_loan_hist)
    with patch.object(fineract_client, "execute_get", return_value=response):
        history = get_loan_history.func(9)
        result = tool(loanId=9)

    assert history["charges"][0]["dueDate"] == ""
    assert history["disbursedOn"] == ""
    assert [(t["transactionId"], t["date"]) for t in result["transactions"]] == [
        (3, ""),
        (4, "5 November 2026"),
    ]
