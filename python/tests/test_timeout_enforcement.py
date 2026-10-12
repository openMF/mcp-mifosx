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

The scan parses the source instead of matching text: only a call's own
keyword arguments count, so a ``timeout=`` written inside a string, a
comment, a nested helper, a dict, a list or a lambda default can never
be mistaken for the request's timeout.

No test here touches the network.
"""

import ast
import re
from pathlib import Path

from tools.mcp_adapter import DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, FineractAdapter

_ROOT = Path(__file__).resolve().parents[1]
_ADAPTER = _ROOT / "tools" / "mcp_adapter.py"
_ENV_EXAMPLE = _ROOT / ".env.example"

# Every method the adapter uses to reach Fineract.
_HTTP_METHODS = ("get", "post", "put", "delete")


def _request_calls(source: str) -> list[tuple[str, ast.Call]]:
    """Return every ``requests.<method>(...)`` call in the source.

    The source is parsed instead of scanned as text. By construction a
    ``timeout=`` inside a string, a comment, a nested call, a dict, a
    list or a lambda default is not one of the call's own keyword
    arguments, a ``)`` in a string cannot truncate the call, and line
    wrapping or spacing cannot hide one.

    Parameters
    ----------
    source : str
        Full text of the adapter module, or a snippet of it.

    Returns
    -------
    list[tuple[str, ast.Call]]
        ``(method, call)`` pairs in source order.
    """
    tree = ast.parse(source)
    calls = []
    for node in ast.walk(tree):
        if not isinstance(node, ast.Call):
            continue
        func = node.func
        if not isinstance(func, ast.Attribute) or not isinstance(func.value, ast.Name):
            continue
        if func.value.id == "requests" and func.attr in _HTTP_METHODS:
            calls.append((func.attr, node))
    calls.sort(key=lambda item: item[1].lineno)
    return calls


def _offenders(source: str) -> list[str]:
    """Names of ``requests`` calls that pass no timeout of their own.

    Only the call's own keyword arguments are consulted
    (``ast.Call.keywords``), so ``timeout=`` nested anywhere else never
    satisfies the check. A ``**kwargs`` unpacking is deliberately not
    counted either: the guard should fail loudly rather than assume what
    was unpacked.

    Parameters
    ----------
    source : str
        Full text of the adapter module (or a snippet of it).

    Returns
    -------
    list[str]
        One entry per call missing a direct ``timeout=`` argument.
    """
    return [
        f"requests.{method}"
        for method, call in _request_calls(source)
        if not any(keyword.arg == "timeout" for keyword in call.keywords)
    ]


def test_every_request_call_site_sets_a_timeout() -> None:
    """Each ``requests.<verb>`` call in the adapter passes ``timeout=``.

    This is the invariant bandit's B113 encodes, checked against the
    source so a regression is caught even if the linter is skipped again.
    """
    source = _ADAPTER.read_text(encoding="utf-8")
    assert _offenders(source) == [], f"requests calls without timeout: {_offenders(source)}"


def test_a_timeout_on_a_nested_call_does_not_count() -> None:
    """``timeout=`` inside a helper's arguments is not a request timeout.

    Pins the check against a false pass: only ``timeout=`` written on the
    ``requests`` call itself satisfies it.
    """
    assert _offenders("requests.get(url, headers=build(timeout=5))") == ["requests.get"]
    assert _offenders("requests.get(url, verify=self.v, timeout=self.timeout)") == []


def test_a_timeout_in_a_string_or_comment_does_not_count() -> None:
    """``timeout=`` text inside a string or a comment is not a keyword.

    Review flagged this: raw text scanning let a string literal such as
    ``"timeout=5"`` satisfy the check. A comment could do the same, and
    an unbalanced parenthesis inside one could truncate the scan.
    """
    assert _offenders('requests.get(url, headers={"X": "timeout=5"})') == ["requests.get"]
    assert _offenders("requests.get(url, verify=v  # timeout= set on session\n)") == [
        "requests.get"
    ]
    assert _offenders("requests.get(url, verify=v  # build(timeout\n)") == ["requests.get"]
    assert _offenders("requests.get(url, timeout=5)  # build(timeout=5") == []


def test_a_timeout_inside_brackets_or_a_lambda_does_not_count() -> None:
    """``timeout=`` in a dict, a list or a lambda default is not a keyword.

    Review flagged this after the token scan: it tracked parentheses but
    left ``{}`` and ``[]`` at call depth, so a lambda parameter default
    inside a hook dict could pass a call that has no timeout at all.
    """
    assert _offenders('requests.get(url, hooks={"response": lambda r, timeout=5: r})') == [
        "requests.get"
    ]
    assert _offenders("requests.get(url, params=[lambda timeout=5: 5])") == ["requests.get"]
    assert _offenders("requests.get(url, timeout=5, hooks={})") == []


def test_the_timeout_scan_finds_all_four_methods() -> None:
    """The scan sees every method it guards, so it cannot pass by finding nothing."""
    source = _ADAPTER.read_text(encoding="utf-8")
    found = {method for method, _ in _request_calls(source)}
    assert found == set(_HTTP_METHODS), f"expected all of {_HTTP_METHODS}, found {sorted(found)}"


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
