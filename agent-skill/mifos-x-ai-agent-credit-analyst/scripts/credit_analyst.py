#!/usr/bin/env python3
# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Credit pre-analysis for the Credit Analyst skill (AI-260).

The model calls one step at a time with a case id. The script fetches every piece of
applicant data through a provider (mock applicants in assets/mock-applicants.json by
default, a real adapter later; see references/external-systems.md), applies the credit
policy in assets/credit-policy.json, and returns every figure, reason, applicant-facing
line and next action as one JSON object. The model never copies applicant data or
computes figures.

Usage:
    python3 credit_analyst.py start --case 1001
    python3 credit_analyst.py id-check --case 1001
    python3 credit_analyst.py income-check --case 1001
    python3 credit_analyst.py request-authorization --case 1001   # only to resend expired links
    python3 credit_analyst.py status --case 1001
    python3 credit_analyst.py analyze --case 1001 [--requested 120000] [--term 36]
    python3 credit_analyst.py handover --case 1001

Case progress is stored per case (CREDIT_ANALYST_STATE_DIR, default
~/.local/state/mifos-credit-analyst) so a later session can resume with `status`.
"""

import argparse
import json
import os
import re
import sys
import uuid
from datetime import date, datetime, timedelta, timezone
from decimal import ROUND_DOWN, ROUND_HALF_UP, Decimal
from pathlib import Path

SKILL_DIR = Path(__file__).resolve().parent.parent
POLICY_FILE = SKILL_DIR / "assets" / "credit-policy.json"
FIXTURES_FILE = SKILL_DIR / "assets" / "mock-applicants.json"
PROFILE_FILE = SKILL_DIR / "assets" / "institution-profile.template.json"
# Mexico has had no daylight saving time since 2022: America/Mexico_City is always UTC-6.
MEXICO_CITY = timezone(timedelta(hours=-6))
CENT = Decimal("0.01")
STEPS = ("start", "id-check", "income-check", "request-authorization", "status", "analyze", "handover")
DISCLAIMER = ("This is a pre-analysis, not a credit approval. Final conditions are set by the institution "
              "after review and formalization.")
CONSENT = "Ask the consent question; after a clear yes, run handover."

MESSAGES = {
    "INVALID_ID": "Your national ID could not be validated, so the pre-analysis cannot continue.",
    "NO_FORMAL_INCOME": "A formal proof of income (bank statements or stamped payroll stubs) is required.",
    "INCOME_PROOF_INCOMPLETE": "We need 3 consecutive months of bank statements or stamped payroll stubs.",
    "INCOME_PROOF_OUTDATED": "Your income documents are too old: we need the 3 most recent complete months.",
    "INCOME_PROOF_NOT_STAMPED": "Payroll stubs must be stamped (digitally certified) to count as formal income.",
    "AUTHORIZATION_REFUSED": "Without your authorization for the credit-bureau and tax checks the pre-analysis "
                             "cannot continue.",
    "AUTHORIZATION_EXPIRED": "Your authorization link expired before it was completed.",
    "TAX_STATUS_CERTIFICATE_MISSING": "A current Tax Status Certificate is required to complete the pre-analysis.",
    "BUREAU_RISK_EXCEEDED": "Your credit-bureau history shows late payments beyond what our policy allows.",
    "INSUFFICIENT_CAPACITY": "Your current monthly payments already use the share of income available for "
                             "credit, so there is no room for a new loan now.",
    "REFER_LEGAL_ENTITY": "Company applications are reviewed directly by a credit officer.",
    "REFER_NO_CREDIT_HISTORY": "You have no credit-bureau history, so a credit officer will review your case.",
    "REFER_TAX_COMPLIANCE": "Your tax compliance opinion needs a review by a credit officer.",
    "REFER_NO_STRUCTURED_DATA": "Your income documents need a manual review by a credit officer.",
    "REFER_AUTHORIZATION_LIMIT": "The authorization links could not be completed, so a credit officer will "
                                 "contact you.",
}
REJECT_REASONS = {"INVALID_ID", "NO_FORMAL_INCOME", "INCOME_PROOF_INCOMPLETE", "INCOME_PROOF_OUTDATED",
                  "INCOME_PROOF_NOT_STAMPED", "AUTHORIZATION_REFUSED", "BUREAU_RISK_EXCEEDED",
                  "INSUFFICIENT_CAPACITY"}
INCOMPLETE_REASONS = {"TAX_STATUS_CERTIFICATE_MISSING", "AUTHORIZATION_EXPIRED"}


class ProviderError(Exception):
    pass


# ── Provider (mock applicants) and state ─────────────────────────────────


def load_json(path: Path) -> dict:
    with open(path, encoding="utf-8") as fh:
        return json.load(fh)


def load_policy() -> dict:
    return load_json(POLICY_FILE)


def load_case(case_id: str, fixtures_file: Path = FIXTURES_FILE) -> dict:
    cases = load_json(fixtures_file)["cases"]
    if case_id not in cases:
        raise ProviderError(f"Case {case_id} not found.")
    return cases[case_id]


def state_dir() -> Path:
    default = Path(os.environ.get("XDG_STATE_HOME", Path.home() / ".local" / "state")) / "mifos-credit-analyst"
    path = Path(os.environ.get("CREDIT_ANALYST_STATE_DIR", default))
    path.mkdir(parents=True, exist_ok=True)
    return path


def load_state(case_id: str) -> dict:
    path = state_dir() / f"{case_id}.json"
    return load_json(path) if path.is_file() else {}


def save_state(case_id: str, state: dict) -> None:
    tmp = state_dir() / f"{case_id}.json.tmp"
    with open(tmp, "w", encoding="utf-8") as fh:
        json.dump(state, fh)
    tmp.replace(state_dir() / f"{case_id}.json")


# ── Helpers ─────────────────────────────────────────────────────────────


def today_mexico_city() -> date:
    return datetime.now(MEXICO_CITY).date()


def cents(value: Decimal) -> Decimal:
    return value.quantize(CENT, rounding=ROUND_HALF_UP)


def money(value: Decimal) -> str:
    return f"{cents(value):,.2f}"


def month_label(today: date, offset: int) -> str:
    year, month = today.year, today.month - offset
    while month <= 0:
        month += 12
        year -= 1
    return f"{year}-{month:02d}"


def institution() -> dict:
    profile = load_json(PROFILE_FILE)
    name = profile.get("financial_institution_name", "")
    regulators = [r for r in (profile.get("regulator_1"), profile.get("regulator_2")) if r and "[" not in r]
    return {"institution_name": name if name and "[" not in name else "our institution", "regulators": regulators}


def auth_links(case_id: str) -> dict:
    return {"bureau_link": f"https://scoring.example/authorize/bureau/{case_id}",
            "tax_link": f"https://scoring.example/authorize/tax/{case_id}"}


def result(case_id: str, step: str, status: str, next_action: str, **extra) -> dict:
    return {"case_id": case_id, "step": step, "status": status, "next_action": next_action, **extra}


def decision_for(reasons: list) -> str:
    if any(r in REJECT_REASONS for r in reasons):
        return "REJECT"
    if any(r in INCOMPLETE_REASONS for r in reasons):
        return "INCOMPLETE"
    return "REFER" if reasons else "PROPOSAL"


def close_case(state: dict, reasons: list) -> dict:
    state["decision"] = decision_for(reasons)
    state["assessment"] = {"decision": state["decision"], "reasons": reasons, "figures": {}, "officer_notes": {}}
    return state


# ── Rules ───────────────────────────────────────────────────────────────


def counted_income(month: dict, excluded: set) -> Decimal:
    return sum((Decimal(str(i["amount"])) for i in month["inflows"] if i["type"].lower() not in excluded),
               Decimal(0))


def check_income(case: dict, policy: dict, today: date) -> dict:
    """Rule 2: formal income proof, 3 consecutive recent complete months, none outdated."""
    rules = policy["income"]
    excluded = set(rules["excluded_inflow_types"])
    months = case.get("income")
    if months is None:
        return {"status": "REFER", "reason": "REFER_NO_STRUCTURED_DATA"}
    by_month = {}
    for m in months:
        if m["source"] not in rules["accepted_sources"] or counted_income(m, excluded) <= 0:
            continue
        current = by_month.get(m["month_offset"])
        if current is None or (current["source"] != "bank_statement" and m["source"] == "bank_statement"):
            by_month[m["month_offset"]] = m  # one entry per month, bank statements preferred
    if not by_month:
        return {"status": "FAIL", "reason": "NO_FORMAL_INCOME"}
    offsets = set(by_month)
    in_grace = today.day <= rules["grace_days"]
    for window in ([1, 2, 3], [2, 3, 4]) if in_grace else ([1, 2, 3],):
        if set(window) <= offsets:
            used = [by_month[o] for o in window]
            if rules["payroll_stub_must_be_stamped"] and any(
                    m["source"] == "payroll_stub" and not m.get("stamped") for m in used):
                return {"status": "FAIL", "reason": "INCOME_PROOF_NOT_STAMPED"}
            return {"status": "PASS", "months": [month_label(today, o) for o in sorted(window, reverse=True)],
                    "used": used}
    newest_allowed = 2 if in_grace else 1
    if min(offsets) > newest_allowed:  # the most recent required month is missing
        return {"status": "FAIL", "reason": "INCOME_PROOF_OUTDATED"}
    return {"status": "FAIL", "reason": "INCOME_PROOF_INCOMPLETE"}


def annuity_factor(term: int, annual_rate_percent: Decimal) -> Decimal:
    r = annual_rate_percent / 100 / 12
    return (1 - (1 + r) ** -term) / r


def payment_for(principal: Decimal, term: int, annual_rate_percent: Decimal) -> Decimal:
    return cents(principal / annuity_factor(term, annual_rate_percent))


def analyze_case(case: dict, policy: dict, income_used: list, requested, term: int) -> dict:
    """Rules 4-11: tax data, bureau, bank and tax capacity, free capacity, maximum financing, decision."""
    share = Decimal(policy["capacity_share"])
    excluded_inflows = set(policy["income"]["excluded_inflow_types"])
    bureau_rules = policy["bureau"]
    excluded_types = set(bureau_rules["excluded_account_types"])
    tax = case.get("tax") or {}
    reasons = []

    if tax.get("compliance_opinion") == "negative":
        reasons.append("REFER_TAX_COMPLIANCE")
    if not tax.get("tax_status_certificate"):
        reasons.append("TAX_STATUS_CERTIFICATE_MISSING")

    raw = case.get("bureau") or []
    accounts = [a for a in raw if a["type"].lower() not in excluded_types]
    payments = Decimal(0)
    for a in accounts:
        if a["status"] != "open":
            continue
        pay = Decimal(str(a.get("monthly_payment") or 0))
        if not pay and a.get("revolving"):
            pay = Decimal(str(a.get("balance", 0))) * Decimal(bureau_rules["revolving_payment_share_of_balance"])
        payments += pay
    payments = cents(payments)
    worst = max((a["worst_risk_level"] for a in accounts), default=None)
    if not accounts:
        reasons.append("REFER_NO_CREDIT_HISTORY")
    elif worst > bureau_rules["max_risk_level"]:
        reasons.append("BUREAU_RISK_EXCEEDED")
    officer = {
        "bureau_summary": {"accounts_considered": len(accounts), "accounts_excluded": len(raw) - len(accounts),
                           "worst_risk_level": worst, "allowed_risk_level": bureau_rules["max_risk_level"],
                           "open_accounts": [{"type": a["type"],
                                              "monthly_payment": money(Decimal(str(a.get("monthly_payment") or 0)))}
                                             for a in accounts if a["status"] == "open"]},
        "tax_summary": {"annual_return_available": bool(tax.get("annual_return_income")),
                        "tax_status_certificate": bool(tax.get("tax_status_certificate")),
                        "compliance_opinion": tax.get("compliance_opinion") or "unknown"},
    }

    monthly = [counted_income(m, excluded_inflows) for m in income_used]
    average_income = cents(sum(monthly) / len(monthly))
    bank_capacity = cents(average_income * share)
    tax_capacity = None
    if tax.get("annual_return_income"):
        tax_capacity = cents(Decimal(str(tax["annual_return_income"])) / 12 * share)
    capacity = max(bank_capacity, tax_capacity or Decimal(0))
    free = capacity - payments
    figures = {
        "average_monthly_income": money(average_income),
        "bank_capacity": money(bank_capacity),
        "tax_capacity": money(tax_capacity) if tax_capacity is not None else "N/A",
        "capacity_used": "tax" if tax_capacity is not None and tax_capacity > bank_capacity else "bank",
        "payment_capacity": money(capacity),
        "current_bureau_payments": money(payments),
        "free_capacity": money(max(free, Decimal(0))),
    }
    if free <= 0:
        reasons.append("INSUFFICIENT_CAPACITY")
    if reasons:
        return {"decision": decision_for(reasons), "reasons": reasons, "figures": figures, "officer_notes": officer}

    rate = Decimal(policy["loan"]["min_annual_rate_percent"])
    max_principal = (free * annuity_factor(term, rate)).quantize(CENT, rounding=ROUND_DOWN)
    req = Decimal(str(requested)) if requested else None
    amount = min(req, max_principal) if req else max_principal
    payment = payment_for(amount, term, rate)
    while payment > free:  # guard only: the payment for a floored principal never exceeds free capacity
        amount -= CENT
        payment = payment_for(amount, term, rate)
    figures.update({
        "maximum_financing": money(max_principal),
        "requested_amount": money(req) if req else "not stated",
        "proposed_amount": money(amount),
        "term_months": term,
        "annual_rate_percent": f"from {rate}%",
        "monthly_payment": money(payment),
        "capped_at_maximum": bool(req) and req > max_principal,
    })
    return {"decision": "PROPOSAL", "reasons": [], "figures": figures, "officer_notes": officer}


def applicant_summary(outcome: dict, currency: str) -> list:
    """Ready-made lines for the applicant; officer-only figures are never included."""
    if outcome["decision"] != "PROPOSAL":
        return [MESSAGES[r] for r in outcome["reasons"]]
    f = outcome["figures"]
    lines = [f"Proposed amount: ${f['proposed_amount']} {currency}",
             f"Term: {f['term_months']} months",
             f"Interest rate: {f['annual_rate_percent']} a year (nominal, monthly payments)",
             f"Estimated monthly payment: ${f['monthly_payment']} {currency}"]
    if f["capped_at_maximum"]:
        lines.insert(1, f"The amount you asked for is above the maximum we can propose now "
                        f"(${f['maximum_financing']} {currency}).")
    return lines


def next_from_state(state: dict) -> tuple:
    """(next_action, case status) for a resumed or intermediate case."""
    if "decision" in state:
        if state.get("handed_over"):
            return "The case was already handed over; only answer questions.", state["decision"]
        return f"Present the result ({state['decision']}). {CONSENT}", state["decision"]
    if state.get("id") != "VALID":
        return "Send the ID verification link; when the applicant confirms, run id-check.", "IN_PROGRESS"
    if state.get("income") != "PASS":
        return "Ask for the income documents; when uploaded, run income-check.", "IN_PROGRESS"
    auth = state.get("authorization", "NOT_REQUESTED")
    if auth == "EXPIRED":
        return "Offer one new link: run request-authorization.", "IN_PROGRESS"
    if auth == "PENDING":
        return "Ask whether the applicant completed both links; then run status.", "IN_PROGRESS"
    return "Run analyze.", "IN_PROGRESS"


# ── Steps ───────────────────────────────────────────────────────────────


def _needs(earlier: str, state: dict) -> bool:
    """Whether an earlier step has not run yet for this case."""
    if earlier == "start":
        return not state
    if "decision" in state:
        return False
    if earlier == "id-check":
        return "id" not in state
    if earlier == "income-check":
        return state.get("id") == "VALID" and "income" not in state
    if earlier == "status":
        return state.get("authorization") == "PENDING"
    return False


PREREQUISITES = {"id-check": ("start",), "income-check": ("start", "id-check"),
                 "status": ("start", "id-check", "income-check"),
                 "analyze": ("start", "id-check", "income-check", "status"),
                 "request-authorization": ("start", "id-check", "income-check")}
PASSING = {"start": "OK", "id-check": "PASS", "income-check": "PASS"}


def run_step(step: str, case_id: str, requested=None, term=None, today: date | None = None,
             fixtures_file: Path = FIXTURES_FILE) -> dict:
    """Run a step, first running any earlier step that has not run yet (the order is enforced here).

    A caught-up step that does not pass is returned instead of the requested step. When the
    income check passes during a catch-up, its output (with the authorization links) is returned
    so the links always reach the applicant before any analysis.
    """
    for earlier in PREREQUISITES.get(step, ()):
        if not _needs(earlier, load_state(case_id)):
            continue
        out = _run_step(earlier, case_id, today=today, fixtures_file=fixtures_file)
        if earlier == "status":
            if out.get("authorization") != "AUTHORIZED":
                return out
            continue
        if out["status"] != PASSING[earlier] or (earlier == "income-check" and step != "income-check"):
            return {**out, "note": f"{earlier} ran first because it had not run yet"}
    return _run_step(step, case_id, requested=requested, term=term, today=today, fixtures_file=fixtures_file)


def _run_step(step: str, case_id: str, requested=None, term=None, today: date | None = None,
              fixtures_file: Path = FIXTURES_FILE) -> dict:
    policy = load_policy()
    today = today or today_mexico_city()
    try:
        case = load_case(case_id, fixtures_file)
    except ProviderError as exc:
        return result(case_id, step, "ERROR", "Say the escalation phrase; do not continue.", message=str(exc))
    state = load_state(case_id)
    currency = policy["currency"]

    if step == "start":
        if state:
            nxt, status = next_from_state(state)
            return result(case_id, step, "ALREADY_STARTED", nxt, case_status=status, **institution())
        state = {"started": today.isoformat(), "auth_checks": 0, "auth_sends": 0,
                 "requested": case.get("requested_amount")}
        if case["entity_type"] == "LEGAL_ENTITY":
            save_state(case_id, close_case(state, ["REFER_LEGAL_ENTITY"]))
            return result(case_id, step, "REFER", f"Explain with message. {CONSENT}",
                          reason="REFER_LEGAL_ENTITY", message=MESSAGES["REFER_LEGAL_ENTITY"], **institution())
        save_state(case_id, state)
        return result(case_id, step, "OK", "Send the ID verification link; when the applicant confirms, run id-check.",
                      id_verification_link=f"https://id-platform.example/verify/{case_id}",
                      message="Please verify your national ID on this secure link. Do not type ID numbers in "
                              "the chat.", **institution())

    if not state:
        return result(case_id, step, "NOT_STARTED", "Run start for this case first.")
    if "decision" in state and step not in ("status", "handover"):
        nxt, status = next_from_state(state)
        return result(case_id, step, "CLOSED", nxt, decision=status)

    if step == "id-check":
        state["id"] = case["id_status"]
        if state["id"] == "VALID":
            save_state(case_id, state)
            return result(case_id, step, "PASS", "Send upload_link and ask the applicant to upload bank statements or "
                          "stamped payroll stubs for months_required; when done, run income-check.",
                          months_required=[month_label(today, o) for o in (3, 2, 1)],
                          upload_link=f"https://documents.example/upload/{case_id}",
                          message="Your national ID is valid.")
        save_state(case_id, close_case(state, ["INVALID_ID"]))
        return result(case_id, step, "FAIL", f"Explain with message; the pre-analysis ends. {CONSENT}",
                      reason="INVALID_ID", message=MESSAGES["INVALID_ID"])

    if step == "income-check":
        if state.get("id") != "VALID":
            return result(case_id, step, "NOT_READY", "Run id-check first.")
        check = check_income(case, policy, today)
        state["income"] = check["status"]
        if check["status"] == "PASS":
            state["income_months"] = check["months"]
            state["income_used"] = check["used"]
            # Rule 3: the authorization links are generated as soon as the income proof passes.
            state.update(auth_sends=1, auth_checks=0, authorization="PENDING")
            save_state(case_id, state)
            return result(case_id, step, "PASS", "Send bureau_link and tax_link; when the applicant says they "
                          "authorized (or refused), run status.", months=check["months"],
                          message="Your income documents are complete. Please authorize the credit-bureau and tax "
                                  "checks on these two secure links. A current Tax Status Certificate is required.",
                          **auth_links(case_id))
        if check["status"] == "REFER":
            save_state(case_id, close_case(state, [check["reason"]]))
            return result(case_id, step, "REFER", f"Explain with message. {CONSENT}",
                          reason=check["reason"], message=MESSAGES[check["reason"]])
        state["income_fails"] = state.get("income_fails", 0) + 1
        if state["income_fails"] > policy["income"]["max_retries"]:
            save_state(case_id, close_case(state, [check["reason"]]))
            return result(case_id, step, "FAIL", f"Explain with message; the pre-analysis ends. {CONSENT}",
                          reason=check["reason"], message=MESSAGES[check["reason"]])
        save_state(case_id, state)
        return result(case_id, step, "FAIL", "Explain with message and months_required; the applicant can upload "
                      "the right documents once more with upload_link, then run income-check again.",
                      reason=check["reason"], message=MESSAGES[check["reason"]],
                      months_required=[month_label(today, o) for o in (3, 2, 1)],
                      upload_link=f"https://documents.example/upload/{case_id}")

    if step == "request-authorization":
        if state.get("income") != "PASS":
            return result(case_id, step, "NOT_READY", "Complete income-check first.")
        auth = state.get("authorization", "NOT_REQUESTED")
        if auth not in ("NOT_REQUESTED", "EXPIRED"):
            return result(case_id, step, "NOT_ALLOWED", next_from_state(state)[0], authorization=auth)
        if state.get("auth_sends", 0) > policy["authorization"]["max_link_resends"]:
            save_state(case_id, close_case(state, ["REFER_AUTHORIZATION_LIMIT"]))
            return result(case_id, step, "REFER", f"Explain with message. {CONSENT}",
                          reason="REFER_AUTHORIZATION_LIMIT", message=MESSAGES["REFER_AUTHORIZATION_LIMIT"])
        state.update(auth_sends=state.get("auth_sends", 0) + 1, auth_checks=0, authorization="PENDING")
        save_state(case_id, state)
        return result(case_id, step, "PENDING", "Send both new links; when the applicant says they authorized, run "
                      "status.", message="Here are new secure links to authorize the credit-bureau and tax checks.",
                      **auth_links(case_id))

    if step == "status":
        extra = {}
        if state.get("authorization") == "PENDING" and "decision" not in state:
            outcome = case["authorization"]
            if state.get("auth_checks", 0) < outcome["pending_checks"]:
                state["auth_checks"] = state.get("auth_checks", 0) + 1
            else:
                state["authorization"] = outcome["result"]
                if outcome["result"] in ("REFUSED", "EXPIRED"):
                    reason = f"AUTHORIZATION_{outcome['result']}"
                    extra = {"reason": reason, "message": MESSAGES[reason]}
                    if outcome["result"] == "REFUSED":
                        close_case(state, [reason])
            save_state(case_id, state)
        nxt, status = next_from_state(state)
        if state.get("authorization") == "EXPIRED" and "decision" not in state:
            nxt = "Explain with message and offer one new link: run request-authorization."
        return result(case_id, step, status, nxt, id_check=state.get("id", "NOT_DONE"),
                      income_check=state.get("income", "NOT_DONE"),
                      authorization=state.get("authorization", "NOT_REQUESTED"), **extra)

    if step == "analyze":
        missing = [name for name, ok in (("id-check", state.get("id") == "VALID"),
                                         ("income-check", state.get("income") == "PASS"),
                                         ("authorization", state.get("authorization") == "AUTHORIZED")) if not ok]
        if missing:
            return result(case_id, step, "NOT_READY", f"Complete first: {', '.join(missing)}.")
        loan = policy["loan"]
        if term is not None and not loan["min_term_months"] <= term <= loan["max_term_months"]:
            return result(case_id, step, "INVALID_TERM", f"Explain that the term must be between "
                          f"{loan['min_term_months']} and {loan['max_term_months']} months; offer the maximum.",
                          max_term_months=loan["max_term_months"])
        if requested:
            state["requested"] = requested
        outcome = analyze_case(case, policy, state["income_used"], state.get("requested"),
                               term or loan["max_term_months"])
        state["decision"] = outcome["decision"]
        state["assessment"] = outcome
        save_state(case_id, state)
        return result(case_id, step, outcome["decision"], f"Show applicant_summary and the disclaimer. {CONSENT}",
                      reasons=outcome["reasons"], applicant_summary=applicant_summary(outcome, currency),
                      disclaimer=DISCLAIMER, officer_figures=outcome["figures"])

    if step == "handover":
        if "decision" not in state:
            return result(case_id, step, "NOT_READY", next_from_state(state)[0])
        if state.get("handed_over"):
            return result(case_id, step, "ALREADY_HANDED_OVER", "Do not write another block; only answer questions.")
        a = state["assessment"]
        record = {
            "schema_version": "1.0",
            "assessment_id": str(uuid.uuid4()),
            "generated_at": datetime.now(MEXICO_CITY).isoformat(timespec="seconds"),
            "source_agent": "mifos-x-ai-agent-credit-analyst",
            "destination": "CREDIT_OFFICER",
            "case_id": case_id,
            "entity_type": case["entity_type"],
            "checks": {"national_id": state.get("id", "NOT_DONE"), "income_proof": state.get("income", "NOT_DONE"),
                       "income_months": state.get("income_months", []),
                       "authorization": state.get("authorization", "NOT_REQUESTED")},
            "decision": a["decision"],
            "reasons": a["reasons"],
            "figures": a.get("figures", {}),
            "officer_notes": a.get("officer_notes", {}),
            "policy": {"capacity_share_percent": int(Decimal(policy["capacity_share"]) * 100),
                       "max_term_months": policy["loan"]["max_term_months"],
                       "min_annual_rate_percent": float(policy["loan"]["min_annual_rate_percent"])},
            "applicant_confirmed_transfer": True,
        }
        state["handed_over"] = True
        save_state(case_id, state)
        return result(case_id, step, "OK", "Write handover_json word for word in a fenced block labelled "
                      "credit_assessment, then tell the applicant a credit officer will contact them.",
                      handover_json=record)

    return result(case_id, step, "ERROR", "Say the escalation phrase.")


# ── CLI ─────────────────────────────────────────────────────────────────


class _JsonArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        print(json.dumps({"status": "INVALID_INPUT", "next_action": "Pass the step, --case digits and optional "
                          "--requested / --term whole numbers.", "message": message}))
        sys.exit(2)


def _positive(text: str) -> str:
    if not re.fullmatch(r"[1-9]\d{0,11}", text):
        raise argparse.ArgumentTypeError("must be a positive whole number (digits only)")
    return text


def _iso_date(text: str) -> date:
    try:
        if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
            return date.fromisoformat(text)
    except ValueError:
        pass
    raise argparse.ArgumentTypeError("dates must be YYYY-MM-DD")


def main(argv: list | None = None) -> int:
    parser = _JsonArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("step", choices=STEPS)
    parser.add_argument("--case", required=True, type=_positive)
    parser.add_argument("--requested", type=_positive, help="requested amount, whole number")
    parser.add_argument("--term", type=_positive, help="term in months")
    parser.add_argument("--today", type=_iso_date, help=argparse.SUPPRESS)
    args = parser.parse_args(argv)
    try:
        out = run_step(args.step, args.case, requested=int(args.requested) if args.requested else None,
                       term=int(args.term) if args.term else None, today=args.today)
    except (OSError, ValueError, KeyError, TypeError, ArithmeticError) as exc:  # never show a traceback
        out = {"case_id": args.case, "step": args.step, "status": "ERROR",
               "next_action": "Say the escalation phrase; do not continue.", "message": type(exc).__name__}
    print(json.dumps(out, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    sys.exit(main())
