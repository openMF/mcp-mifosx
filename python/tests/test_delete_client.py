# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""delete_client used to let closed clients through, but Fineract only deletes pending ones (#660)."""

from typing import Any, Dict
from unittest.mock import patch

import pytest

from tools.domains.clients import delete_client
from tools.mcp_adapter import fineract_client


def _client(status: str) -> Dict[str, Any]:
    """GET clients/{id}, trimmed to what delete_client reads."""
    return {"id": 5, "status": {"id": 100, "code": f"clientStatusType.{status.lower()}", "value": status}}


def test_deletes_a_pending_client() -> None:
    with patch.object(fineract_client, "execute_get", return_value=_client("Pending")), patch.object(
        fineract_client, "execute_delete", return_value={"clientId": 5, "resourceId": 5}
    ) as delete:
        assert delete_client.func(5) == {"clientId": 5, "resourceId": 5}
    delete.assert_called_once_with("clients/5")


@pytest.mark.parametrize("status", ["Closed", "Active"])
def test_does_not_delete_a_client_that_is_not_pending(status: str) -> None:
    with patch.object(fineract_client, "execute_get", return_value=_client(status)), patch.object(
        fineract_client, "execute_delete"
    ) as delete:
        result = delete_client.func(5)
    delete.assert_not_called()
    assert "Only pending clients can be deleted" in result["error"]
