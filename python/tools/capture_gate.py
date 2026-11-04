# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Document capture-quality gate: RETAKE | REVIEW | PASS (deterministic, offline).

Decides whether a photo the officer just took of a document (KYC ID, loan
form, ...) is good enough to keep asking uploaders to pay server-side
processing costs for it. It is the instrument a document-intake agent needs
*before* `upload_client_document` turns an unreadable capture into a
permanent client file. Content verification (does the extracted name/ID
actually match the client?) is a separate tool (`verify_client_details`) —
this gate only answers "is the capture readable", not "is the document
the right one".

Design contract:

- **No LLM, no CV/ML library**: the whole gate is ~50 lines of fixed NumPy
  math. Deterministic: the same image always yields the same verdict.
- **Resolution-invariant**: every capture is resampled to a fixed analysis
  grid (max side 512, bilinear) before measuring, so thresholds transfer
  across devices.
- **Measured where the content is**: sharpness is the mean gradient
  magnitude inside a 1-px dilation of the ink mask (text-edge strength),
  not a whole-frame average diluted by the paper background; contrast is
  `bg − p25(ink pixels)` — whole-frame luminance percentiles break whenever
  ink covers fewer pixels than the percentile.
- **Thresholds are supervised, not hand-tuned**: calibrated on a
  2000-image document corpus with per-image degradation labels:
  sharpness separates blurred (max 0.092) from sharp-labelled (min 0.208)
  with no overlap; zero false rejects on all 952 sharp-labelled images at
  the v3.1 cut; no blurred-labelled capture ever reaches PASS.
- **v3.1 readable-blur band**: real field captures showed camera blur on a
  printed page can stay human-readable into the 0.08–0.13 zone (a printed
  form measured 0.088 and was plainly legible), so the hard-RETAKE cut is
  sharpness < 0.08 and speculating into that zone lands in REVIEW
  ("flag, let a human judge") instead of costing the officer a re-take.

The verdict contract `RETAKE | REVIEW | PASS` is the stable interface that
the Fineract agent workflows (identity / document intake) rely on; changes
to thresholds must keep those semantics. The same gate ships in the
self-service MCP server (openMF/mcp-mifosx-self-service).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

if TYPE_CHECKING:  # for annotations only; numpy resolves lazily at runtime
    import numpy as np

ANALYSIS_MAX_SIDE = 512  # fixed analysis grid: thresholds stay resolution-invariant
INK_MARGIN = 0.25        # ink pixel: luma < (p90 luma) - INK_MARGIN
INK_BG_PCT = 90          # background estimate = 90th percentile of luma
MIN_INK_FOR_CONTRAST = 16  # min ink pixels before contrast uses the ink region
CONTRAST_PCTS = (0.5, 99.5)  # whole-frame fallback when no ink is detectable

# v3.1 gate thresholds (supervised on 2000 labelled images + field evidence).
RETAKE_BRIGHT_MIN = 0.10    # mean luma below this -> too dark to read
RETAKE_INK_MIN = 0.002      # less ink than this -> blank page / wall / crushed
RETAKE_SHARP_MIN = 0.08     # text-edge strength below this -> unreadably blurry
REVIEW_BRIGHT_MIN = 0.25    # dim but possibly readable
REVIEW_CONTRAST_MIN = 0.18  # washed-out print / crushed capture
REVIEW_INK_MIN = 0.003      # near-blank content
REVIEW_SHARP_MIN = 0.13     # soft text edges (mild blur / faint ink)

VERDICT_RETAKE, VERDICT_REVIEW, VERDICT_PASS = "RETAKE", "REVIEW", "PASS"


def assess_image(image_bytes: bytes, filename: str = "capture") -> dict:
    """Quality gate a document capture by its raw bytes.

    Returns a JSON-serialisable dict:

        verdict   "RETAKE" | "REVIEW" | "PASS"
        reasons   the exact gates that tripped, e.g.
                  "unreadably blurry (sharpness 0.031 < 0.08)"
        signals   sharpness / contrast / ink_ratio / brightness / exposure.

    Raises ValueError when the bytes do not decode as an image.
    """
    np = _numpy()
    Image = _pillow()
    import io

    try:
        with Image.open(io.BytesIO(image_bytes)) as im:
            rgb = np.asarray(im.convert("RGB"), dtype=np.float64) / 255.0
    except Exception as e:  # unreadable input (corrupt file, non-image bytes)
        raise ValueError(f"unreadable image {filename!r} ({e})") from e
    return _gate(rgb, np)


def _gate(img: np.ndarray, np) -> dict:
    """Signal extraction + v3.1 decision for one (H, W, 3) float RGB image.

    Written in the classic import-argument style so every math step stays
    legible as one pass over the analysis grid; pure NumPy otherwise.
    """
    luma = _analysis_grid(img, np)
    grad = _grad_mag(luma, np)

    bg = np.percentile(luma, INK_BG_PCT)
    ink_mask = luma < (bg - INK_MARGIN)
    ink_ratio = float(ink_mask.mean())

    near = _dilate(ink_mask, np)
    near_count = float(near.sum())
    if near_count > 0:
        sharpness = float((grad * near).sum() / near_count)
    else:
        sharpness = float(grad.mean())  # uniform / crushed capture fallback

    ink_vals = luma[ink_mask]
    if ink_vals.size >= MIN_INK_FOR_CONTRAST:
        contrast = max(float(bg - np.percentile(ink_vals, 25)), 0.0)
    else:
        p_lo = np.percentile(luma, CONTRAST_PCTS[0])
        p_hi = np.percentile(luma, CONTRAST_PCTS[1])
        contrast = max(float(p_hi - p_lo), 0.0)

    brightness = float(luma.mean())
    signals = {
        "sharpness": round(sharpness, 6),
        "contrast": round(contrast, 6),
        "ink_ratio": round(ink_ratio, 6),
        "brightness": round(brightness, 6),
        "exposure": round(abs(brightness - 0.5), 6),
    }
    verdict, reasons = _decide(signals)
    return {"verdict": verdict, "reasons": reasons, "signals": signals}


