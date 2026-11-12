# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Who may call the LOS, and what each caller may do.

Every caller has its own token, LOS_<CALLER>_TOKEN, sent as
`Authorization: Bearer <token>`. The token decides the caller, and the
caller decides the actor recorded in the audit trail.
"""

import hmac
from dataclasses import dataclass
from typing import Annotated

from fastapi import Depends, Header, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

import rules

# What a token can allow, and how a refusal names it.
ACTIONS = {
    "search": "search opportunities",
    "read": "read an opportunity",
    "create": "create opportunities",
    "edit": "edit opportunities",
    "stage": "move opportunities between stages",
    "consent": "record consents",
    "opt_out": "record opt-outs",
    "register": "record registrations",
    "events": "read the audit trail",
    "upload": "upload documents",
    "documents": "list documents",
    "document_content": "read a document's image",
    "check": "record document checks",
}


@dataclass(frozen=True)
class Permissions:
    actions: frozenset[str]
    # People who share one token: X-Actor names the person. A caller that is
    # one program is named by its token alone.
    shared: bool = False
    # The stage moves allowed, as (stages from, stages to); None allows any.
    moves: tuple[frozenset[str], frozenset[str]] | None = None


CALLERS = {
    # The OpenFn intake job: records leads, and sets aside at once the ones it
    # cannot take (duplicates, no way to contact). It never moves one forward.
    "intake": Permissions(
        frozenset({"search", "create", "stage"}),
        moves=(frozenset({"new"}), frozenset({rules.PAUSED, rules.DISCARDED})),
    ),
    # The OpenFn eligibility workflow: checks one qualifying opportunity, then
    # moves it on to documents or pauses it with the reason. Never further.
    "eligibility": Permissions(
        frozenset({"read", "stage"}),
        moves=(frozenset({"qualifying"}), frozenset({"documents", rules.PAUSED})),
    ),
    # The OpenFn identity workflow: reads the newest identity document of an
    # opportunity in documents, records what its check found, then moves the
    # opportunity on to assessing or pauses it. Never uploads.
    "identity": Permissions(
        frozenset({"read", "documents", "document_content", "check", "stage"}),
        moves=(frozenset({"documents"}), frozenset({"assessing", rules.PAUSED})),
    ),
    # People on the host (curl, /docs).
    "officer": Permissions(frozenset(ACTIONS), shared=True),
}

MIN_TOKEN_LENGTH = 32


class Unauthorized(Exception):
    """No token, or one the LOS does not know (HTTP 401)."""


class Forbidden(Exception):
    """The caller's token does not allow the request (HTTP 403)."""


def token_variable(caller: str) -> str:
    return f"LOS_{caller.upper()}_TOKEN"


def load_tokens(environ) -> dict[str, str]:
    """Each caller's token, from LOS_<CALLER>_TOKEN. Refuses a missing or short
    token, and one token for two callers, which would make them the same caller."""
    tokens = {}
    for caller in CALLERS:
        token = environ.get(token_variable(caller), "")
        if len(token) < MIN_TOKEN_LENGTH:
            raise ValueError(f"{token_variable(caller)} is missing or shorter than {MIN_TOKEN_LENGTH} characters; "
                             "run scripts/gen-secrets.sh")
        tokens[caller] = token
    if len(set(tokens.values())) < len(tokens):
        raise ValueError(f"each caller needs its own token: {', '.join(map(token_variable, CALLERS))} must differ")
    return tokens


bearer = HTTPBearer(auto_error=False, description="A caller's token from .env: LOS_OFFICER_TOKEN for people")


def caller_of(request: Request, credentials: Annotated[HTTPAuthorizationCredentials | None, Depends(bearer)]) -> str:
    """The caller whose token the request carries."""
    if credentials is None:
        raise Unauthorized("send a caller's token: Authorization: Bearer <token>")
    given = credentials.credentials.encode()
    for caller, token in request.app.state.tokens.items():
        if hmac.compare_digest(given, token.encode()):
            return caller
    raise Unauthorized("unknown token")


def allow(action: str):
    """Dependency admitting the callers whose token allows `action`; gives the caller."""
    def check(caller: Annotated[str, Depends(caller_of)]) -> str:
        if action not in CALLERS[caller].actions:
            raise Forbidden(f"{caller} may not {ACTIONS[action]}")
        return caller
    return Depends(check)


@dataclass(frozen=True)
class Who:
    caller: str
    # Recorded in the audit trail.
    actor: str


def change(action: str):
    """Like allow, for a change: also gives the actor for the audit trail."""
    def check(
        caller: Annotated[str, allow(action)],
        x_actor: Annotated[str | None, Header(pattern=r"^[A-Za-z0-9 ._@:-]{1,64}$",
                                              description="The person making the change, with a shared token")] = None,
    ) -> Who:
        if CALLERS[caller].shared:
            return Who(caller, f"{caller}:{x_actor}" if x_actor else caller)
        if x_actor not in (None, caller):
            raise Forbidden(f"{caller} is named by its token; X-Actor must be {caller} or absent")
        return Who(caller, caller)
    return Depends(check)


def check_move(caller: str, current: str, target: str) -> None:
    """Refuses a stage move outside the caller's permissions."""
    moves = CALLERS[caller].moves
    if moves and (current not in moves[0] or target not in moves[1]):
        raise Forbidden(f"{caller} may only move an opportunity from {' or '.join(sorted(moves[0]))} to {' or '.join(sorted(moves[1]))}")
