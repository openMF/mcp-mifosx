# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
import os

import pytest
from fastapi.testclient import TestClient

import app
import store


@pytest.fixture
def client():
    """API client on an emptied test database, configured with the LOS_DB_* variables.
    The database name must end in _test, so a real LOS database is never emptied."""
    if not os.environ.get("LOS_DB_NAME", "").endswith("_test"):
        pytest.skip("API tests need LOS_DB_HOST, LOS_DB_USER, LOS_DB_PASSWORD and LOS_DB_NAME ending in _test")
    with TestClient(app.app) as test_client:
        con = store.connect()
        try:
            con.run("TRUNCATE opportunity_events, opportunities")
        finally:
            con.close()
        yield test_client
