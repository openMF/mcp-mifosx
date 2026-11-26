# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Regenerate contract/capture/golden_vectors.json from the Python gate.

The capture gate is a cross-language contract: Python, Go (and later Java /
Rust) must return the same verdict for the same capture. The fixtures below
are synthetic so they carry no client data, and PNG-encoded so both language
runtimes decode identical pixels (JPEG chroma upsampling differs per decoder).

Run from the `python/` directory:

    python scripts/generate_capture_gate_vectors.py

The output is committed; every server's test suite iterates over it instead of
maintaining its own copy of the thresholds.
"""

from __future__ import annotations

import base64
import io
import json
import sys
from pathlib import Path

import numpy as np
from numpy.typing import NDArray
from PIL import Image, ImageDraw

# Running a script from `scripts/` puts that directory on sys.path, not the
# package root, so import the gate and its vector types from `python/` explicitly.
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import tools.capture_gate as cg  # noqa: E402
from tools.capture_gate_vectors import CaptureGateVector, CaptureGateVectors  # noqa: E402

CONTRACT_PATH = Path(__file__).resolve().parents[2] / "contract" / "capture" / "golden_vectors.json"


def _png(image: Image.Image) -> str:
    """Encode a Pillow image as base64 PNG for a JSON fixture."""
    buf = io.BytesIO()
    image.save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode("ascii")


def _crisp_page(width: int = 512, height: int = 384, stroke: int = 1) -> Image.Image:
    """Dark text-like strokes on white paper: readable by construction."""
    im = Image.new("L", (width, height), 245)
    draw = ImageDraw.Draw(im)
    for i, y in enumerate(range(height // 6, height - 40, 24 * stroke)):
        draw.line([(40, y), (100 + (i * 7) % (120 * stroke), y)], fill=20, width=stroke)
        draw.line(
            [(200 + (i * 5) % (250 * stroke), y + 6 * stroke), (width - 90, y + 6 * stroke)],
            fill=40,
            width=stroke * 2,
        )
    return im


def _resample(im: Image.Image, size: tuple[int, int], target: tuple[int, int]) -> Image.Image:
    """Bilinear down/up round-trip: the synthetic equivalent of camera blur."""
    return im.resize(size, Image.BILINEAR).resize(target, Image.BILINEAR)


def _scale(im: Image.Image, factor: float) -> Image.Image:
    """Multiply luma, then re-quantise to 8 bits (underexposure)."""
    arr: NDArray[np.float64] = np.asarray(im, dtype=np.float64) * factor
    return Image.fromarray(np.clip(arr, 0, 255).astype("uint8"))


def _cases() -> list[tuple[str, str, Image.Image]]:
    """(name, description, image) fixtures covering every verdict class."""
    crisp = _crisp_page()
    large = _crisp_page(1024, 768, stroke=2)
    return [
        ("crisp_text_page", "Readable page at the reference 512x384 size.", crisp),
        ("large_crisp_page", "Same page at 1024x768; the 512px grid makes it verdict-identical.", large),
        ("mildly_blurred_page", "Bilinear 512->384->512 round-trip: soft but human-readable edges.", _resample(crisp, (384, 288), (512, 384))),
        ("heavily_blurred_page", "Bilinear 512->64->512 round-trip: text edges destroyed.", _resample(crisp, (64, 48), (512, 384))),
        ("blank_page", "Uniform luma: no ink at all.", Image.new("L", (512, 384), 244)),
        ("dark_capture", "Crisp page at 5% exposure: too dark to read.", _scale(crisp, 0.05)),
        ("low_exposure_capture", "Crisp page at 20% exposure: ink collapses below the paper estimate.", _scale(crisp, 0.20)),
        ("washed_out_page", "Crisp page blended 60% toward paper grey: faint print.", Image.blend(crisp, Image.new("L", crisp.size, 245), 0.60)),
        ("scant_content", "A few short strokes on a large page: almost no ink.", _scant_page()),
    ]


def _scant_page() -> Image.Image:
    """A page whose ink ratio sits in the REVIEW 'scant content' band."""
    im = Image.new("L", (1024, 768), 245)
    draw = ImageDraw.Draw(im)
    for y in range(120, 300, 30):
        draw.line([(40, y), (200, y)], fill=20, width=2)
    return im


def main() -> None:
    """Assess every fixture and write the shared contract file."""
    fixtures: list[CaptureGateVector] = []
    for name, description, image in _cases():
        encoded = _png(image)
        gate = cg.assess_image(base64.b64decode(encoded), filename=f"{name}.png")
        fixtures.append(
            {
                "name": name,
                "description": description,
                "media_type": "image/png",
                "image_base64": encoded,
                "expected": {
                    "verdict": gate["verdict"],
                    "reasons": gate["reasons"],
                    "signals": gate["signals"],
                },
            }
        )
        print(f"{name:24s} {gate['verdict']:7s} {gate['signals']}")

    document: CaptureGateVectors = {
        "about": (
            "Shared golden vectors for the deterministic document capture-quality gate. "
            "Every MCP server (Python, Go, Java, Rust) must reproduce the expected verdict "
            "and the five signals for each PNG fixture within the stated tolerance; the "
            "verdict is compared exactly. Vectors are generated by "
            "python/scripts/generate_capture_gate_vectors.py."
        ),
        "contract": "RETAKE | REVIEW | PASS",
        "signal_tolerance": 5e-4,
        "analysis_max_side": cg.ANALYSIS_MAX_SIDE,
        "thresholds": {
            "retake": {
                "brightness": cg.RETAKE_BRIGHT_MIN,
                "ink_ratio": cg.RETAKE_INK_MIN,
                "sharpness": cg.RETAKE_SHARP_MIN,
            },
            "review": {
                "brightness": cg.REVIEW_BRIGHT_MIN,
                "contrast": cg.REVIEW_CONTRAST_MIN,
                "ink_ratio": cg.REVIEW_INK_MIN,
                "sharpness": cg.REVIEW_SHARP_MIN,
            },
        },
        "cases": fixtures,
    }
    CONTRACT_PATH.parent.mkdir(parents=True, exist_ok=True)
    CONTRACT_PATH.write_text(json.dumps(document, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {CONTRACT_PATH}")


if __name__ == "__main__":
    main()
