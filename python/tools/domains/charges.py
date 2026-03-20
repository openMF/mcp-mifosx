# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool
from tools.mcp_adapter import fineract_client

# --- CHARGE READ OPERATIONS ---

@tool
def list_charges():
    """Answers: 'Show me all available charges' or 'What fees are configured?'
    Returns all charge definitions (fees and penalties) in the system."""
    print("[Tool] Fetching all charges...")
    return fineract_client.execute_get("charges")

@tool
def get_charge(charge_id: int):
    """Answers: 'Show me details for charge ID 5' or 'What is the Late Payment Fee charge?'"""
    print(f"[Tool] Fetching charge #{charge_id}...")
    return fineract_client.execute_get(f"charges/{charge_id}")

# --- CHARGE WRITE OPERATIONS ---

@tool
def create_charge(name: str, amount: float, currency_code: str = "USD",
                  charge_applies_to: int = 1, charge_time_type: int = 2,
                  charge_calculation_type: int = 1, is_penalty: bool = False,
                  is_active: bool = True):
    """Answers: 'Create a late payment fee of $50' or 'Add a new loan disbursement charge'
    charge_applies_to: 1=Loan, 2=Savings, 3=Client
    charge_time_type: 1=Disbursement, 2=Specified Due Date, 8=Savings Activation, 9=Withdrawal Fee
    charge_calculation_type: 1=Flat, 2=% of Amount
    """
    print(f"[Tool] Creating charge: {name}...")
    payload = {
        "name": name,
        "amount": amount,
        "currencyCode": currency_code,
        "chargeAppliesTo": charge_applies_to,
        "chargeTimeType": charge_time_type,
        "chargeCalculationType": charge_calculation_type,
        "penalty": is_penalty,
        "active": is_active,
        "locale": "en",
        "monthDayFormat": "dd MMM"
    }
    return fineract_client.execute_post("charges", payload)

@tool
def update_charge(charge_id: int, name: str = None, amount: float = None,
                  is_active: bool = None):
    """Answers: 'Update charge 5 to $75' or 'Deactivate charge ID 3'"""
    print(f"[Tool] Updating charge #{charge_id}...")
    payload = {"locale": "en"}
    if name is not None:
        payload["name"] = name
    if amount is not None:
        payload["amount"] = amount
    if is_active is not None:
        payload["active"] = is_active
    return fineract_client.execute_put(f"charges/{charge_id}", payload)
