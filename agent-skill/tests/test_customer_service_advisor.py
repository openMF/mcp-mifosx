# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Tests for the mifos-x-ai-agent-customer-service-advisor skill (AI-261).

Checks the Agent Skills format (https://agentskills.io/specification, as used by Pi),
that every file referenced by SKILL.md exists, and that the handoff JSON Schema accepts
the bundled examples and rejects malformed or privacy-leaking handoffs.

Run: pytest agent-skill/tests/test_customer_service_advisor.py -v
"""

import copy
import json
import re
from pathlib import Path

import pytest

SKILL_DIR = Path(__file__).resolve().parents[1] / "mifos-x-ai-agent-customer-service-advisor"
SKILL_MD = SKILL_DIR / "SKILL.md"
SCHEMA = SKILL_DIR / "assets" / "handoff.schema.json"
EXAMPLES = sorted((SKILL_DIR / "assets").glob("handoff-example-*.json"))

ALLOWED_FIELDS = {"name", "description", "license", "compatibility", "metadata",
                  "allowed-tools", "disable-model-invocation"}


def _frontmatter() -> dict:
    """Parse the simple YAML frontmatter used by SKILL.md (scalars plus one nested map)."""
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
    assert re.fullmatch(r"[a-z0-9]+(-[a-z0-9]+)*", name)
    assert len(name) <= 64
    assert name == SKILL_DIR.name


def test_description_routes_well():
    description = _frontmatter()["description"]
    assert 0 < len(description) <= 1024
    assert "Use for" in description or "Use when" in description
    assert "Don't use" in description
    assert not re.search(r"\b(I|me|my|you|your)\b", description), "description must be third person"


def test_optional_fields_respect_limits():
    fm = _frontmatter()
    assert len(fm.get("compatibility", "")) <= 500
    assert all(isinstance(v, str) for v in fm.get("metadata", {}).values())


def test_skill_md_is_short_enough():
    assert len(SKILL_MD.read_text(encoding="utf-8").splitlines()) < 500


def test_every_referenced_file_exists_and_is_relative():
    refs = set(re.findall(r"`((?:references|assets|scripts)/[^`]+)`", _body()))
    assert refs, "SKILL.md should point to its reference files"
    for ref in refs:
        assert not ref.startswith("/")
        assert (SKILL_DIR / ref).is_file(), f"missing {ref}"


def test_every_bundled_file_is_referenced_somewhere():
    corpus = _body() + "".join(p.read_text(encoding="utf-8") for p in (SKILL_DIR / "references").glob("*.md"))
    for path in [*(SKILL_DIR / "references").glob("*"), *(SKILL_DIR / "assets").glob("*")]:
        assert path.name in corpus, f"{path.relative_to(SKILL_DIR)} is never referenced"


@pytest.mark.parametrize("rule", [
    "Never give investment or credit advice",
    "Route every question",
    "promises of approval",
    "Never accept funds or money-movement instructions",
    "active process",
])
def test_strict_rules_from_ticket_are_present(rule):
    assert rule in _body()


def test_all_eight_eligibility_filters_are_in_skill_md():
    rows = re.findall(r"^\| ([1-8]) \|", _body(), re.MULTILINE)
    assert sorted(rows) == [str(n) for n in range(1, 9)]


# ── Handoff schema ───────────────────────────────────────────────────────

jsonschema = pytest.importorskip("jsonschema")


@pytest.fixture(scope="module")
def validator():
    schema = json.loads(SCHEMA.read_text(encoding="utf-8"))
    jsonschema.Draft202012Validator.check_schema(schema)
    return jsonschema.Draft202012Validator(schema, format_checker=jsonschema.FormatChecker())


def _example(route: str) -> dict:
    return json.loads((SKILL_DIR / "assets" / f"handoff-example-{route}.json").read_text(encoding="utf-8"))


def test_there_is_one_example_per_route():
    assert {p.stem.removeprefix("handoff-example-") for p in EXAMPLES} == {"investment", "credit", "operations"}


@pytest.mark.parametrize("path", EXAMPLES, ids=lambda p: p.stem)
def test_examples_are_valid(validator, path):
    errors = list(validator.iter_errors(json.loads(path.read_text(encoding="utf-8"))))
    assert not errors, [e.message for e in errors]


def test_unknown_fields_are_rejected(validator):
    handoff = _example("credit")
    handoff["customer"]["curp"] = "GOKA900101HDFRRN09"
    assert list(validator.iter_errors(handoff))


def test_route_must_be_a_known_destination(validator):
    handoff = _example("credit")
    handoff["route"] = "LOAN_OFFICER"
    assert list(validator.iter_errors(handoff))


@pytest.mark.parametrize("leak", [
    "Customer asked to check account number 1234567890.",
    "Customer shared INE IDMEX2045678912 in the chat.",
    "Customer RFC is GOKA900101AB1.",
    "Customer shared account number 1234 5678 9012.",
    "Customer shared account number 1234-5678-9012.",
])
def test_privacy_guard_rejects_identifiers_in_free_text(validator, leak):
    handoff = _example("operations")
    handoff["conversation_summary"] = leak
    assert list(validator.iter_errors(handoff)), f"leak not caught: {leak}"


def test_privacy_guard_allows_dates_and_reference_numbers(validator):
    handoff = _example("operations")
    handoff["conversation_summary"] = "Customer asked on 2026-10-10 about case REF-20431."
    handoff["customer"]["reference_number"] = "REF-20431"
    assert not list(validator.iter_errors(handoff))


def test_reference_number_rejects_account_numbers(validator):
    handoff = _example("operations")
    handoff["customer"]["reference_number"] = "1234 5678 9012"
    assert list(validator.iter_errors(handoff))


def test_handoff_is_marked_for_the_harness_not_the_customer():
    body = _body()
    assert "fenced block labelled `handoff`" in body
    assert "must not show it in the customer chat" in body


def test_privacy_guard_allows_normal_amounts(validator):
    handoff = _example("investment")
    handoff["conversation_summary"] = "New customer wants to invest 2,000,000 pesos, or 150000 pesos, for one year."
    assert not list(validator.iter_errors(handoff))


def test_handoff_needs_customer_consent_field(validator):
    handoff = copy.deepcopy(_example("investment"))
    del handoff["customer_confirmed_transfer"]
    assert list(validator.iter_errors(handoff))


def test_institution_profile_template_has_ticket_placeholders():
    profile = json.loads((SKILL_DIR / "assets" / "institution-profile.template.json").read_text(encoding="utf-8"))
    assert profile["financial_institution_name"] == "[FINANCIAL INSTITUTION NAME]"
    assert profile["regulator_1"] == "[REGULATOR 1]"
    assert profile["regulator_2"] == "[REGULATOR 2]"
    assert profile["investment_minimum_amount"] == 1000
