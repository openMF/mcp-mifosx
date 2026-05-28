# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import datetime

def get_fineract_today() -> str:
    """Returns today's date formatted as expected by Fineract (e.g., '14 March 2026')."""
    return datetime.datetime.now().strftime("%d %B %Y")
