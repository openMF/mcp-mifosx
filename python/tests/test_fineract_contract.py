# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Check the payloads the write tools send against what Fineract accepts.

The other tests mock the Fineract client too, but each one asserts the payload
its author expected. These tests assert against contract/fineract_write_contract.json
instead: the fields Fineract's own validators allow and require for each operation,
and which ids are tenant data. Tenant data (payment types, closure reasons, charges,
reschedule reasons) differs per server, so a tool may only send an id the user chose,
never a default.

Each tool is called the way an agent calls it, through the MCP wrapper, against a
fake Fineract that answers the tool's look-ups. Only the POST is checked.

Set MIFOSX_LIVE_CONTRACT=1 (with the usual MIFOSX_BASE_URL and credentials) to also
send each captured payload to a real Fineract; see test_fineract_validator_accepts_the_payload.
"""

import json
import os
import re
from dataclasses import dataclass, field
from inspect import signature
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple
from unittest.mock import patch

import pytest

import mcp_server
from tools.mcp_adapter import fineract_client

CONTRACT = json.loads((Path(__file__).resolve().parents[2] / "contract" / "fineract_write_contract.json").read_text(encoding="utf-8"))
OPERATIONS: Dict[str, Dict[str, Any]] = CONTRACT["operations"]


@dataclass(frozen=True)
class Scenario:
    """One agent call: the tool, the Fineract operation it performs and the arguments the agent gives."""

    name: str
    tool: str
    operation: str
    args: Dict[str, Any]
    # Tool argument -> payload field, for ids that are tenant data. The test
    # calls the tool with and without them.
    tenant_args: Dict[str, str] = field(default_factory=dict)


SCENARIOS = [
    Scenario("create_new_charge-loan", "create_new_charge", "charge.create", {"name": "Late payment fee", "amount": 15}),
    Scenario("create_new_charge-savings", "create_new_charge", "charge.create",
             {"name": "Withdrawal fee", "amount": 2, "chargeAppliesTo": 2, "chargeTimeType": 5}),
    Scenario("close_client_profile", "close_client_profile", "client.close",
             {"clientId": 5, "closureReasonId": 41}, {"closureReasonId": "closureReasonId"}),
    Scenario("deposit", "deposit", "savings.deposit",
             {"accountId": 8, "amount": 50, "paymentTypeId": 4}, {"paymentTypeId": "paymentTypeId"}),
    Scenario("withdraw", "withdraw", "savings.withdrawal",
             {"accountId": 8, "amount": 20, "paymentTypeId": 4}, {"paymentTypeId": "paymentTypeId"}),
    Scenario("make_repayment", "make_repayment", "loan.repayment",
             {"loanId": 3, "amount": 100, "paymentTypeId": 4}, {"paymentTypeId": "paymentTypeId"}),
    Scenario("apply_loan_fee", "apply_loan_fee", "loan.add_charge",
             {"loanId": 3, "feeAmount": 15, "chargeId": 5}, {"chargeId": "chargeId"}),
    Scenario("apply_savings_fee", "apply_savings_fee", "savings.add_charge",
             {"accountId": 8, "amount": 15, "chargeId": 7}, {"chargeId": "chargeId"}),
    Scenario("reschedule_loan_app", "reschedule_loan_app", "loan.reschedule",
             {"loanId": 3, "rescheduleFromDate": "12 December 2026", "extraTerms": 2, "rescheduleReasonId": 30},
             {"rescheduleReasonId": "rescheduleReasonId"}),
]

# Bugs these tests found that are not fixed on main yet (empty = none known): (test, scenario) -> reason.
# strict, so the fix's PR fails here until it deletes its line.
KNOWN_BUGS = {
}


def _params(test: str, scenarios: List[Scenario]) -> List[Any]:
    params = []
    for scenario in scenarios:
        reason = KNOWN_BUGS.get((test, scenario.name))
        marks = [pytest.mark.xfail(reason=reason, strict=True)] if reason else []
        params.append(pytest.param(scenario, id=scenario.name, marks=marks))
    return params


# --- the contract check -------------------------------------------------------

def _endpoint_pattern(operation: Dict[str, Any]) -> "re.Pattern[str]":
    path = re.escape(operation["path"]).replace(re.escape("{id}"), r"\d+")
    command = re.escape(f"?command={operation['command']}") if "command" in operation else ""
    return re.compile(path + command)


def _applies(condition: Dict[str, Any], payload: Dict[str, Any]) -> bool:
    return all(payload.get(key) == value for key, value in condition.items())


def contract_violations(operation_name: str, endpoint: str, payload: Dict[str, Any], chosen: Dict[str, Any]) -> List[str]:
    """Everything about this POST that Fineract would reject, or that records data the user didn't choose.

    `chosen` holds the tenant ids the user picked, by payload field.
    """
    operation = OPERATIONS[operation_name]
    problems = []
    if not _endpoint_pattern(operation).fullmatch(endpoint):
        problems.append(f"posted to {endpoint!r}, expected {operation['path']} {operation.get('command', '')}".rstrip())
    for name in sorted(set(payload) - set(operation["allowed"])):
        problems.append(f"{name}: not a parameter Fineract accepts here")
    for name, why in operation.get("forbidden", {}).items():
        if name in payload:
            problems.append(f"{name}: {why}")
    required = list(operation["required"])
    for rule in operation.get("required_when", []):
        if _applies(rule["if"], payload):
            required += rule["then"]
    for name in required:
        if payload.get(name) in (None, ""):
            problems.append(f"{name}: required by Fineract, not sent")
    for rule in operation.get("one_of_when", []):
        name = rule["field"]
        if _applies(rule["if"], payload) and name in payload and payload[name] not in rule["in"]:
            problems.append(f"{name}={payload[name]!r}: Fineract only accepts {rule['in']} when {rule['if']}")
    for name in operation.get("tenant_ids", []):
        if name not in payload or payload[name] == chosen.get(name):
            continue
        if name in chosen:
            problems.append(f"{name}={payload[name]!r}: the user chose {chosen[name]!r}")
        else:
            problems.append(f"{name}={payload[name]!r}: a tenant-specific id the user never chose")
    return problems


# --- calling a tool the way an agent does ------------------------------------

def _fake_fineract_get(endpoint: str, params: Optional[Dict[str, Any]] = None) -> Any:
    """Answers the look-ups the tools make before they write."""
    path = endpoint.split("?")[0]
    if re.fullmatch(r"loans/\d+", path):
        return {"id": 3, "status": {"value": "Active"}}
    if re.fullmatch(r"savingsaccounts/\d+", path):
        return {"id": 8, "status": {"value": "Active"}, "summary": {"availableBalance": 1000}}
    if path == "charges":
        return [{"id": 5, "name": "Late payment fee", "amount": 15, "active": True,
                 "chargeAppliesTo": {"value": "Loan"}, "chargeTimeType": {"value": "Specified due date"}},
                {"id": 7, "name": "Statement fee", "amount": 15, "active": True,
                 "chargeAppliesTo": {"value": "Savings"}, "chargeTimeType": {"value": "Specified due date"}}]
    if path == "rescheduleloans/template":
        return {"rescheduleReasons": [{"id": 30, "name": "Customer request", "active": True}]}
    if path == "clients/template":
        return {"narrations": [{"id": 41, "name": "Moved away"}]}
    if path == "paymenttypes":
        return [{"id": 4, "name": "Cash", "isCashPayment": True}]
    return {"error": f"Fineract fake has no answer for GET {endpoint}", "status_code": 404}


def _call(scenario: Scenario, with_tenant_ids: bool = True) -> Tuple[List[Tuple[str, Dict[str, Any]]], Dict[str, Any], Any]:
    """Call the MCP tool and return (POSTs made, tenant ids the user chose, tool result)."""
    tool = getattr(mcp_server, scenario.tool)
    fn = getattr(tool, "fn", tool)
    accepted = signature(fn).parameters
    args = {key: value for key, value in scenario.args.items()
            if key in accepted and (with_tenant_ids or key not in scenario.tenant_args)}
    chosen = {scenario.tenant_args[key]: value for key, value in args.items() if key in scenario.tenant_args}
    posts = []

    def fake_post(endpoint: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        posts.append((endpoint, dict(payload)))
        return {"resourceId": 1}

    with patch.object(fineract_client, "execute_get", side_effect=_fake_fineract_get), patch.object(
        fineract_client, "execute_post", side_effect=fake_post
    ):
        try:
            result = fn(**args)
        except Exception as exc:  # safe_tool raises ToolError for {"error": ...}
            result = {"error": str(exc)}
    return posts, chosen, result


# --- tests -------------------------------------------------------------------

@pytest.mark.parametrize("scenario", _params("contract", SCENARIOS))
def test_payload_meets_the_contract(scenario: Scenario) -> None:
    posts, chosen, result = _call(scenario)
    assert len(posts) == 1, f"expected one POST, got {posts} (result: {result})"
    endpoint, payload = posts[0]
    problems = contract_violations(scenario.operation, endpoint, payload, chosen)
    assert not problems, "; ".join(problems)


@pytest.mark.parametrize("scenario", _params("defaults", [s for s in SCENARIOS if s.tenant_args]))
def test_tenant_ids_are_never_defaulted(scenario: Scenario) -> None:
    # Without the id the tool may ask the user (post nothing) or leave the
    # field out, but whatever it posts must still be a valid request.
    posts, _, _ = _call(scenario, with_tenant_ids=False)
    for endpoint, payload in posts:
        problems = contract_violations(scenario.operation, endpoint, payload, chosen={})
        assert not problems, "; ".join(problems)


@pytest.mark.parametrize("scenario", _params("docs", [SCENARIOS[0]]))
def test_documented_codes_match_fineract(scenario: Scenario) -> None:
    # The agent picks codes from the tool description, so a wrong one there
    # is a wrong request later.
    codes = OPERATIONS[scenario.operation]["codes"]
    tool = getattr(mcp_server, scenario.tool)
    doc = getattr(tool, "fn", tool).__doc__ or ""

    def norm(label: str) -> str:
        return re.sub(r"[^a-z0-9%]", "", label.lower())

    wrong = []
    for line in doc.splitlines():
        found = re.match(r"\s*(\w+):\s*(.*)", line)
        if not found or found.group(1) not in codes:
            continue
        for code, label in re.findall(r"(\d+)\s*=\s*([^,]+)", found.group(2)):
            known = codes[found.group(1)].get(code, [])
            if norm(label) not in {norm(name) for name in known}:
                wrong.append(f"{found.group(1)} {code}={label.strip()!r}, Fineract: {known or 'no such code'}")
    assert not wrong, "; ".join(wrong)


LIVE = os.getenv("MIFOSX_LIVE_CONTRACT") == "1"
MISSING_ID = 2147483647


@pytest.mark.live
@pytest.mark.skipif(not LIVE, reason="set MIFOSX_LIVE_CONTRACT=1 and MIFOSX_BASE_URL to check against a real Fineract")
@pytest.mark.parametrize("scenario", [s for s in SCENARIOS if OPERATIONS[s.operation].get("live_probe")], ids=lambda s: s.name)
def test_fineract_validator_accepts_the_payload(scenario: Scenario) -> None:
    """Send the tool's payload to a real Fineract, for an account that does not exist.

    For these operations Fineract validates the request body before it loads the
    account, so a 404 means every field passed the validator and nothing was written;
    a 400 is the validator rejecting the payload.
    """
    posts, _, _ = _call(scenario)
    assert len(posts) == 1
    endpoint, payload = posts[0]
    probe = re.sub(r"/\d+", f"/{MISSING_ID}", endpoint, count=1)
    result = fineract_client.execute_post(probe, payload)
    assert isinstance(result, dict) and result.get("status_code") == 404, result
