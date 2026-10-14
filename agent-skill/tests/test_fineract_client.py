# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Tests for the error messages of the mifos-agent Fineract client.

Fineract answers a failed request with a generic top-level ``developerMessage`` and the
specific reason in ``errors[]``. The bodies below are real responses from Fineract
(demo.mifos.community), so the tests check what an agent actually sees.

Run: pytest agent-skill/tests/test_fineract_client.py -v
"""

import httpx
import pytest
from fineract_skill import client as client_module
from fineract_skill.client import FineractClient, FineractError
from fineract_skill.domains.loans import get_loan_details

BASE_URL = "https://fineract.example/fineract-provider/api/v1"

GENERIC_INVALID = "The request was invalid. This typically will happen due to validation errors which are provided."
GENERIC_NOT_FOUND = "The requested resource is not available."

# POST /clients without lastname
MISSING_LASTNAME = {
    "developerMessage": GENERIC_INVALID,
    "httpStatusCode": "400",
    "defaultUserMessage": "Validation errors exist.",
    "userMessageGlobalisationCode": "validation.msg.validation.errors.exist",
    "errors": [
        {
            "developerMessage": "The parameter `lastname` is mandatory.",
            "defaultUserMessage": "The parameter `lastname` is mandatory.",
            "userMessageGlobalisationCode": "validation.msg.client.lastname.cannot.be.blank",
            "parameterName": "lastname",
            "args": [{}],
        }
    ],
}

# GET /loans/99999999
LOAN_NOT_FOUND = {
    "developerMessage": GENERIC_NOT_FOUND,
    "httpStatusCode": "404",
    "defaultUserMessage": GENERIC_NOT_FOUND,
    "userMessageGlobalisationCode": "error.msg.resource.not.found",
    "errors": [
        {
            "developerMessage": "Loan with identifier 99999999 does not exist",
            "defaultUserMessage": "Loan with identifier 99999999 does not exist",
            "userMessageGlobalisationCode": "error.msg.loan.id.invalid",
            "parameterName": "id",
            "args": [{"value": 99999999}],
        }
    ],
}


def _response(status: int, **kwargs) -> httpx.Response:
    return httpx.Response(status, request=httpx.Request("GET", BASE_URL), **kwargs)


def test_validation_error_reports_the_failed_parameter() -> None:
    assert FineractClient._parse_error(_response(400, json=MISSING_LASTNAME)) == "The parameter `lastname` is mandatory."


def test_not_found_reports_which_resource() -> None:
    assert FineractClient._parse_error(_response(404, json=LOAN_NOT_FOUND)) == "Loan with identifier 99999999 does not exist"


def test_every_reason_is_reported_in_order() -> None:
    body = dict(MISSING_LASTNAME)
    body["errors"] = [
        {"developerMessage": "The parameter `lastname` is mandatory."},
        {"defaultUserMessage": "The parameter `officeId` is mandatory."},
        {"userMessageGlobalisationCode": "no.message"},
        "not-a-dict",
    ]
    assert FineractClient._parse_error(_response(400, json=body)) == (
        "The parameter `lastname` is mandatory.; The parameter `officeId` is mandatory."
    )


@pytest.mark.parametrize(
    "body, expected",
    [
        ({"developerMessage": "Service not available", "errors": []}, "Service not available"),
        ({"defaultUserMessage": "Insufficient permissions"}, "Insufficient permissions"),
        ({"developerMessage": "Service not available", "errors": 5}, "Service not available"),
        ({"developerMessage": "Service not available", "errors": {"developerMessage": "x"}}, "Service not available"),
    ],
)
def test_top_level_message_when_no_specific_reason(body: dict, expected: str) -> None:
    assert FineractClient._parse_error(_response(503, json=body)) == expected


def test_non_json_body_keeps_status_and_text() -> None:
    assert FineractClient._parse_error(_response(502, text="Bad Gateway")) == "HTTP 502: Bad Gateway"


def test_json_that_is_not_an_object() -> None:
    assert FineractClient._parse_error(_response(500, json=["unexpected"])) == '["unexpected"]'


@pytest.fixture
def fineract(monkeypatch: pytest.MonkeyPatch) -> FineractClient:
    """A client whose HTTP calls are answered by a fake Fineract."""
    real_client = httpx.Client

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST" and request.url.path.endswith("/clients"):
            return httpx.Response(400, json=MISSING_LASTNAME)
        return httpx.Response(404, json=LOAN_NOT_FOUND)

    monkeypatch.setattr(client_module.httpx, "Client", lambda **kw: real_client(transport=httpx.MockTransport(handler), **kw))
    return FineractClient(base_url=BASE_URL, username="mifos", password="password")


def test_failed_request_raises_with_the_specific_reason(fineract: FineractClient) -> None:
    with pytest.raises(FineractError) as exc:
        fineract.post("clients", {"officeId": 1, "legalFormId": 1, "firstname": "Ana"})
    assert exc.value.status_code == 400
    assert str(exc.value) == "HTTP 400: The parameter `lastname` is mandatory."


def test_tool_result_tells_the_agent_what_went_wrong(fineract: FineractClient) -> None:
    result = get_loan_details(fineract, 99999999)
    assert result["ok"] is False
    assert "Loan with identifier 99999999 does not exist" in result["error"]
    assert GENERIC_NOT_FOUND not in result["error"]
