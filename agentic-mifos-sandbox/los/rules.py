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

# Identity documents the LOS keeps: images only, each type with the bytes a
# complete file starts and ends with. The LOS checks those, never decodes an
# image: parsing untrusted images is left to the vision model's server.
DOCUMENT_KINDS = ("curp",)
DOCUMENT_TYPES = {
    # The signature, then the IHDR chunk; the IEND chunk last
    "image/png": (b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR", b"\x00\x00\x00\x00IEND\xaeB`\x82"),
    # Start of image; end of image (some cameras pad the file with zeros after it)
    "image/jpeg": (b"\xff\xd8\xff", b"\xff\xd9"),
}
MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
# The outcome of a document's check (the identity workflow): the document is
# valid, another one is needed, or it is not the applicant's. A valid document
# moves the opportunity on to assessing; one that is not the applicant's pauses
# it for a person; for another one, it stays in documents.
CHECK_RESULTS = ("valid", "request_document", "document_mismatch")
CHECK_MOVES = {"valid": "assessing", "document_mismatch": PAUSED}


class Conflict(Exception):
    """The opportunity's current state does not allow the request (HTTP 409)."""


class Invalid(Exception):
    """The request is incomplete or inconsistent (HTTP 422)."""


class TooLarge(Exception):
    """A document over MAX_DOCUMENT_BYTES (HTTP 413)."""


class UnsupportedType(Exception):
    """A document that is not one of DOCUMENT_TYPES (HTTP 415)."""


def check_document_type(content_type: str) -> None:
    if content_type not in DOCUMENT_TYPES:
        raise UnsupportedType(f"a document must be one of {', '.join(DOCUMENT_TYPES)}, not {content_type or 'untyped'}")


def check_document(content_type: str, content: bytes) -> None:
    """Refuses a file that is not a complete image of the declared type: empty,
    another type, truncated, or with bytes after its end."""
    check_document_type(content_type)
    start, end = DOCUMENT_TYPES[content_type]
    if content_type == "image/jpeg":
        content = content.rstrip(b"\x00")
    if not (content.startswith(start) and content.endswith(end)):
        raise UnsupportedType(f"the file is not a complete {content_type} image")


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
