# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Opportunity stages and the rules for changing them.

The rules here are structural only: which stages exist, which moves are
possible and what a move must carry. Whether an applicant is eligible to
advance is decided by the eligibility rules, not by this service.
"""

# Stages an opportunity works through, in their usual order. It may move
# between them in either direction (back to `documents` when a file is
# missing, for example); every move is recorded.
ACTIVE_STAGES = ("new", "contacting", "qualifying", "documents", "assessing", "pre_approved", "qualified")
PAUSED = "paused"
DISCARDED = "discarded"
# Client and loan application created in Fineract (only from `qualified`).
REGISTERED = "registered"
STAGES = (*ACTIVE_STAGES, PAUSED, DISCARDED, REGISTERED)
# Nothing changes after these, except opting out of contact.
FINAL_STAGES = (REGISTERED, DISCARDED)

CONSENT_KINDS = ("bureau", "sat", "contact")


class Conflict(Exception):
    """The opportunity's current state does not allow the request (HTTP 409)."""


class Invalid(Exception):
    """The request is incomplete or inconsistent (HTTP 422)."""


def check_open(stage: str) -> None:
    """Refuses changes to an opportunity that is registered or discarded."""
    if stage in FINAL_STAGES:
        raise Conflict(f"the opportunity is {stage} and can no longer change")


def check_stage_change(current: str, target: str, reason_code: str | None, reason_note: str | None, opted_out: bool) -> None:
    """Checks a move from `current` to `target` (POST /opportunities/{id}/stage)."""
    check_open(current)
    if target == current:
        raise Conflict(f"the opportunity is already {current}")
    if target == REGISTERED:
        raise Conflict("an opportunity becomes registered through POST /opportunities/{id}/registration")
    if target in (PAUSED, DISCARDED):
        if not reason_code:
            raise Invalid(f"moving to {target} needs a reason_code")
    elif reason_code or reason_note:
        raise Invalid("a reason is kept only for paused or discarded")
    if target == "contacting" and opted_out:
        raise Conflict("the applicant opted out of contact")


def check_registration(current: str) -> None:
    """Only a qualified opportunity is registered in Fineract."""
    if current != "qualified":
        raise Conflict(f"only a qualified opportunity can be registered; this one is {current}")
