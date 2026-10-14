# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Tests for the mifos-x-ai-agent-investment-sales-advisor skill (AI-262).

Checks the Agent Skills format, that the simulator data matches the AI-262 ticket cell by
cell, that the simulator reproduces the ticket's worked examples and applies the filters,
and that the handover JSON Schema accepts the bundled examples and rejects privacy leaks.

Run: pytest agent-skill/tests/test_investment_sales_advisor.py -v
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

SKILL_DIR = Path(__file__).resolve().parents[1] / "mifos-x-ai-agent-investment-sales-advisor"
SKILL_MD = SKILL_DIR / "SKILL.md"
SCRIPT = SKILL_DIR / "scripts" / "simulate_investment.py"
DATA = json.loads((SKILL_DIR / "assets" / "simulator-v12.json").read_text(encoding="utf-8"))
SCHEMA = SKILL_DIR / "assets" / "handover.schema.json"
EXAMPLES = sorted((SKILL_DIR / "assets").glob("handover-example-*.json"))
README = SKILL_DIR.parent / "README.md"

ALLOWED_FIELDS = {"name", "description", "license", "compatibility", "metadata",
                  "allowed-tools", "disable-model-invocation"}

_spec = importlib.util.spec_from_file_location("simulate_investment", SCRIPT)
sim = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(sim)

TODAY = date(2026, 1, 2)  # early in the year, so 7-359 day terms stay in 2026


def run(product, amount, **kwargs):
    kwargs.setdefault("today", TODAY)
    return sim.simulate(product, amount, **kwargs)


def _frontmatter() -> dict:
    text = SKILL_MD.read_text(encoding="utf-8")
    match = re.match(r"^---\n(.*?)\n---\n", text, re.DOTALL)
    assert match, "SKILL.md must start with --- frontmatter ---"
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


def test_frontmatter_fields_are_from_the_spec():
    assert set(_frontmatter()) <= ALLOWED_FIELDS


def test_name_is_valid_and_matches_folder():
    name = _frontmatter()["name"]
    assert re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name) and len(name) <= 64
    assert name == SKILL_DIR.name


def test_description_routes_well():
    description = _frontmatter()["description"]
    assert 0 < len(description) <= 1024
    assert "Use for" in description and "Don't use" in description
    assert not re.search(r"\b(I|me|my|you|your)\b", description), "description must be third person"


def test_optional_fields_respect_limits():
    fm = _frontmatter()
    assert len(fm.get("compatibility", "")) <= 500
    assert all(isinstance(v, str) for v in fm.get("metadata", {}).values())


def test_skill_md_fits_the_token_budget():
    # Small-model providers (Groq free tier: 8k tokens per minute) need a small skill: at 15 KB a
    # model call measured about 3.6k input tokens on average and 6.7k at most.
    assert SKILL_MD.stat().st_size <= 16 * 1024
    assert len(SKILL_MD.read_text(encoding="utf-8").splitlines()) < 500


def test_skill_md_has_no_rate_tables():
    # Rates live only in assets/simulator-v12.json so the model cannot quote them from memory.
    body = _body()
    for row in DATA["products"]["promissory"]["rates_percent"] + DATA["products"]["cede"]["rates_percent"]:
        for cell in row:
            if cell != "14.00":  # the campaign rate is named in the workflow
                assert f"{cell}%" not in body, f"rate {cell}% must not appear in SKILL.md"


def test_every_referenced_file_exists_and_is_relative():
    refs = set(re.findall(r"`((?:references|assets|scripts)/[^`*]+)`", _body()))
    assert refs
    for ref in refs:
        assert (SKILL_DIR / ref).is_file(), f"missing {ref}"


def test_every_bundled_file_is_referenced_somewhere():
    corpus = _body() + "".join(p.read_text(encoding="utf-8") for p in (SKILL_DIR / "references").glob("*.md"))
    for folder in ("references", "assets", "scripts"):
        for path in (p for p in (SKILL_DIR / folder).glob("*") if p.is_file()):
            assert path.name in corpus, f"{path.relative_to(SKILL_DIR)} is never referenced"


@pytest.mark.parametrize("phrase", [
    "I do not have that updated data. Please consult a branch or Condusef.",
    "I do not have that updated data. Please consult a branch or Commercial Executive.",
    ("This is the reference annual gross rate for that amount and term as of the simulation date. "
     "Final conditions are confirmed upon formalizing the investment."),
    ("According to our internal policies, this case requires additional review from the responsible "
     "department. May we gather the required information so they can evaluate it formally?"),
    ("This case needs additional review from the responsible area. I will assist you in gathering "
     "information so they can evaluate it formally."),
])
def test_ticket_phrases_are_verbatim(phrase):
    assert phrase in _body()