def _decide(s: dict) -> tuple[str, list[str]]:
    reasons: list[str] = []
    if s["brightness"] < RETAKE_BRIGHT_MIN:
        reasons.append(f"too dark to read (brightness {s['brightness']:.3f} < {RETAKE_BRIGHT_MIN})")
    if s["ink_ratio"] < RETAKE_INK_MIN:
        reasons.append(f"no detectable content (ink_ratio {s['ink_ratio']:.4f} < {RETAKE_INK_MIN})")
    elif s["sharpness"] < RETAKE_SHARP_MIN:
        reasons.append(f"unreadably blurry (sharpness {s['sharpness']:.3f} < {RETAKE_SHARP_MIN})")
    if reasons:
        return VERDICT_RETAKE, reasons

    if s["brightness"] < REVIEW_BRIGHT_MIN:
        reasons.append(f"dim but possibly readable (brightness {s['brightness']:.3f} < {REVIEW_BRIGHT_MIN})")
    if s["contrast"] < REVIEW_CONTRAST_MIN:
        reasons.append(f"washed-out print (contrast {s['contrast']:.3f} < {REVIEW_CONTRAST_MIN})")
    if s["ink_ratio"] < REVIEW_INK_MIN:
        reasons.append(f"scant content (ink_ratio {s['ink_ratio']:.4f} < {REVIEW_INK_MIN})")
    elif s["sharpness"] < REVIEW_SHARP_MIN:
        reasons.append(
            f"soft text edges / mild blur (sharpness {s['sharpness']:.3f} < {REVIEW_SHARP_MIN})")
    if reasons:
        return VERDICT_REVIEW, reasons

    return VERDICT_PASS, []


def _analysis_grid(img: np.ndarray, np) -> np.ndarray:
    """Luma of (H, W, 3) resampled to the fixed analysis grid (pure NumPy
    bilinear, aspect preserved, max side ANALYSIS_MAX_SIDE)."""
    luma = img[..., 0] * 0.2126 + img[..., 1] * 0.7152 + img[..., 2] * 0.0722
    h, w = luma.shape
    scale = ANALYSIS_MAX_SIDE / max(h, w)
    out_h, out_w = max(round(h * scale), 16), max(round(w * scale), 16)
    if out_h == h and out_w == w:
        return luma
    yy = np.linspace(0.0, max(h - 1, 1), out_h)
    xx = np.linspace(0.0, max(w - 1, 1), out_w)
    y0 = np.minimum(np.floor(yy).astype(np.int64), h - 1)
    x0 = np.minimum(np.floor(xx).astype(np.int64), w - 1)
    y1 = np.minimum(y0 + 1, h - 1)
    x1 = np.minimum(x0 + 1, w - 1)
    wy = (yy - y0)[:, None]          # (out_h, 1)
    wx = (xx - x0)[None, :]          # (1, out_w)
    tl = luma[y0[:, None], x0[None, :]]
    tr = luma[y0[:, None], x1[None, :]]
    bl = luma[y1[:, None], x0[None, :]]
    br = luma[y1[:, None], x1[None, :]]
    # mixing advanced indices with a basic slice in between moves the
    # broadcast dims to the front (NumPy indexing rule) — so gather y/x in
    # the same bracket group, adjacent, never split by a slice
    return (tl * (1 - wy) * (1 - wx) + tr * (1 - wy) * wx
            + bl * wy * (1 - wx) + br * wy * wx)


def _grad_mag(luma: np.ndarray, np) -> np.ndarray:
    """Per-pixel gradient magnitude of the analysis-grid luma."""
    gx = np.abs(np.diff(luma, axis=1))  # (H, W-1)
    gy = np.abs(np.diff(luma, axis=0))  # (H-1, W)
    g = np.zeros_like(luma)
    g[:, 1:] += 0.5 * gx
    g[1:, :] += 0.5 * gy
    return g


def _dilate(mask: np.ndarray, np) -> np.ndarray:
    """1-px 4-connected dilation of a boolean mask."""
    m = mask.copy()
    for shift in (1, -1):
        m |= np.roll(mask, shift, axis=0)
        m |= np.roll(mask, shift, axis=1)
    return m


def _numpy():
    try:
        import numpy as np
        return np
    except ImportError as e:
        raise ImportError(
            "capture-quality gate needs its two optional deps: "
            "pip install numpy pillow (quality math is pure NumPy; Pillow "
            "is only used to decode image files)") from e


def _pillow():
    try:
        from PIL import Image
        return Image
    except ImportError as e:
        raise ImportError(
            "capture-quality gate needs Pillow to decode image files: "
            "pip install pillow") from e
