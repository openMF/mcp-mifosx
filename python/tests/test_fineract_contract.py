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
import requests
from fastmcp.exceptions import ToolError

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
    """Build a pytest.param list for *scenarios*, marking known bugs as xfail(strict)."""
    params = []
    for scenario in scenarios:
        reason = KNOWN_BUGS.get((test, scenario.name))
        marks = [pytest.mark.xfail(reason=reason, strict=True)] if reason else []
        params.append(pytest.param(scenario, id=scenario.name, marks=marks))
    return params


# --- the contract check -------------------------------------------------------

def _endpoint_pattern(operation: Dict[str, Any]) -> "re.Pattern[str]":
    """Return a regex matching the URL Fineract expects for *operation*."""
    path = re.escape(operation["path"]).replace(re.escape("{id}"), r"\d+")
    command = re.escape(f"?command={operation['command']}") if "command" in operation else ""
    return re.compile(path + command)


def _applies(condition: Dict[str, Any], payload: Dict[str, Any]) -> bool:
    """Return True when every key/value in *condition* is present and equal in *payload*."""
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
        val = payload.get(name)
        if val is None or (isinstance(val, str) and not val.strip()):
            problems.append(f"{name}: required by Fineract, not sent")
    for rule in operation.get("one_of_when", []):
        name = rule["field"]
        if _applies(rule["if"], payload) and name in payload and payload[name] not in rule["in"]:
            problems.append(f"{name}={payload[name]!r}: Fineract only accepts {rule['in']} when {rule['if']}")
    for name in operation.get("tenant_ids", []):
        if name in chosen:
            if name not in payload:
                problems.append(f"{name}: selected tenant id {chosen[name]!r} was omitted from payload")
            elif payload[name] != chosen[name]:
                problems.append(f"{name}={payload[name]!r}: the user chose {chosen[name]!r}")
        elif name in payload:
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
    """Call the MCP tool and return (POSTs made, tenant ids the user chose, tool result).

    When *with_tenant_ids* is False the tenant-specific arguments are omitted so
    the caller can verify the tool never defaults a tenant id.  All other scenario
    arguments are passed as-is; if a scenario argument is not accepted by the tool
    signature the test fails immediately rather than silently dropping it.
    """
    tool = getattr(mcp_server, scenario.tool)
    fn = getattr(tool, "fn", tool)
    accepted = signature(fn).parameters
    # Assert every scenario argument is accepted; drop only tenant args when asked.
    for key in scenario.args:
        assert key in accepted, f"scenario arg {key!r} is not a parameter of {scenario.tool}; fix the scenario"
    args = {
        key: value for key, value in scenario.args.items()
        if with_tenant_ids or key not in scenario.tenant_args
    }
    chosen = {scenario.tenant_args[key]: value for key, value in scenario.args.items() if key in scenario.tenant_args}
    posts = []

    def fake_post(endpoint: str, payload: Dict[str, Any]) -> Dict[str, Any]:
        posts.append((endpoint, dict(payload)))
        return {"resourceId": 1}

    with patch.object(fineract_client, "execute_get", side_effect=_fake_fineract_get), patch.object(
        fineract_client, "execute_post", side_effect=fake_post
    ):
        try:
            result = fn(**args)
        except ToolError as exc:  # safe_tool raises ToolError for {"error": ...}
            result = {"error": str(exc)}
    return posts, chosen, result


# --- tests -------------------------------------------------------------------

@pytest.mark.parametrize("scenario", _params("contract", SCENARIOS))
def test_payload_meets_the_contract(scenario: Scenario) -> None:
    """Verify that the tool's generated payload satisfies Fineract's write contract."""
    posts, chosen, result = _call(scenario)
    assert len(posts) == 1, f"expected one POST, got {posts} (result: {result})"
    endpoint, payload = posts[0]
    problems = contract_violations(scenario.operation, endpoint, payload, chosen)
    assert not problems, "; ".join(problems)


@pytest.mark.parametrize("scenario", _params("defaults", [s for s in SCENARIOS if s.tenant_args]))
def test_tenant_ids_are_never_defaulted(scenario: Scenario) -> None:
    """Verify that tenant-specific IDs are never sent unless explicitly chosen by the user."""
    # Without the id the tool may ask the user (post nothing) or leave the
    # field out, but whatever it posts must still be a valid request.
    posts, _, result = _call(scenario, with_tenant_ids=False)
    if not posts:
        assert isinstance(result, dict) and "error" in result, f"expected tool error requesting missing tenant id, got {result}"
        err_msg = str(result["error"]).lower()
        assert any(
            token in err_msg for token in ["required", "ask the user", "applies"]
        ), f"expected error to explicitly request the missing tenant ID, got: {result['error']}"
    for endpoint, payload in posts:
        problems = contract_violations(scenario.operation, endpoint, payload, chosen={})
        assert not problems, "; ".join(problems)


@pytest.mark.parametrize("scenario", _params("docs", [SCENARIOS[0]]))
def test_documented_codes_match_fineract(scenario: Scenario) -> None:
    """Verify that codes documented in the tool docstrings match Fineract's contract."""
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

    Two probes are made:
    - **Valid probe**: the tool's real payload to a non-existent account id. Fineract
      validates the body first, so a structured 404 confirming the missing resource
      means every field passed validation.
    - **Negative-control probe**: the same endpoint with an extra unsupported field that
      Fineract's validator must reject with 400, proving the validator is active on this route.
    """
    posts, _, _ = _call(scenario)
    assert len(posts) == 1
    endpoint, payload = posts[0]
    probe = re.sub(r"/\d+", f"/{MISSING_ID}", endpoint, count=1)
    url = f"{fineract_client.base_url}/{probe}"

    # Use transport response directly to retain structured error body before adapter parsing
    resp = requests.post(
        url,
        headers=fineract_client._get_headers(),
        auth=(fineract_client.username, fineract_client.password),
        json=payload,
        verify=fineract_client.verify_ssl,
        timeout=fineract_client.timeout,
    )
    assert resp.status_code == 404, f"expected 404 (resource not found), got {resp.status_code}: {resp.text}"
    try:
        err_json = resp.json()
    except (ValueError, requests.exceptions.JSONDecodeError):
        err_json = {}
    err_text = resp.text.lower()
    assert (
        "not.found" in err_text
        or "does not exist" in err_text
        or "resourcenotfound" in err_text
        or err_json.get("userMessageGlobalisationCode") == "error.msg.resource.not.found"
        or any("not.found" in str(e).lower() for e in err_json.get("errors", []))
    ), f"404 response does not match expected Fineract missing-resource error: {resp.text}"

    # Negative-control probe: unsupported field must fail validation with 400
    bad_payload = {**payload, "__invalid_sentinel_field__": "coderabbit_negative_control"}
    neg_resp = requests.post(
        url,
        headers=fineract_client._get_headers(),
        auth=(fineract_client.username, fineract_client.password),
        json=bad_payload,
        verify=fineract_client.verify_ssl,
        timeout=fineract_client.timeout,
    )
    assert neg_resp.status_code == 400, (
        f"Negative control (unsupported field) expected 400 validation error, got {neg_resp.status_code}: {neg_resp.text}"
    )
    neg_text = neg_resp.text.lower()
    assert any(kw in neg_text for kw in ("validation", "unrecognized", "unknown", "invalid", "error")), (
        f"Negative-control 400 body does not look like a validation rejection: {neg_resp.text}"
    )
