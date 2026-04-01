# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from tools.suggestion_engine import suggest_tools


def test_suggest_tools_loan_query_returns_loan_tools():
    result = suggest_tools("show loan details")
    assert result
    assert result[0] == "get_loan_details"
    assert "create_loan" in result


def test_suggest_tools_client_query_returns_client_tools():
    result = suggest_tools("create new client")
    assert result
    assert result[0] == "create_client"


def test_suggest_tools_empty_query_returns_empty_list():
    assert suggest_tools("") == []


def test_suggest_tools_unrelated_query_returns_empty_list():
    assert suggest_tools("weather forecast for tomorrow") == []
