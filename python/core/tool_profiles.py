# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Per-agent tool profiles for the MCP server.

``MCP_AGENT_PROFILE`` names a profile in ``profiles.toml``. The server hides
every tool and re-enables only that profile's tools, so FastMCP itself
enforces the allowlist: a hidden tool is neither listed nor callable, and a
tool registered after the profile is applied stays hidden.

Every way of getting the configuration wrong stops the server instead of
widening access: no profile means read-only tools, and an unknown profile,
an unreadable file or a tool name the server does not have is an error.
"""

import os
import tomllib
from contextlib import AbstractAsyncContextManager, asynccontextmanager
from pathlib import Path
from typing import Any, AsyncIterator, Callable, Dict, FrozenSet, Mapping, NamedTuple, Optional, Union

from fastmcp import FastMCP

PROFILE_ENV = "MCP_AGENT_PROFILE"
PROFILES_FILE_ENV = "MCP_PROFILES_FILE"
DEFAULT_PROFILE = "read-only"
# Built-in profile that keeps every tool, for deployments that want the
# behaviour from before profiles existed. Not listed in profiles.toml.
ALL_TOOLS = "all"
DEFAULT_PROFILES_FILE = Path(__file__).resolve().parent.parent / "profiles.toml"


class ProfileError(Exception):
    """The tool profile configuration is unusable; the server must not start."""


class ToolProfile(NamedTuple):
    """The profile in force: its name, its tools (None for ``all``), and
    whether it was chosen because ``MCP_AGENT_PROFILE`` was unset."""

    name: str
    tools: Optional[FrozenSet[str]]
    defaulted: bool


def load_profiles(path: Union[str, os.PathLike]) -> Dict[str, FrozenSet[str]]:
    """Read ``[profiles.<name>]`` tables from a TOML file.

    Parameters
    ----------
    path : str or os.PathLike
        The profiles file.

    Returns
    -------
    Dict[str, FrozenSet[str]]
        Profile name -> tool names.

    Raises
    ------
    ProfileError
        The file is missing or not valid TOML, a profile has no tool list,
        the default profile is missing, or the file redefines ``all``.
    """
    path = Path(path)
    try:
        data = tomllib.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        raise ProfileError(f"Tool profiles file not found: {path}") from None
    except tomllib.TOMLDecodeError as e:
        raise ProfileError(f"Tool profiles file {path} is not valid TOML: {e}") from None

    tables = data.get("profiles")
    if not isinstance(tables, dict) or not tables:
        raise ProfileError(f"{path} defines no [profiles.<name>] tables")

    profiles: Dict[str, FrozenSet[str]] = {}
    for name, table in tables.items():
        if name == ALL_TOOLS:
            raise ProfileError(f"{path}: the '{ALL_TOOLS}' profile is built in and cannot be redefined")
        tools = table.get("tools") if isinstance(table, dict) else None
        if not isinstance(tools, list) or not tools or not all(isinstance(t, str) and t for t in tools):
            raise ProfileError(f"{path}: profile '{name}' needs a non-empty 'tools' list of tool names")
        profiles[name] = frozenset(tools)

    if DEFAULT_PROFILE not in profiles:
        raise ProfileError(f"{path}: the default profile '{DEFAULT_PROFILE}' is missing")
    return profiles


def check_profile_tools(profiles: Mapping[str, FrozenSet[str]], registered: FrozenSet[str]) -> None:
    """Fail when any profile names a tool the server does not register.

    A typo or a renamed tool would otherwise take that tool away from an
    agent without anyone noticing.
    """
    missing = {name: sorted(tools - registered) for name, tools in profiles.items() if tools - registered}
    if missing:
        details = "; ".join(f"{name}: {', '.join(tools)}" for name, tools in sorted(missing.items()))
        raise ProfileError(f"Tool profiles name tools the server does not have ({details})")


def apply_tool_profile(mcp: FastMCP, env: Optional[Mapping[str, str]] = None) -> ToolProfile:
    """Expose only the tools of the selected profile on ``mcp``.

    Parameters
    ----------
    mcp : FastMCP
        The server, with all its tools already registered.
    env : Mapping[str, str], optional
        Where to read ``MCP_AGENT_PROFILE`` and ``MCP_PROFILES_FILE`` from,
        by default ``os.environ``.

    Returns
    -------
    ToolProfile
        The profile now in force.

    Raises
    ------
    ProfileError
        The profiles file is unusable or the profile does not exist. The
        server is left untouched; the caller must not start it.
    """
    env = os.environ if env is None else env
    profiles = load_profiles(env.get(PROFILES_FILE_ENV) or DEFAULT_PROFILES_FILE)

    requested = (env.get(PROFILE_ENV) or "").strip()
    name = requested or DEFAULT_PROFILE
    if name == ALL_TOOLS:
        return ToolProfile(name, None, defaulted=False)
    if name not in profiles:
        choices = ", ".join(sorted([*profiles, ALL_TOOLS]))
        raise ProfileError(f"Unknown {PROFILE_ENV} '{name}'. Choose one of: {choices}")

    tools = profiles[name]
    mcp.disable(components={"tool"})
    mcp.enable(names=set(tools))
    return ToolProfile(name, tools, defaulted=not requested)


async def registered_tool_names(mcp: FastMCP) -> FrozenSet[str]:
    """Every tool registered on ``mcp``, including the hidden ones."""
    return frozenset(tool.name for tool in await mcp.local_provider.list_tools())


def profile_check_lifespan(
    env: Optional[Mapping[str, str]] = None,
) -> Callable[[FastMCP], AbstractAsyncContextManager[Dict[str, Any]]]:
    """Build a FastMCP lifespan that checks every profile against the server's tools.

    FastMCP enters the lifespan before it serves anything, however the
    server is started (``python mcp_server.py``, another program serving the
    imported ``mcp``, or an in-memory client), so this is the one place the
    check cannot be skipped. The tools are only known once all of them are
    registered, which is why it cannot run when the server object is created.

    Parameters
    ----------
    env : Mapping[str, str], optional
        Where to read ``MCP_PROFILES_FILE`` from, by default ``os.environ``.

    Returns
    -------
    Callable
        Pass as ``FastMCP(..., lifespan=...)``. It raises ``ProfileError``,
        and the server refuses to start, when a profile names a tool the
        server does not have.
    """
    @asynccontextmanager
    async def lifespan(server: FastMCP) -> AsyncIterator[Dict[str, Any]]:
        source = os.environ if env is None else env
        profiles = load_profiles(source.get(PROFILES_FILE_ENV) or DEFAULT_PROFILES_FILE)
        check_profile_tools(profiles, await registered_tool_names(server))
        yield {}

    return lifespan
