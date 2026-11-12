# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
import pytest

from rules import DOCUMENT_TYPES, Conflict, Invalid, UnsupportedType, check_document, check_open, check_registration, check_stage_change


@pytest.mark.parametrize("current, target", [
    ("new", "qualifying"),
    ("assessing", "documents"),
    ("qualifying", "qualified"),
    ("paused", "assessing"),
])
def test_moves_between_active_stages_either_way(current, target):
    check_stage_change(current, target, None, None, opted_out=False)


def test_a_move_to_the_same_stage_is_refused():
    with pytest.raises(Conflict, match="already new"):
        check_stage_change("new", "new", None, None, opted_out=False)


@pytest.mark.parametrize("target", ["paused", "discarded"])
def test_pause_and_discard_need_a_reason_code(target):
    with pytest.raises(Invalid, match="needs a reason_code"):
        check_stage_change("contacting", target, None, "no answer", opted_out=False)
    check_stage_change("contacting", target, "no_response", None, opted_out=False)


def test_a_reason_is_only_kept_for_pause_or_discard():
    with pytest.raises(Invalid, match="only for paused or discarded"):
        check_stage_change("new", "contacting", "no_response", None, opted_out=False)


def test_a_paused_opportunity_can_be_discarded():
    check_stage_change("paused", "discarded", "duplicate", None, opted_out=False)


def test_registered_only_through_registration():
    with pytest.raises(Conflict, match="registration"):
        check_stage_change("qualified", "registered", None, None, opted_out=False)


@pytest.mark.parametrize("final", ["registered", "discarded"])
def test_final_stages_do_not_change(final):
    with pytest.raises(Conflict, match="can no longer change"):
        check_stage_change(final, "new", None, None, opted_out=False)
    with pytest.raises(Conflict):
        check_open(final)


def test_contacting_is_refused_while_opted_out():
    with pytest.raises(Conflict, match="opted out"):
        check_stage_change("new", "contacting", None, None, opted_out=True)
    check_stage_change("new", "qualifying", None, None, opted_out=True)


def test_registration_only_from_qualified():
    check_registration("qualified")
    with pytest.raises(Conflict, match="only a qualified opportunity"):
        check_registration("pre_approved")


PNG_START, PNG_END = DOCUMENT_TYPES["image/png"]
JPEG_START, JPEG_END = DOCUMENT_TYPES["image/jpeg"]


@pytest.mark.parametrize("content_type, content", [
    ("image/png", PNG_START + b"pixels" + PNG_END),
    ("image/jpeg", JPEG_START + b"pixels" + JPEG_END),
    ("image/jpeg", JPEG_START + b"pixels" + JPEG_END + b"\x00" * 8),
])
def test_a_complete_image_is_accepted(content_type, content):
    check_document(content_type, content)


@pytest.mark.parametrize("content_type, content", [
    ("image/png", PNG_START + b"pixels"),
    ("image/png", b"\x89PNG\r\n\x1a\n" + b"pixels" + PNG_END),
    ("image/png", PNG_START + b"pixels" + PNG_END + b"more"),
    ("image/jpeg", JPEG_START + b"pixels"),
    ("image/jpeg", PNG_START + b"pixels" + PNG_END),
    ("image/png", b""),
])
def test_an_incomplete_or_other_file_is_refused(content_type, content):
    with pytest.raises(UnsupportedType, match="not a complete"):
        check_document(content_type, content)
