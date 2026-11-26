# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Domain tools must log through ``logging`` (stderr), never ``print`` (stdout).

The server's default MCP transport is stdio: stdout is the JSON-RPC channel the
client parses, so a stray ``print`` from a tool corrupts the protocol stream.
The log messages themselves must also stay free of personal and financial data
(client names, search queries, amounts) — the adapter already keeps payload
values out of the logs, and the domain modules must not undo that."""

import ast
import logging
from pathlib import Path
from unittest.mock import patch

import pytest

from tools.domains import clients, savings
from tools.mcp_adapter import fineract_client

DOMAINS_DIR = Path(__file__).parent.parent / "tools" / "domains"


def test_domain_modules_never_print() -> None:
    """A print() in a domain module would corrupt the stdio JSON-RPC stream."""
    offenders = []
    for module in sorted(DOMAINS_DIR.glob("*.py")):
        tree = ast.parse(module.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.Call) and isinstance(node.func, ast.Name) and node.func.id == "print":
                offenders.append(f"{module.name}:{node.lineno}")
    assert offenders == []


def test_tool_logging_stays_off_stdout(capsys: pytest.CaptureFixture, caplog: pytest.LogCaptureFixture) -> None:
    with patch.object(fineract_client, "execute_get", return_value={"id": 1}) as mocked:
        with caplog.at_level(logging.INFO):
            assert clients.get_client_details.func(client_id=1) == {"id": 1}
    mocked.assert_called_once_with("clients/1")

    # stdout is the stdio JSON-RPC channel: nothing but the protocol may appear there.
    assert capsys.readouterr().out == ""
    assert "Fetching details for Client #1" in caplog.text


def test_sensitive_values_stay_out_of_the_logs(caplog: pytest.LogCaptureFixture) -> None:
    name = "Asha Kiran"
    amount = 15000.0

    with patch.object(fineract_client, "execute_get", return_value=[]):
        with caplog.at_level(logging.INFO):
            clients.search_clients_by_name.func(name_query=name)
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 1}):
        with caplog.at_level(logging.INFO):
            clients.create_client.func(firstname="Asha", lastname="Kiran", office_id=1)
    with patch.object(fineract_client, "execute_post", return_value={"resourceId": 2}):
        with caplog.at_level(logging.INFO):
            savings.deposit_savings.func(account_id=9, amount=amount, payment_type_id=1)

    logged = caplog.text
    for value in ("Asha", "Kiran", "15000"):
        assert value not in logged
    assert "Searching for clients by name" in logged
    assert "Creating a new client" in logged
    assert "Depositing into Savings Account #9" in logged
