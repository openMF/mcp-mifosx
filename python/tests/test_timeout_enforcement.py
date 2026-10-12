# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Guards that keep the Fineract request timeouts from regressing.

The adapter bounds every HTTP call so a silent backend cannot hang the
agent that called it (``MIFOSX_CONNECT_TIMEOUT_SECONDS`` /
``MIFOSX_READ_TIMEOUT_SECONDS``). CI used to suppress bandit's
``B113: request_without_timeout``, so the guarantee held only as long as
nobody added a call site. These tests make it structural: a new HTTP
method or a new environment variable cannot land without its timeout and
its documentation.

No test here touches the network.
"""

import re
from pathlib import Path

from tools.mcp_adapter import DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, FineractAdapter

_ROOT = Path(__file__).resolve().parents[1]
_ADAPTER = _ROOT / "tools" / "mcp_adapter.py"
_ENV_EXAMPLE = _ROOT / ".env.example"

# Every method the adapter uses to reach Fineract.
_HTTP_METHODS = ("get", "post", "put", "delete")


def _call_sites(source: str, method: str) -> list[str]:
    """Return the source of each ``requests.<method>(...)`` call.

    Parentheses are matched by counting, so the result does not depend on
    how a call is wrapped across lines.

    Parameters
    ----------
    source : str
        Full text of the adapter module.
    method : str
        HTTP method name, e.g. ``"get"``.

    Returns
    -------
    list[str]
        One entry per call, from ``requests.<method>(`` to its closing
        parenthesis. Empty when the method is not used.
    """
    sites = []
    for match in re.finditer(rf"requests\.{method}\(", source):
        depth = 0
        for index in range(match.end() - 1, len(source)):
            if source[index] == "(":
                depth += 1
            elif source[index] == ")":
                depth -= 1
                if depth == 0:
                    sites.append(source[match.start() : index + 1])
                    break
    return sites


def test_every_request_call_site_sets_a_timeout() -> None:
    """Each ``requests.<verb>`` call in the adapter passes ``timeout=``.

    This is the invariant bandit's B113 encodes, checked against the
    source so a regression is caught even if the linter is skipped again.
    """
    source = _ADAPTER.read_text(encoding="utf-8")
    offenders = [
        f"requests.{method}"
        for method in _HTTP_METHODS
        for site in _call_sites(source, method)
        if "timeout=" not in site
    ]
    assert offenders == [], f"requests calls without timeout: {offenders}"


def test_the_timeout_scan_finds_all_four_methods() -> None:
    """The scan sees every method it guards, so it cannot pass by finding nothing."""
    source = _ADAPTER.read_text(encoding="utf-8")
    for method in _HTTP_METHODS:
        assert _call_sites(source, method), f"requests.{method} not found"


def test_every_environment_variable_is_documented() -> None:
    """Every ``MIFOSX_*`` variable the adapter reads appears in .env.example.

    ``.env.example`` is the file a new deployment copies, so an
    undocumented variable is one nobody knows they can set.
    """
    source = _ADAPTER.read_text(encoding="utf-8")
    read = set(re.findall(r"""["'](MIFOSX_[A-Z_]+)["']""", source))
    assert read, "expected the adapter to read MIFOSX_* variables"

    documented = _ENV_EXAMPLE.read_text(encoding="utf-8")
    missing = sorted(name for name in read if name not in documented)
    assert missing == [], f"undocumented in .env.example: {missing}"


def test_unset_variables_fall_back_to_the_defaults(monkeypatch) -> None:
    """With no override, the adapter uses the documented default timeouts."""
    monkeypatch.delenv("MIFOSX_CONNECT_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("MIFOSX_READ_TIMEOUT_SECONDS", raising=False)
    adapter = FineractAdapter()
    assert adapter.timeout == (DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT)
