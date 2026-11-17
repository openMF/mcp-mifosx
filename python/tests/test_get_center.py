# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""get_center asked Fineract for a 'collectionSheet' association, which the centers endpoint
doesn't support. Fineract ignored it, so the center's meeting calendar never came back.
The supported association is 'collectionMeetingCalendar'."""

from unittest.mock import patch

import mcp_server
from tools.mcp_adapter import fineract_client

CENTER = {
    "id": 5,
    "name": "Main Center",
    "groupMembers": [{"id": 12, "name": "Alpha Group"}],
    "collectionMeetingCalendar": {"id": 3, "title": "Weekly meeting"},
}


def test_get_center_asks_for_the_meeting_calendar() -> None:
    fn = getattr(mcp_server.get_center, "fn", mcp_server.get_center)
    with patch.object(fineract_client, "execute_get", return_value=CENTER) as get:
        result = fn(centerId=5)
    get.assert_called_once_with("centers/5?associations=groupMembers,collectionMeetingCalendar")
    assert result["collectionMeetingCalendar"] == {"id": 3, "title": "Weekly meeting"}
