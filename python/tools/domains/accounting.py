# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client


@tool
def list_gl_accounts(type: int = None):
    """Answers: 'Show me the Chart of Accounts' or 'List Asset accounts'
    Types: 1=Asset, 2=Liability, 3=Equity, 4=Income, 5=Expense
    """
    endpoint = "glaccounts"
    if type: endpoint += f"?type={type}"
    print("[Tool] Fetching GL Accounts...")
    return fineract_client.execute_get(endpoint)

@tool
def get_journal_entries(account_id: int = None, transaction_id: str = None):
    """Answers: 'Show me the ledger for account #5' or 'Show details for txn LDGR-123'"""
    endpoint = "journalentries"
    params = []
    if account_id: params.append(f"glAccountId={account_id}")
    if transaction_id: params.append(f"transactionId={transaction_id}")
    if params: endpoint += "?" + "&".join(params)

    print("[Tool] Fetching Journal Entries...")
    return fineract_client.execute_get(endpoint)

@tool
def create_journal_entry(office_id: int, date: str, credits: list, debits: list, comment: str = ""):
    """Answers: 'Record a manual journal entry'
    credits/debits format: [{"glAccountId": 1, "amount": 100}]
    """
    print("[Tool] Creating Journal Entry...")
    payload = {
        "officeId": office_id,
        "transactionDate": date,
        "comments": comment,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "credits": credits,
        "debits": debits
    }
    return fineract_client.execute_post("journalentries", payload)
