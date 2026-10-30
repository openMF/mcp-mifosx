# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# --- LOAN PRODUCT OPERATIONS ---

@tool
def list_loan_products():
    """Answers: 'What loan products are available?' or 'Show me all loan product types'.
    Use this before create_loan to find a valid productId."""
    print("[Tool] Fetching all loan products...")
    response = fineract_client.execute_get("loanproducts")
    if "error" in response:
        return response
    products = response if isinstance(response, list) else response.get("pageItems", [])
    return {
        "loanProducts": [
            {
                "productId":          p.get("id"),
                "name":               p.get("name"),
                "shortName":          p.get("shortName"),
                "currency":           p.get("currency", {}).get("code"),
                "minPrincipal":       p.get("minPrincipal"),
                "maxPrincipal":       p.get("maxPrincipal"),
                "defaultPrincipal":   p.get("principal"),
                "interestRate_pct":   p.get("interestRatePerPeriod"),
                "minInterestRate_pct": p.get("minInterestRatePerPeriod"),
                "maxInterestRate_pct": p.get("maxInterestRatePerPeriod"),
                "repaymentEvery":     p.get("repaymentEvery"),
                "repaymentFrequency": p.get("repaymentFrequencyType", {}).get("value"),
                "numberOfRepayments": p.get("numberOfRepayments"),
            }
            for p in products
        ]
    }


@tool
def get_loan_product(product_id: int):
    """Answers: 'Show me the full details for loan product #3' or 'What are the interest and fee rules for this product?'"""
    print(f"[Tool] Fetching loan product #{product_id}...")
    data = fineract_client.execute_get(f"loanproducts/{product_id}")
    if "error" in data or not isinstance(data, dict):
        return data
    return {
        "productId":            data.get("id"),
        "name":                 data.get("name"),
        "shortName":            data.get("shortName"),
        "description":          data.get("description"),
        "currency":             data.get("currency", {}).get("code"),
        "principal":            data.get("principal"),
        "minPrincipal":         data.get("minPrincipal"),
        "maxPrincipal":         data.get("maxPrincipal"),
        "interestRate_pct":     data.get("interestRatePerPeriod"),
        "minInterestRate_pct":  data.get("minInterestRatePerPeriod"),
        "maxInterestRate_pct":  data.get("maxInterestRatePerPeriod"),
        "interestType":         data.get("interestType", {}).get("value"),
        "amortizationType":     data.get("amortizationType", {}).get("value"),
        "repaymentEvery":       data.get("repaymentEvery"),
        "repaymentFrequency":   data.get("repaymentFrequencyType", {}).get("value"),
        "numberOfRepayments":   data.get("numberOfRepayments"),
        "charges":              [
            {"chargeId": c.get("id"), "name": c.get("name"), "amount": c.get("amount")}
            for c in data.get("charges", [])
        ],
    }


# --- SAVINGS PRODUCT OPERATIONS ---

@tool
def list_savings_products():
    """Answers: 'What savings products are offered?' or 'List all savings account types'.
    Use this before create_savings_account to find a valid productId."""
    print("[Tool] Fetching all savings products...")
    response = fineract_client.execute_get("savingsproducts")
    if "error" in response:
        return response
    products = response if isinstance(response, list) else response.get("pageItems", [])
    return {
        "savingsProducts": [
            {
                "productId":                  p.get("id"),
                "name":                       p.get("name"),
                "shortName":                  p.get("shortName"),
                "currency":                   p.get("currency", {}).get("code"),
                "nominalAnnualInterestRate":   p.get("nominalAnnualInterestRate"),
                "minRequiredOpeningBalance":   p.get("minRequiredOpeningBalance"),
                "withdrawalFeeForTransfers":   p.get("withdrawalFeeForTransfers"),
            }
            for p in products
        ]
    }


@tool
def get_savings_product(product_id: int):
    """Answers: 'Show me the full details for savings product #2' or 'What are the interest rules for this savings account type?'"""
    print(f"[Tool] Fetching savings product #{product_id}...")
    data = fineract_client.execute_get(f"savingsproducts/{product_id}")
    if "error" in data or not isinstance(data, dict):
        return data
    return {
        "productId":                    data.get("id"),
        "name":                         data.get("name"),
        "shortName":                    data.get("shortName"),
        "description":                  data.get("description"),
        "currency":                     data.get("currency", {}).get("code"),
        "nominalAnnualInterestRate":     data.get("nominalAnnualInterestRate"),
        "interestCompoundingPeriod":     data.get("interestCompoundingPeriodType", {}).get("value"),
        "interestPostingPeriod":         data.get("interestPostingPeriodType", {}).get("value"),
        "interestCalculationType":       data.get("interestCalculationType", {}).get("value"),
        "minRequiredOpeningBalance":     data.get("minRequiredOpeningBalance"),
        "lockinPeriodFrequency":         data.get("lockinPeriodFrequency"),
        "lockinPeriodFrequencyType":     data.get("lockinPeriodFrequencyType", {}).get("value"),
        "withdrawalFeeForTransfers":     data.get("withdrawalFeeForTransfers"),
        "charges":                       [
            {"chargeId": c.get("id"), "name": c.get("name"), "amount": c.get("amount")}
            for c in data.get("charges", [])
        ],
    }


def product_required(kind: str) -> dict:
    """Error for a new loan or savings account without a productId, listing the products Fineract has.

    kind is "loan" or "savings". Product ids are tenant-specific, so there is no id that is
    right everywhere and the configured products are returned instead of guessing one.
    """
    if kind == "loan":
        response, key = list_loan_products.func(), "loanProducts"
    else:
        response, key = list_savings_products.func(), "savingsProducts"
    if not isinstance(response, dict) or key not in response:
        reason = response.get("error") if isinstance(response, dict) else response
        return {"error": f"productId is required for a new {kind} account, and the {kind} products could "
                         f"not be loaded: {reason}"}
    options = [{"id": p["productId"], "name": p["name"]} for p in response[key] if p.get("productId") is not None]
    if not options:
        return {"error": f"productId is required for a new {kind} account, but no {kind} products are "
                         f"configured. Add a {kind} product in Fineract first."}
    # The MCP wrapper (safe_tool) passes on only the message, so the options have to be in it
    listed = "; ".join(f"{o['id']} = {o['name']}" for o in options)
    return {"error": f"productId is required for a new {kind} account. Ask the user which {kind} product "
                     f"applies: {listed}.",
            f"{kind}_products": options}
