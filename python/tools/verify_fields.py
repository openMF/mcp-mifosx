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
_NAME_REVIEW_RATIO = 0.85  # names at/above this similarity are the same person w/ an OCR slip

# International country-code prefixes (CC + national number) captured numbers
# may carry, e.g. '+91 98765 43210' -> '919876543210'. Longest first so wider
# prefixes win over narrower tails.
_INTL_PREFIXES = (
    "1", "7", "20", "27", "30", "31", "36", "40", "41", "43", "44", "45", "46",
    "47", "48", "49", "51", "52", "53", "54", "55", "56", "57", "58", "59", "60",
    "61", "62", "63", "64", "65", "66", "67", "68", "69", "70", "71", "72", "73",
    "74", "75", "76", "77", "78", "79", "80", "82", "83", "84", "85", "86", "87",
    "88", "89", "90", "91", "92", "93", "94", "95", "96", "97", "98",
)
# Country codes whose national significant numbers are shorter than the
# domestic ~10-digit budget, so an international capture can be exactly ten
# digits long while still carrying a leading prefix (Singapore: +65 ->
# 8-digit national numbers, e.g. '+65 8123 4567' -> national form '81234567').
_INTL_SHORT_NATIONAL_PREFIXES = frozenset({"65", "852", "853"})


def _fold(text: Any) -> str:
    """Case/punctuation folded text: '  Rt. Jean-Luc P.  ' -> 'rt jean luc p'."""
    if not isinstance(text, str):
        return ""
    norm = unicodedata.normalize("NFKD", text)
    norm = "".join(c for c in norm if not unicodedata.combining(c))
    norm = re.sub(r"[^0-9a-z\s]", " ", norm.lower())
    return re.sub(r"\s+", " ", norm).strip()


def _tokens(text: str) -> List[str]:
    """Whitespace-separated fold tokens of a name-ish string."""
    return [t for t in _fold(text).split() if t]


def compare_name(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Names compare as token sets: 'Jean Luc' matches 'LucJean P' order-free.

    A nickname never passes on its own; tokens must all be present in the
    bank name(s) or the fold-equal check must hit. A *partial* extracted
    name (some but not all bank-name tokens missing) never matches: it goes
    to REVIEW so a human judges the OCR slip.
    """
    ext = _tokens(extracted)
    if not ext:
        return NO_SOURCE_DATA, None
    folded_ext = _fold(extracted)
    review_value: Optional[str] = None
    for bank in bank_values or []:
        bank_tokens = _tokens(bank)
        if not bank_tokens:
            continue
        if folded_ext == _fold(bank) or set(ext) == set(bank_tokens):
            return MATCH, str(bank)
        partial = set(ext) < set(bank_tokens)
        joint = SequenceMatcher(None, folded_ext, _fold(bank)).ratio()
        if joint >= _NAME_REVIEW_RATIO and review_value is None:
            review_value = str(bank)
        if partial and review_value is None:
            review_value = str(bank)
    if review_value is not None:
        return REVIEW, review_value
    return MISMATCH, None


def _normalize_id(extracted: Any) -> str:
    """Identity numbers: alphanumeric chars plus decimal digits, separators dropped.

    Unicode decimal digits stay significant (an Arabic-Indic or fullwidth digit is
    a digit, not punctuation to strip), so they are folded to their ASCII value
    before comparison; separators and case differences never change an ID's
    identity.
    """
    if not isinstance(extracted, str):
        return ""
    norm = unicodedata.normalize("NFKD", extracted)
    norm = "".join(c for c in norm if not unicodedata.combining(c))
    chars: List[str] = []
    for ch in norm.lower():
        if ch.isdecimal():
            chars.append(str(unicodedata.digit(ch)))
        elif "a" <= ch <= "z":
            chars.append(ch)
    return "".join(chars)


def compare_id(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Identity numbers compare exact after alphanumeric folding: a
    near-miss digit on an ID means the wrong document, so there is no REVIEW."""
    ext = _normalize_id(extracted)
    if not ext:
        return NO_SOURCE_DATA, None
    for bank in bank_values or []:
        if _normalize_id(bank) == ext:
            return MATCH, str(bank)
    return MISMATCH, None


def _normalize_mobile(digits: str) -> str:
    """Complete-number normalisation: digits only, minus a leading country-code prefix.

    International captures carry a +CC prefix (e.g. '919876543210'); national
    numbers top out near 10 digits, so a longer digit string gets its country
    prefix stripped before the full-equality compare. No substring or suffix
    logic ever runs here — a short extracted number can no longer match a longer
    bank number as if it were the same subscriber.

    Country-code prefixes of differing widths (2- and 3-digit codes such as
    Singapore's +65) are resolved longest-first and only when the remaining
    digits form a plausible national number, so an international capture like
    '+65 8123 4567' normalises to its national form ('81234567') and matches the
    bank record, while domestic numbers pass through unchanged.
    """
    d = re.sub(r"\D", "", _fold(digits))
    national = d
    if len(national) > 10:
        for cc in sorted(_INTL_PREFIXES, key=len, reverse=True):
            if national.startswith(cc) and 4 <= len(national) - len(cc) <= 10:
                national = national[len(cc):]
                break
    elif len(national) == 10 and national[:2] in _INTL_SHORT_NATIONAL_PREFIXES:
        national = national[2:]
    while len(national) > 10 and len(national) - 1 >= 6:
        national = national[1:]
    return national


def compare_mobile(extracted: Any, bank_values: List[Any]) -> Tuple[str, Optional[str]]:
    """Phone numbers compare digit-exact as complete numbers, tolerating a
    leading country-code prefix: '+91 98765 43210' and '98765 43210' are the same
    subscriber. Partial/suffix matches are never accepted."""
    ext = _normalize_mobile(extracted)
    if not ext:
        return NO_SOURCE_DATA, None
    for bank in bank_values or []:
        bank_digits = _normalize_mobile(str(bank))
        if not bank_digits:
            continue
        if ext == bank_digits:
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
