# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""Make the mifos-agent package (fineract_skill) importable without installing it."""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "mifos-agent"))
