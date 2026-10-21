# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Tests for the mifos-x-ai-agent-credit-analyst skill (AI-260).

Checks the Agent Skills format, that the credit policy matches the AI-260 ticket, the
income-proof window, the capacity and financing formulas, every mock applicant end to end
(decision, reasons and figures), the CLI input validation, the handover schema and the
privacy guard.

Run: pytest agent-skill/tests/test_credit_analyst.py -v
"""

import copy
import importlib.util
import json
import re
import subprocess
import sys
from datetime import date
from decimal import Decimal
from pathlib import Path

import pytest

SKILL_DIR = Path(__file__).resolve().parents[1] / "mifos-x-ai-agent-credit-analyst"
SKILL_MD = SKILL_DIR / "SKILL.md"
SCRIPT = SKILL_DIR / "scripts" / "credit_analyst.py"
POLICY = json.loads((SKILL_DIR / "assets" / "credit-policy.json").read_text(encoding="utf-8"))
CASES = json.loads((SKILL_DIR / "assets" / "mock-applicants.json").read_text(encoding="utf-8"))["cases"]
SCHEMA = SKILL_DIR / "assets" / "credit-assessment.schema.json"
EXAMPLES = sorted((SKILL_DIR / "assets").glob("assessment-example-*.json"))
README = SKILL_DIR.parent / "README.md"
TODAY = date(2026, 10, 14)  # after the 10-day grace period

ALLOWED_FIELDS = {"name", "description", "license", "compatibility", "metadata",
                  "allowed-tools", "disable-model-invocation"}

_spec = importlib.util.spec_from_file_location("credit_analyst", SCRIPT)
ca = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(ca)


@pytest.fixture(autouse=True)
def isolated_state(tmp_path, monkeypatch):
    monkeypatch.setenv("CREDIT_ANALYST_STATE_DIR", str(tmp_path / "state"))


def step(name, case, **kwargs):
    kwargs.setdefault("today", TODAY)
    return ca.run_step(name, case, **kwargs)


def run_to_analysis(case, **kwargs):
    """Run every step in order; return the last result (stops at the first non-passing step)."""
    out = step("start", case)
    if out["status"] != "OK":
        return out
    for name in ("id-check", "income-check"):
        out = step(name, case)
        if out["status"] != "PASS":
            return out
    assert out["bureau_link"] and out["tax_link"]  # rule 3: links come with the passing income check
    for _ in range(3):
        out = step("status", case)
        if out.get("authorization") != "PENDING":
            break
    if out.get("authorization") != "AUTHORIZED":
        return out
    return step("analyze", case, **kwargs)


def _frontmatter() -> dict:
    text = SKILL_MD.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    assert match
    data, current = {}, None
    for line in match.group(1).splitlines():
        if line.startswith("  ") and current:
            key, _, value = line.strip().partition(":")
            data[current][key.strip()] = value.strip().strip('"')
        else:
            key, _, value = line.partition(":")
            key, value = key.strip(), value.strip()
            if value:
                data[key] = value
                current = None
            else:
                data[key], current = {}, key
    return data


def _body() -> str:
    return SKILL_MD.read_text(encoding="utf-8").split("---", 2)[2]


# ── Agent Skills format ──────────────────────────────────────────────────


def test_frontmatter_follows_the_spec():
    fm = _frontmatter()
    assert set(fm) <= ALLOWED_FIELDS
    assert fm["name"] == SKILL_DIR.name and re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", fm["name"])
    assert 0 < len(fm["description"]) <= 1024
    assert "Use for" in fm["description"] and "Don't use" in fm["description"]
    assert len(fm.get("compatibility", "")) <= 500


def test_skill_md_is_small_and_has_no_figures():
    assert SKILL_MD.stat().st_size <= 14 * 1024
    assert not re.search(r"\d{1,3},\d{3}", _body()), "no example amounts in SKILL.md (small models copy them)"


def test_every_referenced_file_exists():
    for ref in set(re.findall(r"`((?:references|assets|scripts)/[^`<*]+)`", _body())):
        assert (SKILL_DIR / ref).is_file(), f"missing {ref}"


def test_every_bundled_file_is_referenced_somewhere():
    corpus = _body() + "".join(p.read_text(encoding="utf-8") for p in (SKILL_DIR / "references").glob("*.md"))
    for folder in ("references", "assets", "scripts"):
        for path in (p for p in (SKILL_DIR / folder).glob("*") if p.is_file()):
            assert path.name in corpus, f"{path.relative_to(SKILL_DIR)} is never referenced"


def test_script_path_uses_the_skill_directory():
    assert "python3 <SKILL_DIR>/scripts/credit_analyst.py" in _body()
    assert "/path/to" not in _body()


def test_skill_covers_the_ticket_rules():
    body = _body().lower()
    for words in ("national id", "income proof", "authoriz", "bureau", "30%" if "30%" in body else "capacity",
                  "48 months", "22%", "proposal", "reason for rejection", "credit officer"):
        assert words in body, words


def test_readme_documents_the_skill():
    assert SKILL_DIR.name in README.read_text(encoding="utf-8")


# ── Policy matches the ticket ────────────────────────────────────────────


def test_policy_values_from_the_ticket():
    assert Decimal(POLICY["capacity_share"]) == Decimal("0.30")
    assert POLICY["loan"]["max_term_months"] == 48
    assert Decimal(POLICY["loan"]["min_annual_rate_percent"]) == 22
    assert POLICY["income"]["required_months"] == 3
    assert set(POLICY["income"]["excluded_inflow_types"]) == {"refund", "credit_disbursement", "reversal"}
    assert set(POLICY["bureau"]["excluded_account_types"]) == {"service", "communications", "tax_credit"}


def test_annuity_factor_for_48_months_at_22_percent():
    assert ca.annuity_factor(48, Decimal(22)).quantize(Decimal("0.0001")) == Decimal("31.7399")
    assert ca.payment_for(Decimal("158699.53"), 48, Decimal(22)) <= Decimal(5000)


# ── Income-proof window ─────────────────────────────────────────────────


def _income(offsets, source="bank_statement", stamped=None):
    case = copy.deepcopy(CASES["1001"])
    case["income"] = [{"month_offset": o, "source": source, "inflows": [{"type": "salary", "amount": 10000}],
                       **({"stamped": stamped} if stamped is not None else {})} for o in offsets]
    return case


@pytest.mark.parametrize("offsets,today,expected", [
    ([1, 2, 3], date(2026, 10, 14), "PASS"),
    ([1, 2, 3, 4, 5], date(2026, 10, 14), "PASS"),
    ([2, 3, 4], date(2026, 10, 10), "PASS"),            # within the 10-day grace
    ([2, 3, 4], date(2026, 10, 11), "INCOME_PROOF_OUTDATED"),
    ([4, 5, 6], date(2026, 10, 14), "INCOME_PROOF_OUTDATED"),
    ([1, 2, 4], date(2026, 10, 14), "INCOME_PROOF_INCOMPLETE"),
    ([1, 2], date(2026, 10, 14), "INCOME_PROOF_INCOMPLETE"),
    ([], date(2026, 10, 14), "NO_FORMAL_INCOME"),
])
def test_income_window(offsets, today, expected):
    out = ca.check_income(_income(offsets), POLICY, today)
    assert out.get("reason", out["status"]) == expected


def test_income_months_are_calendar_months():
    out = ca.check_income(_income([1, 2, 3]), POLICY, date(2026, 1, 20))
    assert out["months"] == ["2025-10", "2025-11", "2025-12"]


def test_unstamped_payroll_stubs_fail_and_stamped_pass():
    assert ca.check_income(_income([1, 2, 3], "payroll_stub", False), POLICY, TODAY)["reason"] == "INCOME_PROOF_NOT_STAMPED"
    assert ca.check_income(_income([1, 2, 3], "payroll_stub", True), POLICY, TODAY)["status"] == "PASS"


# ── Mock applicants end to end ──────────────────────────────────────────

EXPECTED = {
    "1001": ("PROPOSAL", [], {"payment_capacity": "9,400.00", "free_capacity": "7,900.00",
                             "maximum_financing": "250,745.27", "proposed_amount": "120,000.00",
                             "monthly_payment": "3,780.73"}),
    "1002": ("REJECT", ["INSUFFICIENT_CAPACITY"], {"payment_capacity": "3,600.00"}),
    "1003": ("REJECT", ["BUREAU_RISK_EXCEEDED"], {}),
    "1007": ("PROPOSAL", [], {"average_monthly_income": "20,000.00", "proposed_amount": "100,000.00"}),
    "1008": ("PROPOSAL", [], {"bank_capacity": "7,800.00", "proposed_amount": "60,000.00"}),
    "1009": ("PROPOSAL", [], {"capacity_used": "tax", "tax_capacity": "12,000.00", "proposed_amount": "250,000.00"}),
    "1010": ("INCOMPLETE", ["TAX_STATUS_CERTIFICATE_MISSING"], {}),
    "1011": ("REFER", ["REFER_TAX_COMPLIANCE"], {}),
    "1012": ("REFER", ["REFER_NO_CREDIT_HISTORY"], {}),
    "1013": ("PROPOSAL", [], {"proposed_amount": "120,000.00"}),
    "1020": ("PROPOSAL", [], {"maximum_financing": "250,745.27", "proposed_amount": "250,745.27",
                             "monthly_payment": "7,900.00", "capped_at_maximum": True}),
}


@pytest.mark.parametrize("case", sorted(EXPECTED))
def test_mock_applicant_results(case):
    decision, reasons, figures = EXPECTED[case]
    out = run_to_analysis(case)
    assert out["status"] == decision and out["reasons"] == reasons
    for key, value in figures.items():
        assert out["officer_figures"][key] == value, key


@pytest.mark.parametrize("case,where,reason", [
    ("1004", "id-check", "INVALID_ID"),
    ("1005", "income-check", "INCOME_PROOF_OUTDATED"),
    ("1006", "income-check", "INCOME_PROOF_INCOMPLETE"),
    ("1016", "income-check", "NO_FORMAL_INCOME"),
    ("1017", "income-check", "INCOME_PROOF_NOT_STAMPED"),
    ("1018", "income-check", "REFER_NO_STRUCTURED_DATA"),
    ("1014", "status", "AUTHORIZATION_REFUSED"),
    ("1019", "status", "AUTHORIZATION_EXPIRED"),
])
def test_mock_applicants_stopped_early(case, where, reason):
    out = run_to_analysis(case)
    assert out["step"] == where and out["reason"] == reason and out["message"]


def test_legal_entity_is_referred_and_stays_referred():
    assert step("start", "1015")["reason"] == "REFER_LEGAL_ENTITY"
    assert step("analyze", "1015")["status"] == "CLOSED"
    assert step("handover", "1015")["handover_json"]["decision"] == "REFER"


def test_proposal_payment_never_exceeds_free_capacity():
    for case in ("1001", "1007", "1008", "1009", "1020"):
        f = run_to_analysis(case)["officer_figures"]
        to_d = lambda s: Decimal(s.replace(",", ""))
        assert to_d(f["monthly_payment"]) <= to_d(f["free_capacity"])
        assert to_d(f["proposed_amount"]) <= to_d(f["maximum_financing"])


def test_shorter_term_on_request():
    out = run_to_analysis("1001", term=24)
    assert out["officer_figures"]["term_months"] == 24
    assert Decimal(out["officer_figures"]["maximum_financing"].replace(",", "")) < Decimal("250745.27")
    assert run_to_analysis("1002", term=60)["status"] in ("INVALID_TERM", "REJECT")


def test_steps_run_in_order_even_when_called_out_of_order():
    first = step("analyze", "1001")  # catches up: start, id-check, income-check
    assert first["step"] == "income-check" and first["bureau_link"] and first["tax_link"]
    assert step("analyze", "1001")["status"] == "PROPOSAL"  # links were sent first


def test_catch_up_stops_at_the_first_failing_check():
    out = step("analyze", "1004")
    assert out["step"] == "id-check" and out["reason"] == "INVALID_ID"
    out = step("income-check", "1005")
    assert out["step"] == "income-check" and out["reason"] == "INCOME_PROOF_OUTDATED"


def test_resume_from_saved_state():
    run_to_analysis("1001")
    out = step("status", "1001")
    assert out["status"] == "PROPOSAL" and "consent" in out["next_action"]


def test_pending_authorization_then_authorized():
    step("start", "1013")
    step("id-check", "1013")
    step("income-check", "1013")
    step("request-authorization", "1013")
    assert step("status", "1013")["authorization"] == "PENDING"
    assert step("status", "1013")["authorization"] == "AUTHORIZED"


def test_unknown_case_escalates():
    assert step("start", "9999")["status"] == "ERROR"


# ── CLI ─────────────────────────────────────────────────────────────────


def _cli(*args, tmp_path):
    env = {"CREDIT_ANALYST_STATE_DIR": str(tmp_path / "cli"), "PATH": "/usr/bin:/bin"}
    proc = subprocess.run([sys.executable, str(SCRIPT), *args, "--today", "2026-10-14"], capture_output=True,
                          text=True, cwd=tmp_path, env=env, check=False)
    return proc.returncode, json.loads(proc.stdout)


def test_cli_works_from_any_directory(tmp_path):
    code, out = _cli("start", "--case", "1001", tmp_path=tmp_path)
    assert code == 0 and out["status"] == "OK" and out["id_verification_link"]


@pytest.mark.parametrize("bad", [["--case", "1001; ls"], ["--case", "$(whoami)"], ["--case", "abc"],
                                 ["--requested", "1e6"], ["--term", "6.5"], ["--term", "-3"]])
def test_cli_rejects_anything_but_digits(tmp_path, bad):
    args = {"--case": "1001"}
    args.update(dict(zip(bad[::2], bad[1::2], strict=True)))
    code, out = _cli("analyze", *[x for kv in args.items() for x in kv], tmp_path=tmp_path)
    assert code == 2 and out["status"] == "INVALID_INPUT"


# ── Handover schema and privacy ─────────────────────────────────────────

jsonschema = pytest.importorskip("jsonschema")


@pytest.fixture(scope="module")
def validator():
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())


@pytest.mark.parametrize("path", EXAMPLES, ids=lambda p: p.stem)
def test_examples_are_valid(validator, path):
    assert not list(validator.iter_errors(json.loads(path.read_text(encoding="utf-8"))))


@pytest.mark.parametrize("case", ["1001", "1002", "1003", "1004", "1009", "1010", "1011", "1012", "1014", "1015",
                                  "1018", "1020"])
def test_every_handover_matches_the_schema(validator, case):
    run_to_analysis(case)
    record = step("handover", case)["handover_json"]
    errors = [e.message for e in validator.iter_errors(record)]
    assert not errors, errors


def test_rejection_requires_a_reason(validator):
    record = json.loads((SKILL_DIR / "assets" / "assessment-example-reject.json").read_text(encoding="utf-8"))
    record["reasons"] = []
    assert list(validator.iter_errors(record))


def test_no_identifiers_in_any_output():
    for case in CASES:
        outputs = [step("start", case)]
        for name in ("id-check", "income-check", "request-authorization", "status", "analyze", "handover"):
            outputs.append(step(name, case))
        text = re.sub(r'"assessment_id": "[0-9a-f-]{36}"', "", json.dumps(outputs))  # random uuid
        assert not re.search(r"\d{10,}|[A-Z]{4}\d{6}", text), case


# ── State machine and edge cases (independent review) ───────────────────


def test_start_on_an_existing_case_does_not_reset_it():
    run_to_analysis("1001")
    out = step("start", "1001")
    assert out["status"] == "ALREADY_STARTED" and out["case_status"] == "PROPOSAL"
    assert step("status", "1001")["id_check"] == "VALID"


def test_authorization_cannot_be_requested_again_after_a_result():
    run_to_analysis("1014")
    assert step("request-authorization", "1014")["status"] == "CLOSED"
    run_to_analysis("1001")
    assert step("request-authorization", "1001")["status"] == "CLOSED"


def test_expired_link_allows_one_resend_then_refers():
    run_to_analysis("1019")
    assert step("request-authorization", "1019")["status"] == "PENDING"
    assert step("status", "1019")["reason"] == "AUTHORIZATION_EXPIRED"
    out = step("request-authorization", "1019")
    assert out["status"] == "REFER" and out["reason"] == "REFER_AUTHORIZATION_LIMIT"
    assert step("handover", "1019")["handover_json"]["decision"] == "REFER"


def test_income_failure_allows_one_retry_then_closes():
    step("start", "1005")
    step("id-check", "1005")
    first = step("income-check", "1005")
    assert first["status"] == "FAIL" and "once more" in first["next_action"]
    second = step("income-check", "1005")
    assert second["status"] == "FAIL" and "ends" in second["next_action"]
    assert step("handover", "1005")["handover_json"]["reasons"] == ["INCOME_PROOF_OUTDATED"]


@pytest.mark.parametrize("case", ["1004", "1014", "1018"])
def test_cases_stopped_early_can_be_handed_over(case):
    run_to_analysis(case)
    assert step("handover", case)["status"] == "OK"


def test_handover_only_once():
    run_to_analysis("1001")
    assert step("handover", "1001")["status"] == "OK"
    assert step("handover", "1001")["status"] == "ALREADY_HANDED_OVER"


def test_analysis_after_the_month_changes_uses_the_checked_months(tmp_path):
    fixtures = tmp_path / "cases.json"
    case = copy.deepcopy(CASES["1001"])
    for m, o in zip(case["income"], (2, 3, 4), strict=True):
        m["month_offset"] = o
    fixtures.write_text(json.dumps({"cases": {"1001": case}}), encoding="utf-8")
    run = lambda name, day: ca.run_step(name, "1001", today=date(2026, 10, day), fixtures_file=fixtures)
    for name in ("start", "id-check", "income-check", "request-authorization", "status"):
        assert run(name, 10)["status"] not in ("FAIL", "ERROR")
    assert run("analyze", 11)["status"] == "PROPOSAL"


def test_one_entry_per_month_and_zero_income_months_ignored():
    case = _income([1, 2, 3])
    case["income"].append({"month_offset": 1, "source": "payroll_stub", "stamped": False,
                           "inflows": [{"type": "salary", "amount": 99999}]})
    case["income"].append({"month_offset": 5, "source": "payroll_stub", "stamped": False,
                           "inflows": [{"type": "salary", "amount": 5000}]})
    out = ca.check_income(case, POLICY, TODAY)
    assert out["status"] == "PASS" and len(out["used"]) == 3
    assert all(m["source"] == "bank_statement" for m in out["used"])
    zero = _income([1, 2, 3])
    zero["income"][0]["inflows"] = [{"type": "refund", "amount": 5000}]
    assert ca.check_income(zero, POLICY, TODAY)["reason"] == "INCOME_PROOF_OUTDATED"  # newest month has no income


def test_bureau_with_only_excluded_or_mixed_case_types():
    case = copy.deepcopy(CASES["1001"])
    used = ca.check_income(case, POLICY, TODAY)["used"]
    case["bureau"] = [{"type": "service", "status": "open", "worst_risk_level": 7, "monthly_payment": 300}]
    assert ca.analyze_case(case, POLICY, used, None, 48)["reasons"] == ["REFER_NO_CREDIT_HISTORY"]
    case["bureau"] = [{"type": "Communications", "status": "open", "worst_risk_level": 7, "monthly_payment": 300},
                      {"type": "credit_card", "status": "open", "worst_risk_level": 1, "monthly_payment": 500}]
    assert ca.analyze_case(case, POLICY, used, None, 48)["decision"] == "PROPOSAL"


def test_all_reasons_are_collected():
    case = copy.deepcopy(CASES["1001"])
    used = ca.check_income(case, POLICY, TODAY)["used"]
    case["tax"]["compliance_opinion"] = "negative"
    case["bureau"][0]["worst_risk_level"] = 9
    out = ca.analyze_case(case, POLICY, used, None, 48)
    assert out["decision"] == "REJECT" and set(out["reasons"]) == {"REFER_TAX_COMPLIANCE", "BUREAU_RISK_EXCEEDED"}


def test_requested_amount_is_kept_for_the_case():
    out = run_to_analysis("1001", requested=80000)
    assert out["officer_figures"]["proposed_amount"] == "80,000.00"


def test_applicant_summary_hides_officer_figures():
    out = run_to_analysis("1001")
    text = " ".join(out["applicant_summary"])
    assert "120,000.00" in text and "3,780.73" in text and "MXN" in text and "22%" in text
    for officer_only in ("9,400.00", "7,900.00", "1,500.00", "31,333.33"):
        assert officer_only not in text


def test_status_catches_up_and_gives_the_next_step():
    step("start", "1001")
    out = step("status", "1001")  # runs id-check and income-check first, returns the links
    assert out["step"] == "income-check" and out["bureau_link"] and "status" in out["next_action"]
    assert "analyze" in step("status", "1001")["next_action"]


def test_cli_rejects_zero_amounts(tmp_path):
    code, out = _cli("analyze", "--case", "1001", "--requested", "0", tmp_path=tmp_path)
    assert code == 2 and out["status"] == "INVALID_INPUT"


def test_skill_md_gives_one_answer_about_the_rate():
    body = _body()
    rate_rows = [line for line in body.splitlines() if "interest rate" in line.lower() and line.startswith("|")]
    assert rate_rows and all("22%" in line for line in rate_rows)


def test_authorization_links_cannot_be_resent_while_pending():
    step("start", "1013")
    step("id-check", "1013")
    assert step("income-check", "1013")["bureau_link"]
    assert step("request-authorization", "1013")["status"] == "NOT_ALLOWED"


def test_repeated_income_check_keeps_authorization_and_resend_limit():
    run_to_analysis("1001")
    step("start", "1013")
    step("id-check", "1013")
    step("income-check", "1013")
    assert step("status", "1013")["authorization"] == "PENDING"
    assert step("income-check", "1013")["status"] == "PASS"  # repeat: no reset
    assert step("status", "1013")["authorization"] == "AUTHORIZED"
    run_to_analysis("1019")  # expired once
    step("request-authorization", "1019")
    step("status", "1019")  # expired again
    step("income-check", "1019")  # must not reset the resend counter
    assert step("request-authorization", "1019")["reason"] == "REFER_AUTHORIZATION_LIMIT"


def test_schema_rejects_inconsistent_decisions(validator):
    proposal = json.loads((SKILL_DIR / "assets" / "assessment-example-proposal.json").read_text(encoding="utf-8"))
    bad = copy.deepcopy(proposal)
    bad["reasons"] = ["INSUFFICIENT_CAPACITY"]
    assert list(validator.iter_errors(bad))
    bad = copy.deepcopy(proposal)
    bad["checks"]["authorization"] = "REFUSED"
    assert list(validator.iter_errors(bad))
    reject = json.loads((SKILL_DIR / "assets" / "assessment-example-reject.json").read_text(encoding="utf-8"))
    bad = copy.deepcopy(reject)
    bad["figures"]["proposed_amount"] = "50,000.00"
    assert list(validator.iter_errors(bad))
    bad = copy.deepcopy(reject)
    del bad["figures"]["free_capacity"]  # analysis ran: capacity figures are required
    assert list(validator.iter_errors(bad))
