# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from tools.validation import validate_tool_params


def test_validate_tool_params_accepts_valid_inputs():
    params = {
        "loanId": 10,
        "amount": 500.25,
        "months": 12,
        "rescheduleFromDate": "15 March 2026",
    }
    assert validate_tool_params(params) is None


def test_validate_tool_params_rejects_invalid_id():
    result = validate_tool_params({"loanId": 0})
    assert result is not None
    assert result["httpStatusCode"] == 400
    assert result["validation"]["field"] == "loanId"


def test_validate_tool_params_rejects_invalid_amount():
    result = validate_tool_params({"amount": -1})
    assert result is not None
    assert result["httpStatusCode"] == 400
    assert result["validation"]["field"] == "amount"


def test_validate_tool_params_rejects_empty_date_string():
    result = validate_tool_params({"transactionDate": ""})
    assert result is not None
    assert result["httpStatusCode"] == 400
    assert result["validation"]["field"] == "transactionDate"
