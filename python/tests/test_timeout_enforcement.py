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

The scan works on tokens, not raw text: a ``timeout=`` written inside a
string, a comment or a nested helper call is not a keyword argument of
the ``requests`` call and must not be counted as one.

No test here touches the network.
"""

import io
import re
import tokenize
from pathlib import Path

from tools.mcp_adapter import DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT, FineractAdapter

_ROOT = Path(__file__).resolve().parents[1]
_ADAPTER = _ROOT / "tools" / "mcp_adapter.py"
_ENV_EXAMPLE = _ROOT / ".env.example"

# Every method the adapter uses to reach Fineract.
_HTTP_METHODS = ("get", "post", "put", "delete")


def _request_calls(source: str, method: str) -> list[list[tokenize.TokenInfo]]:
    """Return the argument tokens of each ``requests.<method>(...)`` call.

    The source is tokenized rather than scanned as text, so a ``)`` or a
    ``timeout=`` occurring inside a string or a comment is a STRING or
    COMMENT token: it cannot truncate the call, and it cannot masquerade
    as a keyword argument. Calls are found the same way, which also makes
    the scan independent of line wrapping and spacing.

    Parameters
    ----------
    source : str
        Full text of the adapter module, or a snippet of it.
    method : str
        HTTP method name, e.g. ``"get"``.

    Returns
    -------
    list[list[tokenize.TokenInfo]]
        One list per call, holding the tokens between the call's own
        parentheses with nested parentheses excluded. Empty when the
        method is not used.
    """
    if not source.endswith("\n"):
        source += "\n"
    tokens = list(tokenize.generate_tokens(io.StringIO(source).readline))
    calls = []
    for index, token in enumerate(tokens):
        if token.type != tokenize.NAME or token.string != "requests":
            continue
        head = tokens[index + 1 : index + 4]
        if len(head) < 3:
            continue
        if not (
            head[0].type == tokenize.OP
            and head[0].string == "."
            and head[1].type == tokenize.NAME
            and head[1].string == method
            and head[2].type == tokenize.OP
            and head[2].string == "("
        ):
            continue

        depth = 0
        arguments = []
        for call_token in tokens[index + 3 :]:
            if call_token.type == tokenize.OP and call_token.string == "(":
                depth += 1
                if depth > 1:
                    continue
            elif call_token.type == tokenize.OP and call_token.string == ")":
                depth -= 1
                if depth == 0:
                    break
                continue
            if depth == 1:
                arguments.append(call_token)
        calls.append(arguments)
    return calls


def _has_direct_timeout(arguments: list[tokenize.TokenInfo]) -> bool:
    """Whether a call's own arguments contain a ``timeout=`` keyword.

    Parameters
    ----------
    arguments : list[tokenize.TokenInfo]
        Tokens returned by ``_request_calls`` for one call.

    Returns
    -------
    bool
        True only for ``timeout=`` written at the call's top level, not
        inside a nested call, a string or a comment.
    """
    for index, token in enumerate(arguments[:-1]):
        if (
            token.type == tokenize.NAME
            and token.string == "timeout"
            and arguments[index + 1].type == tokenize.OP
            and arguments[index + 1].string == "="
        ):
            return True
    return False


def _offenders(source: str) -> list[str]:
    """Names of ``requests`` calls that pass no timeout of their own.

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
        for method in _HTTP_METHODS
        for arguments in _request_calls(source, method)
        if not _has_direct_timeout(arguments)
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

    Review flagged this after approval: raw text scanning let a string
    literal such as ``"timeout=5"`` satisfy the check. A comment could do
    the same, and an unbalanced parenthesis inside one could truncate the
    scan entirely. Tokens keep strings and comments in their own
    categories, so only a real keyword argument counts.
    """
    assert _offenders('requests.get(url, headers={"X": "timeout=5"})') == ["requests.get"]
    assert _offenders("requests.get(url, verify=v  # timeout= set on session\n)") == [
        "requests.get"
    ]
    assert _offenders("requests.get(url, verify=v  # build(timeout\n)") == ["requests.get"]
    assert _offenders("requests.get(url, timeout=5)  # build(timeout=5") == []


def test_the_timeout_scan_finds_all_four_methods() -> None:
    """The scan sees every method it guards, so it cannot pass by finding nothing."""
    source = _ADAPTER.read_text(encoding="utf-8")
    for method in _HTTP_METHODS:
        assert _request_calls(source, method), f"requests.{method} not found"


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
