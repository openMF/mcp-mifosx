# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""The callers' tokens and permissions, without a database (the API's access is
tested in test_api.py)."""

import pytest

from auth import ACTIONS, CALLERS, Forbidden, check_move, load_tokens

INTAKE = "i" * 32
ELIGIBILITY = "e" * 32
IDENTITY = "d" * 32
REGISTRATION = "r" * 32
OFFICER = "o" * 32
TOKENS = {"LOS_INTAKE_TOKEN": INTAKE, "LOS_ELIGIBILITY_TOKEN": ELIGIBILITY, "LOS_IDENTITY_TOKEN": IDENTITY,
          "LOS_REGISTRATION_TOKEN": REGISTRATION, "LOS_OFFICER_TOKEN": OFFICER}


def test_each_caller_gets_its_token():
    assert load_tokens(TOKENS) == {"intake": INTAKE, "eligibility": ELIGIBILITY, "identity": IDENTITY,
                                   "registration": REGISTRATION, "officer": OFFICER}


@pytest.mark.parametrize("environ", [
    {key: value for key, value in TOKENS.items() if key != "LOS_INTAKE_TOKEN"},
    {**TOKENS, "LOS_INTAKE_TOKEN": ""},
    {**TOKENS, "LOS_INTAKE_TOKEN": "i" * 31},
])
def test_a_missing_or_short_token_is_refused(environ):
    with pytest.raises(ValueError, match="LOS_INTAKE_TOKEN is missing or shorter than 32 characters") as refused:
        load_tokens(environ)
    assert OFFICER not in str(refused.value)


def test_two_callers_may_not_share_a_token():
    every = ", ".join(TOKENS)
    with pytest.raises(ValueError, match=f"{every} must differ") as refused:
        load_tokens({**TOKENS, "LOS_ELIGIBILITY_TOKEN": OFFICER})
    assert OFFICER not in str(refused.value)


def test_permissions_name_known_actions():
    for permissions in CALLERS.values():
        assert permissions.actions <= set(ACTIONS)


@pytest.mark.parametrize("target", ["paused", "discarded"])
def test_intake_may_set_a_new_lead_aside(target):
    check_move("intake", "new", target)


@pytest.mark.parametrize("current, target", [
    ("new", "contacting"),
    ("new", "qualified"),
    ("contacting", "paused"),
    ("paused", "discarded"),
    ("pre_approved", "discarded"),
])
def test_intake_may_make_no_other_move(current, target):
    with pytest.raises(Forbidden, match="intake may only move an opportunity from new to discarded or paused"):
        check_move("intake", current, target)


@pytest.mark.parametrize("target", ["documents", "paused"])
def test_eligibility_may_move_a_qualifying_opportunity_on_or_pause_it(target):
    check_move("eligibility", "qualifying", target)


@pytest.mark.parametrize("current, target", [
    ("new", "documents"),
    ("documents", "paused"),
    ("qualifying", "pre_approved"),
    ("qualifying", "qualified"),
    ("qualifying", "discarded"),
])
def test_eligibility_may_make_no_other_move(current, target):
    with pytest.raises(Forbidden, match="eligibility may only move an opportunity from qualifying to documents or paused"):
        check_move("eligibility", current, target)


@pytest.mark.parametrize("target", ["assessing", "paused"])
def test_identity_may_move_an_opportunity_in_documents_on_or_pause_it(target):
    check_move("identity", "documents", target)


@pytest.mark.parametrize("current, target", [
    ("qualifying", "documents"),
    ("assessing", "paused"),
    ("documents", "pre_approved"),
    ("documents", "qualified"),
    ("documents", "discarded"),
])
def test_identity_may_make_no_other_move(current, target):
    with pytest.raises(Forbidden, match="identity may only move an opportunity from documents to assessing or paused"):
        check_move("identity", current, target)


def test_registration_moves_no_stage():
    assert "stage" not in CALLERS["registration"].actions


def test_officers_may_make_any_move():
    check_move("officer", "pre_approved", "discarded")
    check_move("officer", "paused", "qualified")