def test_all_nine_strict_rules_and_eight_filters():
    body = _body()
    assert sorted(re.findall(r"^([1-9])\. ", body.split("## Fixed Phrases")[0], re.MULTILINE)) == list("123456789")
    assert sorted(re.findall(r"^\| ([1-8]) \|", body, re.MULTILINE)) == list("12345678")


@pytest.mark.parametrize("document", [
    "INE or passport", "CURP", "Tax Status Certificate (RFC)", "no older than 3 months",
    "Articles of incorporation", "Public Registry", "power of attorney of the legal representative",
    "shareholders or controlling beneficiaries", "payout at maturity (to be confirmed)",
])
def test_document_lists_from_ticket(document):
    assert document.lower() in _body().lower()


def test_script_path_is_resolved_from_the_skill_directory():
    body = _body()
    assert "Never resolve them against the current working directory" in body
    assert "python3 <SKILL_DIR>/scripts/simulate_investment.py" in body
    assert "/path/to" not in body, "small models copy example paths literally"


def test_handover_is_marked_for_the_harness_not_the_customer():
    body = _body()
    assert "fenced block labelled `handover`" in body
    assert "must not show it in the customer chat" in body


def test_readme_documents_the_skill():
    assert SKILL_DIR.name in README.read_text(encoding="utf-8")


# ── Simulator data matches the ticket ────────────────────────────────────

TICKET_PROMISSORY = [
    [8.00, 8.25, 8.75, 9.25, 10.00, 11.25],
    [8.50, 8.75, 9.25, 10.00, 10.75, 11.75],
    [9.00, 9.25, 10.25, 11.25, 12.00, 12.75],
    [9.45, 9.70, 10.70, 11.70, 12.45, 13.20],
    [10.00, 10.25, 11.25, 12.25, 13.25, 14.00],
]
TICKET_CEDE = [
    [8.50, 9.00, 9.75, 11.00],
    [9.00, 9.75, 10.50, 11.50],
    [10.00, 11.00, 11.75, 12.50],
    [10.45, 11.45, 12.20, 12.95],
    [11.00, 12.00, 13.00, 13.75],
]
TICKET_TAX = {2022: (0.08, 175505.40), 2023: (0.15, 189222.00), 2024: (0.50, 198031.80),
              2025: (0.50, 206367.60), 2026: (0.90, 213973.20)}


@pytest.mark.parametrize("product,table", [("promissory", TICKET_PROMISSORY), ("cede", TICKET_CEDE)])
def test_every_rate_cell_matches_the_ticket(product, table):
    assert [[Decimal(c) for c in row] for row in DATA["products"][product]["rates_percent"]] == \
        [[Decimal(str(c)).quantize(Decimal("0.01")) for c in row] for row in table]


def test_brackets_ranges_and_reinvestment_limits_match_the_ticket():
    assert [b["min"] for b in DATA["amount_brackets"]] == [1000, 100000, 250000, 500000, 1000000]
    promissory = DATA["products"]["promissory"]["term_ranges"]
    assert [r["min"] for r in promissory] == [7, 30, 60, 90, 180, 360]
    assert [r["max_reinvestments"] for r in promissory] == [51, 12, 6, 4, 2, 0]
    cede = DATA["products"]["cede"]["term_ranges"]
    assert [r["min"] for r in cede] == [60, 90, 180, 360]
    assert all(r["max_reinvestments"] is None for r in cede)
    assert DATA["minimum_amount"] == 1000 and DATA["day_count_basis"] == 360


def test_tax_table_and_uma_match_the_ticket():
    for year, (rate, exempt) in TICKET_TAX.items():
        row = DATA["tax_years"][str(year)]
        assert Decimal(row["withholding_rate_percent"]) == Decimal(str(rate)).quantize(Decimal("0.01"))
        assert Decimal(row["exempt_amount"]) == Decimal(str(exempt)).quantize(Decimal("0.01"))
    annual = Decimal(DATA["uma_2026"]["annual"])
    assert annual == Decimal("42794.64")
    assert 5 * annual == Decimal(DATA["tax_years"]["2026"]["exempt_amount"])


def test_campaign_matches_the_ticket():
    campaign = DATA["campaign"]
    assert campaign["rate_percent"] == "14.00" and campaign["min_days"] == 360
    assert set(campaign["products"]) == {"promissory", "cede"}


# ── Simulator: worked examples and formulas ──────────────────────────────


