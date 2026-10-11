# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import socket
from threading import Event, Thread
from unittest.mock import Mock, patch

import pytest

from tools.mcp_adapter import FineractAdapter


@pytest.fixture
def adapter(monkeypatch):
    monkeypatch.delenv("MIFOSX_CONNECT_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("MIFOSX_READ_TIMEOUT_SECONDS", raising=False)
    result = FineractAdapter()
    result.username = "test-user"
    result.password = "test-password"
    return result


@pytest.mark.parametrize("method", ["get", "post", "put", "delete"])
def test_requests_receive_connect_and_read_timeouts(adapter, method):
    response = Mock()
    response.json.return_value = {"resourceId": 1}
    args = ("clients", {}) if method in ("post", "put") else ("clients",)
    with patch(f"tools.mcp_adapter.requests.{method}", return_value=response) as request:
        assert getattr(adapter, f"execute_{method}")(*args) == {"resourceId": 1}
    assert request.call_args.kwargs["timeout"] == (10, 60)
    request.assert_called_once()


def test_timeout_configuration(monkeypatch):
    monkeypatch.setenv("MIFOSX_CONNECT_TIMEOUT_SECONDS", "0.25")
    monkeypatch.setenv("MIFOSX_READ_TIMEOUT_SECONDS", "120")
    assert FineractAdapter().timeout == (0.25, 120)


@pytest.mark.parametrize("name", ["MIFOSX_CONNECT_TIMEOUT_SECONDS", "MIFOSX_READ_TIMEOUT_SECONDS"])
@pytest.mark.parametrize("value", ["0", "-1", "nan", "inf", "-inf", "invalid"])
def test_invalid_timeout_rejected(adapter, monkeypatch, name, value):
    monkeypatch.setenv(name, value)
    with pytest.raises(ValueError):
        FineractAdapter()


@pytest.mark.parametrize("method", ["get", "post", "put", "delete"])
def test_unresponsive_backend_returns_error_without_retry(adapter, method):
    # Accept an actual HTTP request, but never send a response. This exercises
    # Requests' read timeout, rather than mocking a Timeout exception.
    release = Event()
    requests_received = []
    with socket.socket() as listener:
        listener.bind(("127.0.0.1", 0))
        listener.listen(1)
        listener.settimeout(2)

        def serve():
            connection, _ = listener.accept()
            with connection:
                requests_received.append(connection.recv(4096))
                release.wait(2)

        thread = Thread(target=serve, daemon=True)
        thread.start()
        adapter.base_url = f"http://127.0.0.1:{listener.getsockname()[1]}"
        adapter.timeout = (0.5, 0.05)
        args = ("clients", {}) if method in ("post", "put") else ("clients",)
        try:
            result = getattr(adapter, f"execute_{method}")(*args)
            assert "error" in result
            assert "Read timed out" in result["error"]
            assert len(requests_received) == 1
            assert requests_received[0].startswith(method.upper().encode() + b" ")
        finally:
            release.set()
            thread.join(3)
        assert not thread.is_alive()
