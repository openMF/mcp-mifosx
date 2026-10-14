# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

from tools.domains.loans import reschedule_loan
from tools.mcp_adapter import fineract_client


def test_sends_the_reschedule_reason() -> None:
    with patch("tools.domains.loans.get_fineract_today", return_value="14 October 2026"), patch.object(
        fineract_client, "execute_post", return_value={"resourceId": 1}
    ) as post:
        result = reschedule_loan.func(1, "12 December 2026", extra_terms=2, reschedule_reason_id=30)
    assert result == {"resourceId": 1}
    post.assert_called_once_with(
        "rescheduleloans",
        {
            "loanId": 1,
            "rescheduleFromDate": "12 December 2026",
            "submittedOnDate": "14 October 2026",
            "rescheduleReasonId": 30,
            "rescheduleReasonComment": "Rescheduled via AI Agent",
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
            "extraTerms": 2,
        },
    )


def test_without_a_reason_lists_the_configured_ones_and_does_not_submit() -> None:
    template = {"rescheduleReasons": [
        {"id": 30, "name": "Customer request", "active": True},
        {"id": 31, "name": "Old reason", "active": False},
        {"id": 32, "name": "Natural disaster", "active": True},
    ]}
    with patch.object(fineract_client, "execute_get", return_value=template) as get, patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = reschedule_loan.func(1, "12 December 2026", extra_terms=2)
    get.assert_called_once_with("rescheduleloans/template")
    post.assert_not_called()
    assert "reschedule_reason_id is required" in result["error"]
    # The list is in the message, which is all an agent gets through safe_tool
    assert "30 = Customer request; 32 = Natural disaster" in result["error"]
    assert "Old reason" not in result["error"]
    assert result["reschedule_reasons"] == [
        {"id": 30, "name": "Customer request"},
        {"id": 32, "name": "Natural disaster"},
    ]


def test_without_a_reason_and_none_configured() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"rescheduleReasons": []}), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = reschedule_loan.func(1, "12 December 2026", extra_terms=2)
    post.assert_not_called()
    assert "LoanRescheduleReason" in result["error"]


def test_without_a_reason_when_the_template_fails() -> None:
    with patch.object(fineract_client, "execute_get", return_value={"error": "boom", "status_code": 503}), patch.object(
        fineract_client, "execute_post"
    ) as post:
        result = reschedule_loan.func(1, "12 December 2026", extra_terms=2)
    post.assert_not_called()
    assert "could not be loaded: boom" in result["error"]
