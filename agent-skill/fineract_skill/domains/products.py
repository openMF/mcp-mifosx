# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Products domain — loan products and savings products.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import safe_result


def list_loan_products(client: FineractClient) -> dict[str, Any]:
    """List all loan products with principal ranges, interest rates, and terms."""
    try:
        return safe_result(client.get("loanproducts"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_loan_product(client: FineractClient, product_id: int) -> dict[str, Any]:
    """Get full details for a loan product.

    Parameters
    ----------
    product_id : int
        Loan product ID.
    """
    try:
        return safe_result(client.get(f"loanproducts/{product_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def list_savings_products(client: FineractClient) -> dict[str, Any]:
    """List all savings products with interest rates and minimums."""
    try:
        return safe_result(client.get("savingsproducts"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_savings_product(client: FineractClient, product_id: int) -> dict[str, Any]:
    """Get full details for a savings product.

    Parameters
    ----------
    product_id : int
        Savings product ID.
    """
    try:
        return safe_result(client.get(f"savingsproducts/{product_id}"))
    except FineractError as exc:
        return safe_result(None, error=str(exc))