@pytest.mark.parametrize("product,amount,days,expected", [
    ("promissory", 100000, 90, ("10.00", "2,500.00", "0.00", "2,500.00", "10.00", "102,500.00")),
    ("promissory", 500000, 180, ("12.45", "31,125.00", "1,287.12", "29,837.88", "11.94", "529,837.88")),
    ("cede", 1000000, 360, ("13.75", "137,500.00", "7,074.24", "130,425.76", "13.04", "1,130,425.76")),
])
def test_ticket_worked_examples_to_the_cent(product, amount, days, expected):
    out = run(product, amount, days=days)
    assert out["status"] == "OK"
    keys = ("gross_rate_percent", "gross_interest", "estimated_isr", "net_interest",
            "net_rate_percent", "total_at_maturity")
    assert tuple(out[k] for k in keys) == expected


@pytest.mark.parametrize("amount,row", [
    (1000, 0), (99999, 0), (Decimal("99999.50"), 0), (100000, 1), (Decimal("249999.99"), 1),
    (250000, 2), (Decimal("999999.99"), 3), (1000000, 4), (50000000, 4),
])
def test_amount_bracket_boundaries(amount, row):
    out = run("promissory", amount, days=30, rate_only=True)
    assert out["gross_rate_percent"] == f"{TICKET_PROMISSORY[row][1]:.2f}"


@pytest.mark.parametrize("days,column", [(7, 0), (29, 0), (30, 1), (59, 1), (60, 2), (89, 2),
                                         (90, 3), (179, 3), (180, 4), (359, 4), (360, 5), (1000, 5)])
def test_promissory_term_boundaries(days, column):
    out = run("promissory", 50000, days=days, rate_only=True)
    assert out["gross_rate_percent"] == f"{TICKET_PROMISSORY[0][column]:.2f}"


@pytest.mark.parametrize("days,column", [(60, 0), (89, 0), (90, 1), (179, 1), (180, 2), (359, 2), (360, 3)])
def test_cede_term_boundaries(days, column):
    out = run("cede", 50000, days=days, rate_only=True)
    assert out["gross_rate_percent"] == f"{TICKET_CEDE[0][column]:.2f}"


def test_isr_is_zero_up_to_the_exempt_amount():
    assert run("promissory", Decimal("213973.20"), days=90)["estimated_isr"] == "0.00"
    assert run("promissory", Decimal("213973.21"), days=90)["estimated_isr"] == "0.00"  # 0.01 above rounds to 0
    assert run("promissory", 300000, days=90)["estimated_isr"] != "0.00"


def test_cede_periods_are_30_days_with_a_proportional_last_period():
    out = run("cede", 250000, days=100, periods=True)
    assert [p["days"] for p in out["periods"]] == [30, 30, 30, 10]
    assert out["interest_paid"].startswith("every 30 days")
    assert "periods" not in run("cede", 250000, days=100)


def test_cede_total_is_not_the_sum_of_rounded_periods():
    out = run("cede", 1000000, days=360, periods=True)
    assert out["gross_interest"] == "137,500.00"
    assert all(p["gross_interest"] == "11,458.33" for p in out["periods"])


def test_maturity_date_input_equals_days():
    by_days = run("promissory", 500000, days=180)
    by_date = run("promissory", 500000, maturity_date=date(2026, 7, 1))
    assert by_date["days"] == 180 and by_date["net_interest"] == by_days["net_interest"]


def test_output_carries_simulation_date_and_table_version():
    out = run("promissory", 100000, days=90)
    assert out["simulation_date"] == "2026-01-02" and out["opening_date"] == "2026-01-02"
    assert out["maturity_date"] == "2026-04-02"
    assert out["rate_table"] == "Investment Simulator V12 (2026-04-28)"


# ── Simulator: filters, flags and escalation ─────────────────────────────


