# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Cross-language conformance: Python reproduces the shared capture-gate vectors.

`contract/capture/golden_vectors.json` is the integration surface every MCP
server's capture gate is tested against. If the Python thresholds or signal
math drift, this test fails before the Go/Java/Rust ports silently disagree.
"""

from __future__ import annotations

import base64
import json
from pathlib import Path

import pytest

from tools import capture_gate as cg
from tools.capture_gate_vectors import CaptureGateVector, CaptureGateVectors

CONTRACT = Path(__file__).resolve().parents[2] / "contract" / "capture" / "golden_vectors.json"
VECTORS: CaptureGateVectors = json.loads(CONTRACT.read_text(encoding="utf-8"))


def test_contract_pins_the_python_thresholds() -> None:
    """The contract's declared thresholds must match the Python constants."""
    thresholds = VECTORS["thresholds"]
    assert thresholds["retake"] == {
        "brightness": cg.RETAKE_BRIGHT_MIN,
        "ink_ratio": cg.RETAKE_INK_MIN,
        "sharpness": cg.RETAKE_SHARP_MIN,
    }
    assert thresholds["review"] == {
        "brightness": cg.REVIEW_BRIGHT_MIN,
        "contrast": cg.REVIEW_CONTRAST_MIN,
        "ink_ratio": cg.REVIEW_INK_MIN,
        "sharpness": cg.REVIEW_SHARP_MIN,
    }
    assert VECTORS["analysis_max_side"] == cg.ANALYSIS_MAX_SIDE


def test_contract_covers_every_verdict_class() -> None:
    """A fixture set without all three verdicts cannot catch a regression."""
    verdicts = {case["expected"]["verdict"] for case in VECTORS["cases"]}
    assert verdicts == {"RETAKE", "REVIEW", "PASS"}


@pytest.mark.parametrize("case", VECTORS["cases"], ids=lambda c: c["name"])
def test_python_matches_shared_golden_vector(case: CaptureGateVector) -> None:
    """Python must reproduce each fixture's verdict, reasons and signals."""
    image_bytes = base64.b64decode(case["image_base64"])
    gate = cg.assess_image(image_bytes, filename=f"{case['name']}.png")
    expected = case["expected"]

    assert gate["verdict"] == expected["verdict"]
    assert gate["reasons"] == expected["reasons"]
    tolerance = VECTORS["signal_tolerance"]
    for name, expected_value in expected["signals"].items():
        assert gate["signals"][name] == pytest.approx(expected_value, abs=tolerance), name
