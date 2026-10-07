# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Charges domain — manage fee and penalty definitions.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import safe_result


def list_charges(client: FineractClient) -> dict[str, Any]:
    """List all available charge definitions (fees and penalties)."""
    try:
        return safe_result(client.get("charges"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_charge(client: FineractClient, charge_id: int) -> dict[str, Any]:
    """Get details of a specific charge.

    Parameters
    ----------
    charge_id : int
        Charge definition ID.
    """
    try:
        return safe_result(client.get(f"charges/{charge_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_charge(
    client: FineractClient,
    name: str,
    amount: float,
    *,
    currency_code: str = "USD",
    charge_applies_to: int = 1,
    charge_time_type: int,
    charge_calculation_type: int = 1,
    is_penalty: bool = False,
    is_active: bool = True,
) -> dict[str, Any]:
    """Create a new charge definition.

    Parameters
    ----------
    name : str
        Charge name.
    amount : float
        Default amount.
    currency_code : str
        ISO currency code (default ``"USD"``).
    charge_applies_to : int
        1=Loan, 2=Savings, 3=Client.
    charge_time_type : int
        1=Disbursement, 2=Specified Due Date, 3=Savings Activation, 5=Withdrawal Fee.
    charge_calculation_type : int
        1=Flat, 2=% of Amount.
    is_penalty : bool
        Whether this charge is a penalty.
    is_active : bool
        Whether this charge is active.
    """
    if charge_applies_to == 1 and charge_time_type not in (1, 2):
        return safe_result(None, error="For Loan (1), charge_time_type must be 1 (Disbursement) or 2 (Specified Due Date).")
    if charge_applies_to == 2 and charge_time_type not in (3, 5):
        return safe_result(None, error="For Savings (2), charge_time_type must be 3 (Savings Activation) or 5 (Withdrawal Fee).")

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
    }
    try:
        result = client.post("charges", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def update_charge(
    client: FineractClient,
    charge_id: int,
    *,
    name: str | None = None,
    amount: float | None = None,
    is_active: bool | None = None,
) -> dict[str, Any]:
    """Update an existing charge definition.

    Only the provided fields are changed.
    """
    payload: dict[str, Any] = {"locale": "en"}
    if name is not None:
        payload["name"] = name
    if amount is not None:
        payload["amount"] = amount
    if is_active is not None:
        payload["active"] = is_active

    if len(payload) <= 1:
        return safe_result(None, error="No fields to update.")

    try:
        result = client.put(f"charges/{charge_id}", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))
