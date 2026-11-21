# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import logging

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

logger = logging.getLogger(__name__)

# --- CODE TABLE READ OPERATIONS ---

@tool
def list_codes():
    """Answers: 'Show me all system codes' or 'What dropdown categories are available?'
    Returns all code definitions (Gender, Client Type, ID Type, etc.)"""
    logger.info("[Tool] Fetching all system codes...")
    return fineract_client.execute_get("codes")

@tool
def get_code_values(code_id: int):
    """Answers: 'Show me the values for code ID 4' or 'What are the gender options?'
    Use list_codes() first to find the code ID, then use this to get its dropdown values."""
    logger.info(f"[Tool] Fetching code values for code #{code_id}...")
    return fineract_client.execute_get(f"codes/{code_id}/codevalues")

@tool
def list_datatables():
    """Answers: 'Show me all data tables' or 'What custom fields are registered?'
    Returns all registered data tables (custom fields, additional data extensions)."""
    logger.info("[Tool] Fetching all datatables...")
    return fineract_client.execute_get("datatables")
