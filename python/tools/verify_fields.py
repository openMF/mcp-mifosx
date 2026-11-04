"""Deterministic comparison of document-extracted fields against bank records.

The vision model that reads a photo lives in the AI client (this server
never calls an LLM): the agent arranges the OCR/vision extraction into a
field dict and hands it to ``verify_client_details``, which fetches the
client's records from Fineract and compares them here — deterministically,
so the same inputs always give the same verdicts and every verdict can be
audited field by field.

Verdict vocabulary (per field):

- ``MATCH``      the extracted value equals the bank record (whitespace,
                 punctuation, case and order-tolerant for names).
- ``REVIEW``     close to the record but not equal — plausible OCR slip or a
                 nickname; a human should look at the document.
- ``MISMATCH``   clearly different (IDs and phone numbers are compared
                 exactly; near-equality there is not a pass).
- ``NO_SOURCE_DATA``  the bank record does not carry that field, so nothing
                 could be compared; not a pass and not a fail.
"""

import re
import unicodedata
from difflib import SequenceMatcher
from typing import Any, Dict, List, Optional, Tuple

MATCH, REVIEW, MISMATCH, NO_SOURCE_DATA = "MATCH", "REVIEW", "MISMATCH", "NO_SOURCE_DATA"

# Two names this similar after folding are almost always the same person
# with an OCR slip ("Jean-Luc vs Jean Luc"); below that, characters really
# differ ("Reyya" vs "Reyyappa" territory needs a human eye).
_NAME_REVIEW_RATIO = 0.85
_MOBILE_MIN_DIGITS = 6  # a phone suffix shorter than this could match anything


def _fold(text: Any) -> str:
    """Case/punctuation folded text: '  Rt. Jean-Luc P.  ' -> 'rt jean luc p'."""
    if not isinstance(text, str):
        return ""
    norm = unicodedata.normalize("NFKD", text)
    norm = "".join(c for c in norm if not unicodedata.combining(c))
    norm = re.sub(r"[^0-9a-z\s]", " ", norm.lower())
    return re.sub(r"\s+", " ", norm).strip()


def _tokens(text: str) -> List[str]:
    return [t for t in _fold(text).split() if t]


def compare_name(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Names compare as token sets: 'Jean Luc' matches 'LucJean P' order-free.

    A nickname never passes on its own; tokens must all be present in the
    bank name(s) or the fold-equal check must hit.
    """
    ext = _tokens(extracted)
    if not ext:
        return NO_SOURCE_DATA, None
    folded_ext = _fold(extracted)
    for bank in bank_values or []:
        bank_tokens = _tokens(bank)
        if not bank_tokens:
            continue
        if folded_ext == _fold(bank) or set(ext) == set(bank_tokens):
            return MATCH, str(bank)
        if set(ext) <= set(bank_tokens):
            # extracted short-form ("jean luc") inside the full bank name
            return MATCH, str(bank)
        joint = SequenceMatcher(None, folded_ext, _fold(bank)).ratio()
        if joint >= _NAME_REVIEW_RATIO:
            return REVIEW, str(bank)
    return MISMATCH, None


def compare_id(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Identity numbers compare exact after non-alphanumeric folding: a
    near-miss digit on an ID means the wrong document, so there is no REVIEW."""
    ext = _fold(extracted)
    if not ext:
        return NO_SOURCE_DATA, None
    for bank in bank_values or []:
        if _fold(bank) == ext:
            return MATCH, str(bank)
    return MISMATCH, None


def compare_mobile(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Phone numbers compare digit-exact, tolerating country-code prefixes:
    '+91 98...' and '98...' are the same subscriber."""
    ext = re.sub(r"\D", "", _fold(extracted))
    if not ext:
        return NO_SOURCE_DATA, None
    for bank in bank_values or []:
        bank_digits = re.sub(r"\D", "", str(bank))
        if not bank_digits:
            continue
        if ext == bank_digits:
            return MATCH, str(bank)
        if len(ext) >= _MOBILE_MIN_DIGITS and bank_digits.endswith(ext):
            return MATCH, str(bank)
        if len(bank_digits) >= _MOBILE_MIN_DIGITS and ext.endswith(bank_digits):
            return MATCH, str(bank)
    return MISMATCH, None


def _client_source_values(profile: Dict[str, Any], field: str) -> List[Any]:
    """The client record values a given field can be checked against."""
    if field == "full_name":
        parts = [profile.get(k) for k in ("firstname", "middlename", "lastname")]
        tokens = [str(p) for p in parts if p]
        values: List[Any] = []
        if profile.get("displayName"):
            values.append(profile["displayName"])
        if tokens:
            values.append(" ".join(tokens))
        return values
    if field == "national_id":
        for key in ("national_id", "documentKey"):
            if profile.get(key):
                return [profile[key]]
        identifiers = profile.get("identifiers") or []
        return [i.get("documentKey") for i in identifiers if isinstance(i, dict) and i.get("documentKey")]
    if field == "mobile_no":
        for key in ("mobile_no", "mobileNo", "phone"):
            if profile.get(key):
                return [profile[key]]
        return []
    return []


def verify_fields(extracted: Dict[str, Any], profile: Dict[str, Any]) -> Dict[str, Any]:
    """Compare every extracted field against the client profile.

    ``extracted`` is the field dict the AI client produced from the document
    photo (full_name / national_id / mobile_no — the fields the bank can
    check). Returns per-field verdicts plus an overall result.
    """
    field_verdicts: Dict[str, Dict[str, Any]] = {}
    for field, compare in (
        ("full_name", compare_name),
        ("national_id", compare_id),
        ("mobile_no", compare_mobile),
    ):
        if not extracted.get(field):
            continue
        bank_values = _client_source_values(profile, field)
        if not bank_values:
            # nothing on the record to compare with: not a pass, not a fail
            field_verdicts[field] = {
                "verdict": NO_SOURCE_DATA,
                "extracted": extracted[field],
                "note": "the client record carries no such field to compare with",
            }
            continue
        verdict, source = compare(extracted[field], bank_values)
        entry: Dict[str, Any] = {
            "verdict": verdict,
            "extracted": extracted[field],
        }
        if source is not None:
            entry["source_value"] = source
        field_verdicts[field] = entry

    compared = [v["verdict"] for v in field_verdicts.values()]
    if not compared:
        overall = "NOTHING_TO_VERIFY"
    elif any(v == MISMATCH for v in compared):
        overall = "FAILED"
    elif any(v != MATCH for v in compared):
        overall = "NEEDS_REVIEW"
    else:
        overall = "VERIFIED"

    return {
        "verified": overall == "VERIFIED",
        "overall": overall,
        "fields": field_verdicts,
        "message": (
            {
                "VERIFIED": "Every extracted detail matched the client record.",
                "NEEDS_REVIEW": "Some details are close but not exact — a human should check the document.",
                "FAILED": "Extracted details differ from the client record.",
                "NOTHING_TO_VERIFY": "No comparable fields were extracted.",
            }[overall]
        ),
    }
