# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

from tools.domains.reports import run_report
from tools.mcp_adapter import fineract_client


def test_adds_the_r_prefix_fineract_expects() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"data": []}) as get:
        run_report.func("Client Listing", {"officeId": "1"})
    get.assert_called_once_with("runreports/Client%20Listing", params={"R_officeId": "1"})


def test_keeps_parameters_that_already_have_the_prefix() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"data": []}) as get:
        run_report.func("Active Loans - Summary", {"R_officeId": "1", "currencyId": "-1"})
    get.assert_called_once_with(
        "runreports/Active%20Loans%20-%20Summary", params={"R_officeId": "1", "R_currencyId": "-1"}
    )


def test_encodes_the_report_name_as_one_path_segment() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"data": []}) as get:
        run_report.func("Income/Expense Summary")
    get.assert_called_once_with("runreports/Income%2FExpense%20Summary", params={})
