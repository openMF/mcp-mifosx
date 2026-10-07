# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Apache Fineract Agent Skill
============================

A reusable, framework-agnostic skill that enables AI agents (Pi Agent,
Hermes Agent, Prime-Agent) to interact with the Apache Fineract REST API.

Quick start::

    from fineract_skill import FineractClient

    client = FineractClient()
    clients = client.search_clients("John")
"""

from fineract_skill.client import FineractClient
from fineract_skill.tools import TOOL_REGISTRY, get_tool, list_tools

__all__ = [
    "FineractClient",
    "TOOL_REGISTRY",
    "get_tool",
    "list_tools",
]
