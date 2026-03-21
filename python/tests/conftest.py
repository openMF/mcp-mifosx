# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from unittest.mock import patch

import pytest


@pytest.fixture
def mock_fineract_client():
    """Provides a mocked FineractAdapter instance.

    Usage:
        def test_something(mock_fineract_client):
            mock_fineract_client.execute_get.return_value = {"id": 1}
            result = some_domain_function(1)
            assert result["id"] == 1
    """
    with patch("tools.mcp_adapter.fineract_client") as mock_client:
        yield mock_client
