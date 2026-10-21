# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
Tests for per-agent tool profiles.

The allowlist is checked through a real MCP client: a tool outside the
profile must be neither listed nor callable. Every misconfiguration must
stop the server rather than expose more tools. The shipped profiles.toml is
checked against the tools mcp_server.py registers, and the read-only profile
against the HTTP methods its tools actually use. No network access.
"""

import ast
import asyncio
from pathlib import Path
from typing import Any, Dict, FrozenSet, Optional, Set

import pytest
from fastmcp import Client, FastMCP
from fastmcp.exceptions import ToolError

import mcp_server
from core.tool_profiles import (
    DEFAULT_PROFILES_FILE,
    ProfileError,
    apply_tool_profile,
    check_profile_tools,
    load_profiles,
    profile_check_lifespan,
    registered_tool_names,
)

PYTHON_DIR = Path(__file__).resolve().parent.parent

PROFILES = """
[profiles.read-only]
tools = ["get_thing"]

[profiles.writer]
tools = ["get_thing", "update_thing"]
"""


def _server(**kwargs: Any) -> FastMCP:
    mcp = FastMCP("profiles-test", **kwargs)

    @mcp.tool()
    def get_thing() -> dict:
        return {"thing": 1}

    @mcp.tool()
    def update_thing() -> dict:
        return {"updated": True}

    @mcp.tool()
    def delete_thing() -> dict:
        return {"deleted": True}

    return mcp


def _env(tmp_path: Path, profile: Optional[str] = None, text: str = PROFILES) -> Dict[str, str]:
    path = tmp_path / "profiles.toml"
    path.write_text(text, encoding="utf-8")
    env = {"MCP_PROFILES_FILE": str(path)}
    if profile is not None:
        env["MCP_AGENT_PROFILE"] = profile
    return env


def _listed(mcp: FastMCP) -> Set[str]:
    async def run() -> Set[str]:
        async with Client(mcp) as client:
            return {tool.name for tool in await client.list_tools()}
    return asyncio.run(run())


def _call(mcp: FastMCP, name: str) -> Any:
    async def run() -> Any:
        async with Client(mcp) as client:
            return (await client.call_tool(name, {})).data
    return asyncio.run(run())


# ── enforcement ──────────────────────────────────────────────────────

def test_no_profile_exposes_read_only_tools(tmp_path: Path) -> None:
    mcp = _server()
    profile = apply_tool_profile(mcp, _env(tmp_path))

    assert profile.name == "read-only" and profile.defaulted
    assert _listed(mcp) == {"get_thing"}


def test_blank_profile_counts_as_unset(tmp_path: Path) -> None:
    mcp = _server()
    assert apply_tool_profile(mcp, _env(tmp_path, "  ")).name == "read-only"


def test_named_profile_exposes_exactly_its_tools(tmp_path: Path) -> None:
    mcp = _server()
    profile = apply_tool_profile(mcp, _env(tmp_path, "writer"))

    assert not profile.defaulted
    assert _listed(mcp) == {"get_thing", "update_thing"}
    assert _call(mcp, "update_thing") == {"updated": True}


def test_hidden_tool_cannot_be_called_by_name(tmp_path: Path) -> None:
    """Hiding from the list is not enough: the call itself must fail."""
    mcp = _server()
    apply_tool_profile(mcp, _env(tmp_path, "writer"))

    with pytest.raises(ToolError, match="delete_thing"):
        _call(mcp, "delete_thing")


def test_tool_registered_after_the_profile_stays_hidden(tmp_path: Path) -> None:
    mcp = _server()
    apply_tool_profile(mcp, _env(tmp_path, "writer"))

    @mcp.tool()
    def wire_funds() -> dict:
        return {"sent": True}

    assert "wire_funds" not in _listed(mcp)
    with pytest.raises(ToolError):
        _call(mcp, "wire_funds")


def test_all_profile_keeps_every_tool(tmp_path: Path) -> None:
    mcp = _server()
    profile = apply_tool_profile(mcp, _env(tmp_path, "all"))

    assert profile.tools is None
    assert _listed(mcp) == {"get_thing", "update_thing", "delete_thing"}


# ── misconfiguration stops the server ────────────────────────────────

def test_unknown_profile_is_rejected_and_lists_the_choices(tmp_path: Path) -> None:
    mcp = _server()
    with pytest.raises(ProfileError, match="Unknown MCP_AGENT_PROFILE 'writter'.*all, read-only, writer"):
        apply_tool_profile(mcp, _env(tmp_path, "writter"))
    # Nothing was hidden or shown: the caller is expected not to start.
    assert _listed(mcp) == {"get_thing", "update_thing", "delete_thing"}


@pytest.mark.parametrize(
    ("text", "error"),
    [
        ("[profiles.read-only\n", "not valid TOML"),
        ("title = 'no profiles'\n", "no \\[profiles"),
        ("[profiles.read-only]\ntools = []\n", "non-empty 'tools' list"),
        ("[profiles.read-only]\ntools = [1, 2]\n", "non-empty 'tools' list"),
        ("[profiles.writer]\ntools = ['get_thing']\n", "default profile 'read-only' is missing"),
        ("[profiles.read-only]\ntools = ['get_thing']\n[profiles.all]\ntools = ['get_thing']\n", "built in"),
    ],
)
def test_unusable_profiles_file_is_rejected(tmp_path: Path, text: str, error: str) -> None:
    with pytest.raises(ProfileError, match=error):
        apply_tool_profile(_server(), _env(tmp_path, text=text))


def test_missing_profiles_file_is_rejected(tmp_path: Path) -> None:
    env = {"MCP_PROFILES_FILE": str(tmp_path / "absent.toml")}
    with pytest.raises(ProfileError, match="not found"):
        apply_tool_profile(_server(), env)


def test_profile_naming_an_unknown_tool_is_rejected() -> None:
    profiles = {"read-only": frozenset({"get_thing"}), "writer": frozenset({"get_thing", "updat_thing"})}
    with pytest.raises(ProfileError, match="writer: updat_thing"):
        check_profile_tools(profiles, frozenset({"get_thing", "update_thing"}))


def test_server_with_an_unknown_tool_in_a_profile_refuses_to_serve(tmp_path: Path) -> None:
    """The lifespan check runs however the server is started, not only from __main__."""
    env = _env(tmp_path, text=PROFILES.replace('"update_thing"', '"updat_thing"'))
    mcp = _server(lifespan=profile_check_lifespan(env))
    apply_tool_profile(mcp, env)

    with pytest.raises(RuntimeError, match="writer: updat_thing"):
        _listed(mcp)


# ── the shipped profiles.toml ────────────────────────────────────────

def test_server_applies_the_selected_profile() -> None:
    """Connecting also runs the server's lifespan check on the shipped profiles."""
    exposed = _listed(mcp_server.mcp)
    if mcp_server.TOOL_PROFILE.tools is None:
        pytest.skip("MCP_AGENT_PROFILE=all in this environment")
    assert exposed == mcp_server.TOOL_PROFILE.tools


