# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from __future__ import annotations

import logging
import re
from time import perf_counter

logger = logging.getLogger(__name__)

# v1 keyword-to-tool mapping: lightweight and easy to extend.
KEYWORD_TOOL_MAP: dict[str, list[str]] = {
    "loan": ["get_loan_details", "create_loan", "get_loan"],
    "client": ["get_client", "create_client", "create_new_client", "search_clients"],
    "payment": ["make_payment", "make_repayment"],
    "repayment": ["repayment_schedule", "get_repayment_sched", "make_repayment"],
}

PREFERRED_TOOL_BY_KEYWORD: dict[str, str] = {
    "loan": "get_loan_details",
    "client": "create_client",
    "payment": "make_payment",
    "repayment": "repayment_schedule",
}


def _tokenize(text: str) -> set[str]:
    return set(re.findall(r"[a-z0-9_]+", text.lower()))


def suggest_tools(query: str) -> list[str]:
    """Return a ranked list of MCP tool names relevant to the given query."""
    started = perf_counter()

    if not query or not query.strip():
        logger.info("SUGGEST_TOOLS query=%r suggested_tools=[] duration_ms=%.2f", query, (perf_counter() - started) * 1000)
        return []

    lowered = query.lower()
    query_tokens = _tokenize(query)
    scores: dict[str, int] = {}

    for keyword, tools in KEYWORD_TOOL_MAP.items():
        if keyword in lowered:
            for tool in tools:
                # Base score for keyword hit.
                scores[tool] = scores.get(tool, 0) + 2

            preferred = PREFERRED_TOOL_BY_KEYWORD.get(keyword)
            if preferred:
                scores[preferred] = scores.get(preferred, 0) + 2

    # Add simple lexical overlap scoring for ranking quality.
    for tool in list(scores.keys()):
        tool_tokens = set(tool.lower().split("_"))
        overlap = len(tool_tokens & query_tokens)
        if overlap:
            scores[tool] += overlap

    ranked_tools = [
        tool for tool, _ in sorted(scores.items(), key=lambda item: (-item[1], item[0]))
    ]

    logger.info(
        "SUGGEST_TOOLS query=%r suggested_tools=%s duration_ms=%.2f",
        query,
        ranked_tools,
        (perf_counter() - started) * 1000,
    )
    return ranked_tools


if __name__ == "__main__":
    query = "show loan details"
    result = {
        "query": query,
        "suggested_tools": suggest_tools(query),
    }

    import json

    print("TOOL: suggest_tools")
    print("STATUS: success")
    print("OUTPUT:")
    print(json.dumps(result, indent=2))
