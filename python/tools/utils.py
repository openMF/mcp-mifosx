# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import datetime

_ENGLISH_MONTHS = (
    "", "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December"
)

def get_fineract_today() -> str:
    """Returns today's date formatted as expected by Fineract (e.g., '14 March 2026')."""
    now = datetime.datetime.now()
    return f"{now.day:02d} {_ENGLISH_MONTHS[now.month]} {now.year}"
