# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""Authentication and authorization for the REST bridge (``core/api_server.py``).

The REST bridge is not an MCP transport: its handlers call the domain
functions directly. It therefore has to enforce the same two controls the MCP
server enforces, in the same order:

1. The caller presents the deployment's REST API key.
2. The endpoint's MCP tool is in the active tool profile
   (``MCP_AGENT_PROFILE`` / ``MCP_PROFILES_FILE``).

Everything fails closed. A missing API key, an unreadable profiles file or an
unknown profile refuses the request instead of exposing the bridge.
"""

import logging
import os
import secrets
from typing import Callable, FrozenSet, Mapping, Optional

from fastapi import HTTPException, Request, status

from core.tool_profiles import ProfileError, ToolProfile, resolve_tool_profile

logger = logging.getLogger(__name__)

# Canonical deployment setting for the REST bridge. It is deliberately
# separate from the Fineract username/password the adapter uses server-side.
API_KEY_ENV = "MCP_REST_API_KEY"
API_KEY_HEADER = "X-API-Key"
CORS_ORIGINS_ENV = "MCP_REST_CORS_ORIGINS"

# Attribute used by tests and route introspection to prove every REST route
# declares the MCP tool it is allowed to execute.
REST_TOOL_ATTRIBUTE = "__rest_tool__"


def configured_api_key(env: Optional[Mapping[str, str]] = None) -> str:
    """Return the configured REST API key, or an empty string when unset."""
    source = os.environ if env is None else env
    return (source.get(API_KEY_ENV) or "").strip()


def configured_cors_origins(env: Optional[Mapping[str, str]] = None) -> list[str]:
    """Return the explicitly allowed browser origins (empty by default)."""
    source = os.environ if env is None else env
    raw = source.get(CORS_ORIGINS_ENV) or ""
    return [origin.strip() for origin in raw.split(",") if origin.strip()]


def _presented_api_key(request: Request) -> str:
    """Read the key from ``X-API-Key`` or ``Authorization: Bearer``."""
    key = (request.headers.get(API_KEY_HEADER) or "").strip()
    if key:
        return key
    scheme, _, value = (request.headers.get("Authorization") or "").partition(" ")
    if scheme.lower() == "bearer":
        return value.strip()
    return ""


def require_api_key(request: Request) -> None:
    """FastAPI dependency: reject requests without the deployment's API key.

    A deployment that has not configured a key is not usable: returning 503 is
    the only safe answer, because an unauthenticated fallback would expose the
    configured Fineract identity to anyone who can reach the bridge.
    """
    expected = configured_api_key()
    if not expected:
        logger.error("%s is not configured; refusing REST requests", API_KEY_ENV)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="REST API authentication is not configured.",
        )

    presented = _presented_api_key(request)
    # Compare bytes: compare_digest rejects non-ASCII strings, and a malformed
    # header must be a 401 rather than a 500.
    if not presented or not secrets.compare_digest(presented.encode("utf-8"), expected.encode("utf-8")):
        logger.warning("REST request rejected: missing or invalid API key")
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing or invalid API key.",
            headers={"WWW-Authenticate": 'Bearer realm="mifos-mcp"'},
        )


def active_tool_profile() -> ToolProfile:
    """Resolve the active profile or fail closed with HTTP 503."""
    try:
        return resolve_tool_profile()
    except ProfileError as e:
        logger.error("REST request refused: %s", e)
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
            detail="Tool profile configuration is unavailable.",
        ) from None


def active_profile_tools() -> Optional[FrozenSet[str]]:
    """Return the active profile's tool names, or ``None`` for the ``all`` profile."""
    return active_tool_profile().tools


def require_tool(tool_name: str) -> Callable[[], None]:
    """Build a dependency that allows only tools in the active profile.

    The dependency is attached to a REST route so authorization runs before
    the domain function is called, not after a write has already happened.
    """

    def dependency() -> None:
        profile = active_tool_profile()
        if profile.tools is not None and tool_name not in profile.tools:
            logger.warning(
                "REST request refused: tool '%s' is not in profile '%s'",
                tool_name,
                profile.name,
            )
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN,
                detail=f"Tool '{tool_name}' is not enabled by the active profile.",
            )

    setattr(dependency, REST_TOOL_ATTRIBUTE, tool_name)
    return dependency
