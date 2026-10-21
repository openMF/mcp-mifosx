# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Tests for how the agent-skill handles the payment type on a transaction.

Fineract's ``SavingsAccountTransactionDataValidator`` has ``paymentTypeId`` as
``notNull``, so a savings deposit or withdrawal cannot post without one, while a loan
repayment goes through with no payment detail at all (``LoanTransactionValidatorImpl``
uses ``ignoreIfNull()``). The payment types below are the ones Fineract's
``0002_initial_data.xml`` seeds, and none of them is cash, which is why no id can be
guessed: the ids are tenant data.

Run: pytest agent-skill/tests/test_payment_type.py -v
"""

import json
from typing import Any

import httpx
import pytest
from fineract_skill import client as client_module
from fineract_skill import get_tool
from fineract_skill.client import FineractClient
from fineract_skill.domains.loans import make_repayment
from fineract_skill.domains.savings import deposit, withdraw

BASE_URL = "https://fineract.example/fineract-provider/api/v1"

# Seeded by Fineract's 0002_initial_data.xml (changeSet 23).
PAYMENT_TYPES = [
    {"id": 1, "name": "Money Transfer", "isCashPayment": False, "isActive": True},
    {"id": 2, "name": "Repayment Adjustment Chargeback", "isCashPayment": False, "isActive": True},
    {"id": 3, "name": "Repayment Adjustment Refund", "isCashPayment": False, "isActive": True},
]

STOCK_LISTING = "1 = Money Transfer; 2 = Repayment Adjustment Chargeback; 3 = Repayment Adjustment Refund"


@pytest.fixture
def posted() -> list[dict[str, Any]]:
    """The payloads of every POST made during a test."""
    return []


@pytest.fixture
def fineract(monkeypatch: pytest.MonkeyPatch, posted: list[dict[str, Any]]) -> FineractClient:
    """A client whose POSTs are recorded and answered by a fake Fineract."""
    real_client = httpx.Client

    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            posted.append(json.loads(request.content) if request.content else {})
            return httpx.Response(200, json={"resourceId": 1})
        if request.url.path.endswith("paymenttypes"):
            return httpx.Response(200, json=PAYMENT_TYPES)
        return httpx.Response(404, json={})

    monkeypatch.setattr(client_module.httpx, "Client", lambda **kw: real_client(transport=httpx.MockTransport(handler), **kw))
    return FineractClient(base_url=BASE_URL, username="mifos", password="password")


def test_deposit_without_a_payment_type_posts_nothing(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = deposit(fineract, 1, 500.0)

    assert result["ok"] is False
    assert posted == []


def test_the_error_lists_the_tenant_payment_types_as_id_equals_name(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = deposit(fineract, 1, 500.0)

    assert STOCK_LISTING in result["error"]
    assert result["payment_types"] == [{"id": p["id"], "name": p["name"]} for p in PAYMENT_TYPES]


def test_withdraw_without_a_payment_type_posts_nothing(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = withdraw(fineract, 1, 100.0)

    assert result["ok"] is False
    assert "withdrawal" in result["error"]
    assert STOCK_LISTING in result["error"]
    assert posted == []


def test_deposit_posts_the_payment_type_it_was_given(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = deposit(fineract, 1, 500.0, payment_type_id=3)

    assert result["ok"] is True
    assert len(posted) == 1
    assert posted[0]["paymentTypeId"] == 3
    assert posted[0]["transactionAmount"] == 500.0


def test_withdraw_posts_the_payment_type_it_was_given(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = withdraw(fineract, 1, 100.0, payment_type_id=1)

    assert result["ok"] is True
    assert len(posted) == 1
    assert posted[0]["paymentTypeId"] == 1


def test_repayment_omits_the_field_when_it_is_left_out(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    # A loan repayment needs no payment type, so nothing is guessed at.
    result = make_repayment(fineract, 7, 250.0)

    assert result["ok"] is True
    assert len(posted) == 1
    assert "paymentTypeId" not in posted[0]


def test_repayment_posts_the_field_when_it_is_given(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = make_repayment(fineract, 7, 250.0, payment_type_id=2)

    assert result["ok"] is True
    assert posted[0]["paymentTypeId"] == 2


def test_a_savings_transaction_never_asks_the_agent_to_guess(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    # Two failed attempts must not have posted anything in between.
    deposit(fineract, 1, 500.0)
    withdraw(fineract, 1, 100.0)
    deposit(fineract, 1, 500.0, payment_type_id=1)

    assert len(posted) == 1
    assert posted[0]["paymentTypeId"] == 1


def test_the_registry_advertises_the_new_parameter() -> None:
    # Each parameter is optional in the schema so that an agent which leaves it out
    # gets told the configured payment types instead of being rejected by its own
    # framework before the handler can say what is missing.
    for tool_name in ("deposit", "withdraw", "make_repayment"):
        spec = get_tool(tool_name)
        parameter = {p.name: p for p in spec.parameters}["payment_type_id"]
        assert parameter.type == "integer"
        assert parameter.required is False


def test_the_registry_tells_the_agent_the_field_is_required_on_savings() -> None:
    for tool_name in ("deposit", "withdraw"):
        spec = get_tool(tool_name)
        parameter = {p.name: p for p in spec.parameters}["payment_type_id"]
        assert "Fineract requires one" in parameter.description
        assert "this tenant's payment types" in parameter.description


def test_deposit_rejects_a_zero_payment_type(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    # Fineract rejects paymentTypeId with integerGreaterThanZero, so posting it is a
    # pointless round trip with an opaque message.
    result = deposit(fineract, 1, 500.0, payment_type_id=0)

    assert result["ok"] is False
    assert "greater than zero" in result["error"]
    assert posted == []


def test_withdraw_rejects_a_negative_payment_type(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = withdraw(fineract, 1, 100.0, payment_type_id=-1)

    assert result["ok"] is False
    assert "greater than zero" in result["error"]
    assert posted == []


def test_repayment_rejects_a_non_positive_payment_type(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = make_repayment(fineract, 7, 250.0, payment_type_id=0)

    assert result["ok"] is False
    assert "greater than zero" in result["error"]
    assert posted == []


def test_the_rejection_tells_the_agent_how_to_fix_it(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    result = deposit(fineract, 1, 500.0, payment_type_id=0)

    # An id of 0 is not a hint that the field should be dropped, so the message has to
    # say what to do instead.
    assert "leave it out to be told the configured ones" in result["error"]


def test_one_is_still_accepted(fineract: FineractClient, posted: list[dict[str, Any]]) -> None:
    # 1 is a real payment type on a stock Fineract, so the guard must not be a range
    # check that lets anything through.
    result = deposit(fineract, 1, 500.0, payment_type_id=1)

    assert result["ok"] is True
    assert posted[0]["paymentTypeId"] == 1
