# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import math

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

def _validate_journal_items(items: list, label: str) -> dict | None:
    for item in items:
        if not isinstance(item, dict):
            return {"error": f"Each {label} item must be a dictionary with glAccountId and amount."}
        gl_id = item.get("glAccountId")
        if isinstance(gl_id, bool) or not isinstance(gl_id, int) or gl_id <= 0:
            return {"error": f"Each {label} item must have a positive glAccountId."}
        amt = item.get("amount")
        if isinstance(amt, bool) or not isinstance(amt, (int, float)) or amt <= 0:
            return {"error": f"Each {label} item must have a positive amount."}
        try:
            if not math.isfinite(amt):
                return {"error": f"Each {label} item must have a positive amount."}
        except OverflowError:
            return {"error": f"Each {label} item must have a positive amount."}
    return None


@tool
def create_journal_entry(office_id: int, date: str, credits: list, debits: list, currency_code: str,
                         comment: str = ""):
    """Answers: 'Record a manual journal entry'
    credits/debits format: [{"glAccountId": 1, "amount": 100}]
    currency_code is the entry's currency (e.g. "USD"). Fineract requires it, and it differs per
    tenant, so it is not guessed.
    """
    if isinstance(office_id, bool) or not isinstance(office_id, int) or office_id <= 0:
        return {"error": "office_id must be a positive integer."}
    if not isinstance(currency_code, str) or not currency_code.strip():
        return {"error": "currency_code is required for a journal entry (e.g. \"USD\") and cannot be blank."}
    if not isinstance(credits, list) or len(credits) == 0:
        return {"error": "credits must be a non-empty list."}
    if not isinstance(debits, list) or len(debits) == 0:
        return {"error": "debits must be a non-empty list."}
    credits_err = _validate_journal_items(credits, "credit")
    if credits_err:
        return credits_err
    debits_err = _validate_journal_items(debits, "debit")
    if debits_err:
        return debits_err
    print("[Tool] Creating Journal Entry...")
    payload = {
        "officeId": office_id,
        "currencyCode": currency_code.strip(),
        "transactionDate": date,
        "comments": comment,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "credits": credits,
        "debits": debits
    }
    return fineract_client.execute_post("journalentries", payload)
