# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Typed shapes for the shared capture-gate golden vectors.

Both the generator that writes ``contract/capture/golden_vectors.json`` and the
tests that read it use these types, so a change to the nested signal fields is
checked in one place instead of drifting between the two scripts.
"""

from __future__ import annotations

from typing import TypedDict


class CaptureGateSignals(TypedDict):
    """The five stable signal names of the capture gate."""

    sharpness: float
    contrast: float
    ink_ratio: float
    brightness: float
    exposure: float


class CaptureGateExpectation(TypedDict):
    """The expected result for one golden vector."""

    verdict: str
    reasons: list[str]
    signals: CaptureGateSignals


class CaptureGateVector(TypedDict):
    """One PNG fixture and the verdict/signals every server must reproduce."""

    name: str
    description: str
    media_type: str
    image_base64: str
    expected: CaptureGateExpectation


class CaptureGateVectors(TypedDict):
    """Top-level shape of the contract file."""

    about: str
    contract: str
    signal_tolerance: float
    analysis_max_side: int
    thresholds: dict[str, dict[str, float]]
    cases: list[CaptureGateVector]
