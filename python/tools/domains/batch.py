# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client


# --- T1-6 BATCH API ---

@tool
def send_batch(operations: list, enclosing_transaction: bool = False):
    """Answers: 'Drain the offline outbox via Fineract Batch API'.
    operations: list of {requestId:int, method:str, relativeUrl:str, headers?:list, body?:dict}.
    Up to 200 ops/request. Set enclosing_transaction=True for atomic batches (any failure rolls
    back the whole batch). Returns list of {requestId, statusCode, body} per op, in submission order."""
    print(f"[Tool] Sending batch of {len(operations)} operation(s) "
          f"(transactional={enclosing_transaction})...")
    endpoint = "batches"
    if enclosing_transaction:
        endpoint += "?enclosingTransaction=true"
    return fineract_client.execute_post(endpoint, operations)