@pytest.mark.parametrize("product,amount,kwargs,status", [
    ("promissory", 999, {"days": 30}, "INVALID_AMOUNT"),
    ("promissory", Decimal("999.99"), {"days": 30}, "INVALID_AMOUNT"),
    ("promissory", 5000, {"days": 6}, "INVALID_TERM"),
    ("cede", 5000, {"days": 5}, "INVALID_TERM"),
    ("cede", 5000, {"days": 45}, "CEDE_MIN_TERM"),
    ("cede", 5000, {"days": 59}, "CEDE_MIN_TERM"),
    ("promissory", 5000, {"days": 90, "reinvestments": 5}, "REINVEST_LIMIT"),
    ("promissory", 5000, {"days": 360, "reinvestments": 1}, "REINVEST_LIMIT"),
    ("cede", 5000, {"days": 90, "reinvestments": 1}, "REINVEST_NOT_APPLICABLE"),
    ("promissory", 5000, {}, "MISSING_TERM"),
    ("promissory", 5000, {"days": 30, "opening_date": date(2026, 1, 1)}, "INVALID_DATE"),
    ("promissory", 5000, {"days": 30, "maturity_date": date(2026, 3, 1)}, "INVALID_DATE"),
    ("promissory", 5000, {"days": 30, "currency": "USD"}, "UNSUPPORTED_CURRENCY"),
    ("promissory", 5000, {"days": 30, "opening_date": date(2027, 1, 5)}, "ESCALATE_NO_TAX_DATA"),
])
def test_filters_and_invalid_input_return_no_figures(product, amount, kwargs, status):
    out = run(product, amount, **kwargs)
    assert out["status"] == status
    assert "gross_interest" not in out and out["message"]


def test_reinvestments_within_the_limit_are_fine():
    assert run("promissory", 5000, days=90, reinvestments=4)["status"] == "OK"
    assert run("promissory", 5000, days=7, reinvestments=51)["status"] == "OK"


def test_fiscal_year_crossing_is_flagged_not_blocked():
    out = run("promissory", 500000, days=180, today=date(2026, 10, 12))
    assert out["status"] == "OK" and out["fiscal_year_crossing"] is True
    assert out["tax_year"] == 2026 and any("2027" in n for n in out["notes"])
    assert run("promissory", 500000, days=180)["fiscal_year_crossing"] is False


@pytest.mark.parametrize("answer,flag", [("no", False), ("yes", True), ("unknown", True)])
def test_other_investments_flag_cumulative_isr(answer, flag):
    out = run("promissory", 500000, days=90, other_investments=answer)
    assert out["status"] == "OK" and out["cumulative_isr_unverified"] is flag


def test_campaign_is_offered_from_360_days_only():
    out = run("cede", 1000000, days=360)
    assert out["gross_rate_percent"] == "13.75"  # standard figures stay as in the ticket example
    assert out["campaign"]["gross_rate_percent"] == "14.00"
    assert out["campaign"]["net_interest"] == "132,925.76"
    assert "campaign" not in run("cede", 1000000, days=359)


def test_campaign_never_lowers_or_repeats_the_table_rate():
    assert "campaign" not in run("promissory", 1000000, days=360)  # table is already 14.00%


def test_campaign_switch_lives_in_the_asset():
    data = copy.deepcopy(DATA)
    data["campaign"]["active"] = False
    assert "campaign" not in sim.simulate("cede", 1000000, days=360, today=TODAY, data=data)


def test_inconsistent_results_escalate():
    data = copy.deepcopy(DATA)
    data["tax_years"]["2026"]["withholding_rate_percent"] = "90"
    assert sim.simulate("promissory", 5000000, days=30, today=TODAY, data=data)["status"] == "ESCALATE_INCONSISTENT"


# ── Simulator CLI ────────────────────────────────────────────────────────


def _cli(*args, cwd):
    proc = subprocess.run([sys.executable, str(SCRIPT), *args, "--today", "2026-01-02"],
                          capture_output=True, text=True, cwd=cwd, check=False)
    return proc.returncode, json.loads(proc.stdout)


def test_cli_works_from_any_directory(tmp_path):
    code, out = _cli("--product", "promissory", "--amount", "500,000", "--days", "180", cwd=tmp_path)
    assert code == 0 and out["net_interest"] == "29,837.88"


@pytest.mark.parametrize("bad", [
    ["--amount", "100000; ls"], ["--amount", "$(whoami)"], ["--amount", "-5000"],
    ["--amount", "1e6"], ["--days", "90 days"], ["--opening-date", "tomorrow"],
])
def test_cli_rejects_anything_but_digits_and_dates(tmp_path, bad):
    args = {"--product": "promissory", "--amount": "5000", "--days": "90"}
    args.update(dict(zip(bad[::2], bad[1::2], strict=True)))
    code, out = _cli(*[x for kv in args.items() for x in kv], cwd=tmp_path)
    assert code == 2 and out["status"] == "INVALID_INPUT"


def test_cli_days_and_maturity_date_are_exclusive(tmp_path):
    code, out = _cli("--product", "cede", "--amount", "5000", "--days", "90",
                     "--maturity-date", "2026-04-02", cwd=tmp_path)
    assert code == 2 and out["status"] == "INVALID_INPUT"


# ── Handover schema ──────────────────────────────────────────────────────

