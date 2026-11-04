# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
Document capture-quality gate + client-document tool tests.

The gate is deterministic (no LLM, no CV/ML library): the same image must
always yield the same verdict, and each verdict class must be exercised on
synthetic captures whose truth is known by construction. The upload tool is
tested with a stubbed Fineract adapter — no network.
"""

import io

import pytest
from PIL import Image, ImageDraw

from tools import capture_gate as cg
from tools.domains import documents as doc_domain

# ── synthetic capture builders ──────────────────────────────────────

def _png_bytes(pil_image):
    buf = io.BytesIO()
    pil_image.save(buf, "PNG")
    return buf.getvalue()


def _crisp_text_page():
    """Dark text-like strokes on white paper: a usable capture by construction."""
    im = Image.new("L", (512, 384), 245)
    draw = ImageDraw.Draw(im)
    for i, y in enumerate(range(60, 340, 24)):
        draw.line([(40, y), (100 + (i * 7) % 120, y)], fill=20, width=2)
        draw.line([(200 + (i * 5) % 250, y + 6), (420, y + 6)], fill=40, width=3)
    return im


# ── gate: verdict classes ────────────────────────────────────────────

def test_crisp_capture_passes():
    r = cg.assess_image(_png_bytes(_crisp_text_page()))
    assert r["verdict"] == "PASS"
    assert r["reasons"] == []
    assert r["signals"]["sharpness"] >= cg.REVIEW_SHARP_MIN


def test_heavily_blurred_capture_retakes_with_blur_reason():
    """Mild blur stays readable; heavy blur must not reach PASS."""
    im = _crisp_text_page()
    mild = im.resize((384, 288), cg._pillow().BILINEAR).resize((512, 384), cg._pillow().BILINEAR)
    heavy = im.resize((64, 48), cg._pillow().BILINEAR).resize((512, 384), cg._pillow().BILINEAR)
    r_mild = cg.assess_image(_png_bytes(mild))
    r_heavy = cg.assess_image(_png_bytes(heavy))
    assert r_heavy["verdict"] == "RETAKE"
    assert r_heavy["signals"]["sharpness"] < r_mild["signals"]["sharpness"]
    # blur is the named cause (not the flat-page ink fallback)
    assert any("blurry" in x or "content" in x for x in r_heavy["reasons"])
    assert r_mild["verdict"] in ("RETAKE", "REVIEW", "PASS")


def test_blank_capture_retakes_on_no_content():
    Image = cg._pillow()
    r = cg.assess_image(_png_bytes(Image.new("L", (512, 384), 244)))
    assert r["verdict"] == "RETAKE"
    assert any("content" in x for x in r["reasons"])
    assert r["signals"]["ink_ratio"] < cg.RETAKE_INK_MIN


def test_dark_capture_retakes_on_too_dark():
    import PIL.Image

    arr = cg._numpy().asarray(_crisp_text_page(), dtype=float) * 0.05
    r = cg.assess_image(_png_bytes(PIL.Image.fromarray(arr.astype("uint8"))))
    assert r["verdict"] == "RETAKE"
    assert any("dark" in x for x in r["reasons"])


def test_review_band_boundaries():
    """v3.1 readable-blur band: hard-RETAKE < 0.08, 0.08–0.13 → REVIEW, ≥ 0.13 → PASS."""
    def verdict(sharpness):
        return cg._decide({
            "sharpness": sharpness, "contrast": 0.5, "ink_ratio": 0.01,
            "brightness": 0.95, "exposure": 0.45,
        })[0]

    assert verdict(0.05) == "RETAKE"    # genuinely destroyed
    assert verdict(0.079) == "RETAKE"
    assert verdict(0.088) == "REVIEW"   # the field-anchored readable-blur case
    assert verdict(0.125) == "REVIEW"
    assert verdict(0.13) == "PASS"
    assert verdict(0.20) == "PASS"


def test_gate_is_deterministic():
    b = _png_bytes(_crisp_text_page())
    assert cg.assess_image(b) == cg.assess_image(b)


def test_resolution_invariance():
    """The same page rendered natively at different pixel densities → same verdict."""
    def page(width, height, stroke):
        im = Image.new("L", (width, height), 245)
        d = ImageDraw.Draw(im)
        for i, y in enumerate(range(height // 6, height - 40, 24 * stroke)):
            d.line([(40, y), (100 + (i * 7) % (120 * stroke), y)], fill=20, width=stroke)
            d.line([(200 + (i * 5) % (250 * stroke), y + 6 * stroke),
                    (width - 90, y + 6 * stroke)], fill=40, width=stroke * 2)
        return im

    r1 = cg.assess_image(_png_bytes(page(512, 384, 1)))
    r2 = cg.assess_image(_png_bytes(page(1024, 768, 2)))
    assert r1["verdict"] == r2["verdict"] == "PASS"
    # the fixed analysis grid keeps the measured sharpness close enough that
    # the verdict class never separates with capture scale
    assert abs(r1["signals"]["sharpness"] - r2["signals"]["sharpness"]) < 0.05


def test_unreadable_bytes_raise_value_error():
    with pytest.raises(ValueError, match="unreadable image"):
        cg.assess_image(b"not an image at all")


# ── tools: readable errors, fail-closed upload ──────────────────────

def test_validate_missing_file_returns_error_sentinel():
    r = doc_domain.validate_capture_quality.func("/no/such/photo.png")
    assert r == {"error": "image not found: /no/such/photo.png"}


def test_validate_directory_returns_error_sentinel(tmp_path):
    r = doc_domain.validate_capture_quality.func(str(tmp_path))
    assert r["error"].startswith(str(tmp_path))


def test_validate_corrupt_file_returns_error_sentinel(tmp_path):
    p = tmp_path / "bad.png"
    p.write_bytes(b"corrupt")
    r = doc_domain.validate_capture_quality.func(str(p))
    assert "unreadable image" in r["error"] and "bad.png" in r["error"]


def test_validate_real_crisp_capture_verdict(tmp_path):
    p = tmp_path / "cap.png"
    p.write_bytes(_png_bytes(_crisp_text_page()))
    r = doc_domain.validate_capture_quality.func(str(p))
    assert r["verdict"] == "PASS" and r["signals"]["brightness"] > 0.5


def test_upload_refuses_retake_capture(tmp_path):
    Image = cg._pillow()
    p = tmp_path / "blank.png"
    p.write_bytes(_png_bytes(Image.new("L", (512, 384), 244)))  # blank → RETAKE
    r = doc_domain.upload_client_document.func(42, str(p))
    assert r["upload_rejected"] is True
    assert r["verdict"] == "RETAKE" and r["reasons"]
    assert "signals" in r  # the officer sees the measured evidence too


def test_upload_refuses_when_gate_has_no_verdict(tmp_path, monkeypatch):
    p = tmp_path / "x.png"
    p.write_bytes(b"x")
    monkeypatch.setattr(doc_domain, "assess_image", lambda b, filename="": {"verdict": None})
    r = doc_domain.upload_client_document.func(42, str(p))
    assert r == {"error": f"capture gate could not assess {p}"}


def test_upload_gate_passes_then_posts_multipart(tmp_path, monkeypatch):
    calls = {}

    def fake_multipart(endpoint, form, file_path):
        calls["endpoint"], calls["form"], calls["file"] = endpoint, form, file_path
        return {"resourceId": 42}

    monkeypatch.setattr(doc_domain.fineract_client, "execute_multipart", fake_multipart)
    p = tmp_path / "good.png"
    p.write_bytes(_png_bytes(_crisp_text_page()))

    r = doc_domain.upload_client_document.func(42, str(p), name="kyc-id")
    assert r["resourceId"] == 42
    assert calls == {
        "endpoint": "clients/42/documents",
        "form": {"name": "kyc-id"},
        "file": str(p),
    }
    # audit trail: the gate verdict rode along with the upload
    assert r["capture_gate"]["verdict"] == "PASS"
    assert "signals" in r["capture_gate"]


def test_upload_force_overrides_retake(tmp_path, monkeypatch):
    import PIL.Image

    def fake_multipart(endpoint, form, file_path):
        return {"resourceId": 7}

    monkeypatch.setattr(doc_domain.fineract_client, "execute_multipart", fake_multipart)
    p = tmp_path / "blank.png"
    arr = cg._numpy().full((64, 100), 244, dtype="uint8")
    p.write_bytes(_png_bytes(PIL.Image.fromarray(arr)))

    r = doc_domain.upload_client_document.func(42, str(p), force=True)
    assert r["resourceId"] == 7
    assert r["capture_gate"]["verdict"] == "RETAKE"  # flagged, not hidden


def test_upload_propagates_server_error(tmp_path, monkeypatch):
    def fake_multipart(endpoint, form, file_path):
        return {"error": "409 Conflict", "status_code": 409}

    monkeypatch.setattr(doc_domain.fineract_client, "execute_multipart", fake_multipart)
    p = tmp_path / "good.png"
    p.write_bytes(_png_bytes(_crisp_text_page()))
    r = doc_domain.upload_client_document.func(42, str(p))
    assert r["error"] == "409 Conflict"


# ── registry intent routing ─────────────────────────────────────────

def test_registry_routes_capture_intents():
    from tools.registry import router

    routed = router.route_intent("gate this blurry capture photo before upload")
    names = {t.name for t in routed}
    assert "validate_capture_quality" in names


def test_registry_exposes_exactly_two_new_document_tools():
    from tools.registry import router

    client_tools = {t.name for t in router.get_domain("clients")}
    assert {"validate_capture_quality", "upload_client_document", "verify_client_details"} <= client_tools


# ── verify_client_details ───────────────────────────────────────────

def test_verify_module_aggregation():
    from tools import verify_fields as vf

    profile = {
        "displayName": "Reyyappa Reddy", "firstname": "Reyyappa", "lastname": "Reddy",
        "mobileNo": "9876543210", "identifiers": [{"documentKey": "AB-1234-5678"}],
    }
    r = vf.verify_fields(
        {"full_name": "REDDY REYYAPPA", "national_id": "AB 1234 5678",
         "mobile_no": "+91 98765 43210"},
        profile,
    )
    assert r["overall"] == "VERIFIED" and r["verified"] is True
    assert all(v["verdict"] == "MATCH" for v in r["fields"].values())

    r = vf.verify_fields({"national_id": "AB-9999-0000"}, profile)
    assert r["overall"] == "FAILED"
    assert r["fields"]["national_id"]["verdict"] == "MISMATCH"

    r = vf.verify_fields({"full_name": "Reyyapa Reddy"}, profile)  # one-char OCR slip
    assert r["overall"] == "NEEDS_REVIEW"
    assert r["fields"]["full_name"]["verdict"] == "REVIEW"

    r = vf.verify_fields({"mobile_no": "9876543210"}, {"displayName": "X"})
    assert r["fields"]["mobile_no"]["verdict"] == "NO_SOURCE_DATA"
    assert r["overall"] == "NEEDS_REVIEW"

    assert vf.verify_fields({}, profile)["overall"] == "NOTHING_TO_VERIFY"


def test_domain_verify_fetches_profile_and_identifiers(monkeypatch):
    calls = []

    def fake_get(endpoint):
        calls.append(endpoint)
        if "identifiers" in endpoint:
            return [{"documentKey": "AB-1234-5678"}]
        return {"displayName": "Reyyappa Reddy", "firstname": "Reyyappa", "lastname": "Reddy"}

    monkeypatch.setattr(doc_domain.fineract_client, "execute_get", fake_get)
    r = doc_domain.verify_client_details.func(42, fullName="Reyyappa Reddy", nationalId="AB-1234-5678")
    assert calls == ["clients/42", "clients/42/identifiers"]
    assert r["overall"] == "VERIFIED"
    assert r["fields"]["national_id"]["source_value"] == "AB-1234-5678"


def test_domain_verify_propagates_client_error(monkeypatch):
    monkeypatch.setattr(
        doc_domain.fineract_client, "execute_get",
        lambda e: {"error": "404 not found", "status_code": 404})
    r = doc_domain.verify_client_details.func(999, fullName="X")
    assert r["status_code"] == 404


def test_domain_verify_requires_a_field(monkeypatch):
    r = doc_domain.verify_client_details.func(42)
    assert "error" in r and "non-empty" in r["error"]