def test_shipped_profiles_name_only_registered_tools() -> None:
    registered = asyncio.run(registered_tool_names(mcp_server.mcp))
    check_profile_tools(load_profiles(DEFAULT_PROFILES_FILE), registered)


def _fineract_methods_by_tool() -> Dict[str, Set[str]]:
    """HTTP methods each MCP tool can reach through the domain layer.

    Follows calls from the tool body (and module helpers it uses) into
    tools/domains, where requests go through fineract_client.execute_<method>.
    """
    def called(node: ast.AST) -> Set[str]:
        names: Set[str] = set()
        for call in ast.walk(node):
            if not isinstance(call, ast.Call):
                continue
            func = call.func
            if isinstance(func, ast.Name):
                names.add(func.id)
            elif isinstance(func, ast.Attribute):
                names.add(func.attr)
                if isinstance(func.value, ast.Name):  # domain_fn.func(...)
                    names.add(func.value.id)
        return names

    domain: Dict[str, Set[str]] = {}
    for path in (PYTHON_DIR / "tools" / "domains").glob("*.py"):
        for node in ast.parse(path.read_text(encoding="utf-8")).body:
            if isinstance(node, ast.FunctionDef):
                domain[node.name] = called(node)

    def methods(name: str, seen: FrozenSet[str] = frozenset()) -> Set[str]:
        if name not in domain or name in seen:
            return set()
        own = {c.removeprefix("execute_").upper() for c in domain[name] if c.startswith("execute_")}
        return own.union(*(methods(c, seen | {name}) for c in domain[name]))

    server = ast.parse((PYTHON_DIR / "mcp_server.py").read_text(encoding="utf-8"))
    aliases = {a.asname or a.name: a.name for n in server.body if isinstance(n, ast.ImportFrom) for a in n.names}
    helpers = {n.name: n for n in server.body if isinstance(n, ast.FunctionDef)}
    result: Dict[str, Set[str]] = {}
    for node in server.body:
        is_tool = isinstance(node, ast.FunctionDef) and any(
            isinstance(d, ast.Call) and getattr(d.func, "attr", "") == "tool" for d in node.decorator_list
        )
        if is_tool:
            names = called(node)
            names |= set().union(*(called(helpers[h]) for h in names if h in helpers and h != node.name))
            result[node.name] = set().union(*(methods(aliases.get(n, n)) for n in names))
    return result


def test_read_only_profile_only_reads() -> None:
    """Every tool in the default profile must reach Fineract with GET only."""
    by_tool = _fineract_methods_by_tool()
    read_only = load_profiles(DEFAULT_PROFILES_FILE)["read-only"]

    unresolved = sorted(t for t in read_only if not by_tool.get(t))
    writes = {t: sorted(by_tool[t] - {"GET"}) for t in read_only if by_tool.get(t, set()) - {"GET"}}
    assert not unresolved, f"Could not trace any Fineract call for: {unresolved}"
    assert not writes, f"read-only profile contains tools that write: {writes}"


def test_every_get_only_tool_is_in_the_read_only_profile() -> None:
    """A new read-only tool should be offered by default, not forgotten."""
    get_only = {t for t, m in _fineract_methods_by_tool().items() if m == {"GET"}}
    assert get_only == set(load_profiles(DEFAULT_PROFILES_FILE)["read-only"])