jsonschema = pytest.importorskip("jsonschema")


@pytest.fixture(scope="module")
def validator():
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())


def _example(name: str) -> dict:
    return json.loads((SKILL_DIR / "assets" / f"handover-example-{name}.json").read_text(encoding="utf-8"))


def test_one_example_per_reason():
    assert {p.stem.removeprefix("handover-example-") for p in EXAMPLES} == {"ready", "onboarding", "escalation"}


@pytest.mark.parametrize("path", EXAMPLES, ids=lambda p: p.stem)
def test_examples_are_valid(validator, path):
    errors = list(validator.iter_errors(json.loads(path.read_text(encoding="utf-8"))))
    assert not errors, [e.message for e in errors]


@pytest.mark.parametrize("name", ["ready", "onboarding"])
def test_example_figures_come_from_the_simulator(name):
    for s in _example(name)["simulations"]:
        product = "promissory" if s["product"] == "PROMISSORY_NOTE" else "cede"
        out = run(product, s["amount"], days=s["days"], today=date.fromisoformat(s["simulation_date"]))
        shown = out["campaign"] if s["campaign_rate_shown"] else out
        for key in ("gross_rate_percent", "gross_interest", "estimated_isr", "net_interest",
                    "net_rate_percent", "total_at_maturity"):
            assert Decimal(shown[key].replace(",", "")) == Decimal(str(s[key])), (name, key)
        assert out["fiscal_year_crossing"] == s["fiscal_year_crossing"]


def test_figures_must_be_numbers(validator):
    handover = _example("ready")
    handover["simulations"][0]["net_interest"] = "29,837.88"
    assert list(validator.iter_errors(handover))


def test_unknown_fields_are_rejected(validator):
    handover = _example("ready")
    handover["customer"]["curp"] = "GOKA900101HDFRRN09"
    assert list(validator.iter_errors(handover))


def test_documents_use_the_ticket_list(validator):
    handover = _example("ready")
    handover["documents"].append({"type": "BANK_STATEMENTS", "status": "PENDING"})
    assert list(validator.iter_errors(handover))


@pytest.mark.parametrize("leak", [
    "Customer shared account number 1234567890.",
    "Customer shared INE IDMEX2045678912.",
    "Customer RFC is GOKA900101AB1.",
    "Customer shared CLABE 0121 8000 1234 5678 90.",
])
def test_privacy_guard_rejects_identifiers_in_free_text(validator, leak):
    handover = _example("ready")
    handover["conversation_summary"] = leak
    assert list(validator.iter_errors(handover)), f"leak not caught: {leak}"


def test_privacy_guard_allows_amounts_and_dates(validator):
    handover = _example("ready")
    handover["conversation_summary"] = "Wants 1,000,000 pesos for 360 days from 2026-10-12."
    assert not list(validator.iter_errors(handover))


def test_handover_needs_customer_consent_field(validator):
    handover = _example("ready")
    del handover["customer_confirmed_transfer"]
    assert list(validator.iter_errors(handover))


def test_ok_simulation_carries_the_full_disclaimer():
    disclaimer = run("cede", 1000000, days=360)["disclaimer"]
    assert disclaimer.startswith("Simulation date 2026-01-02, Investment Simulator V12 (2026-04-28).")
    assert sim.RATE_PHRASE in disclaimer
    assert "validate its tax treatment with your accountant" in disclaimer
    assert sim.RATE_PHRASE in _body(), "SKILL.md and the script must use the same rate phrase"


def test_skill_md_has_no_worked_example_figures():
    # Small models copy example numbers into replies instead of running the simulator.
    body = _body()
    for figure in ("12.45", "11.94", "31125", "31,125", "1287.12", "29837.88", "529837.88", "13.75", "7074.24"):
        assert figure not in body, f"example figure {figure} must not appear in SKILL.md"


def test_cede_reports_net_interest_for_each_30_day_period():
    out = run("cede", 800000, days=360, periods=True)
    month = out["each_30_day_period"]
    assert (month["gross_interest"], month["estimated_isr"], month["net_interest"]) == ("8,633.33", "439.52", "8,193.81")
    assert [p["net_interest"] for p in out["periods"]] == ["8,193.81"] * 12
    assert "each_30_day_period" not in run("promissory", 800000, days=360)


def test_ok_simulation_provides_a_handover_id():
    ids = {run("promissory", 50000, days=90)["handover_id"] for _ in range(3)}
    assert len(ids) == 3 and all(re.fullmatch(r"[0-9a-f-]{36}", i) for i in ids)
