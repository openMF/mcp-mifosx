# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
import os

import pytest
from fastapi.testclient import TestClient

import app
import store

INTAKE_TOKEN = "intake-test-token-0123456789abcdef"
ELIGIBILITY_TOKEN = "eligibility-test-token-0123456789abcdef"
IDENTITY_TOKEN = "identity-test-token-0123456789abcdef"
OFFICER_TOKEN = "officer-test-token-0123456789abcdef"


def bearer(token: str) -> dict:
    return {"Authorization": f"Bearer {token}"}


@pytest.fixture
def client(monkeypatch):
    """API client on an emptied test database, configured with the LOS_DB_* variables.
    The database name must end in _test, so a real LOS database is never emptied.
    It calls as an officer; pass `intake`, `eligibility` or `identity` as headers to call as that job."""
    if not os.environ.get("LOS_DB_NAME", "").endswith("_test"):
        pytest.skip("API tests need LOS_DB_HOST, LOS_DB_USER, LOS_DB_PASSWORD and LOS_DB_NAME ending in _test")
    monkeypatch.setenv("LOS_INTAKE_TOKEN", INTAKE_TOKEN)
    monkeypatch.setenv("LOS_ELIGIBILITY_TOKEN", ELIGIBILITY_TOKEN)
    monkeypatch.setenv("LOS_IDENTITY_TOKEN", IDENTITY_TOKEN)
    monkeypatch.setenv("LOS_OFFICER_TOKEN", OFFICER_TOKEN)
    with TestClient(app.app, headers=bearer(OFFICER_TOKEN)) as test_client:
        con = store.connect()
        try:
            con.run("TRUNCATE opportunity_documents, opportunity_events, opportunities")
        finally:
            con.close()
        yield test_client


@pytest.fixture
def intake():
    """Headers of a call by the intake job."""
    return bearer(INTAKE_TOKEN)


@pytest.fixture
def eligibility():
    """Headers of a call by the eligibility workflow."""
    return bearer(ELIGIBILITY_TOKEN)


@pytest.fixture
def identity():
    """Headers of a call by the identity workflow."""
    return bearer(IDENTITY_TOKEN)
