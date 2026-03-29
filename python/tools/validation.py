# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

from __future__ import annotations

from typing import Any


def validation_error_response(
    message: str,
    field: str,
    value: Any,
    expected: str,
    http_status_code: int = 400,
) -> dict:
    """Return a consistent validation error payload for MCP tools."""
    return {
        "error": message,
        "httpStatusCode": http_status_code,
        "validation": {
            "field": field,
            "value": value,
            "expected": expected,
        },
    }


def _is_positive_int(value: Any) -> bool:
    return isinstance(value, int) and not isinstance(value, bool) and value > 0


def _is_positive_number(value: Any) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and value > 0


def validate_tool_params(params: dict[str, Any]) -> dict | None:
    """Validate common MCP tool parameters and return structured error if invalid.

    Validation rules are intentionally conservative to preserve compatibility:
    - Any `*Id` / `*_id` parameter must be a positive integer when provided.
    - Common quantitative fields (amount/principal/months/terms/rates) must be positive.
    - Date-like fields, when present, must be non-empty strings.
    """
    for name, value in params.items():
        if value is None:
            continue

        lname = name.lower()

        # Common Fineract/MCP ID fields: clientId, loanId, productId, loan_id, etc.
        if name.endswith("Id") or lname.endswith("_id") or lname == "id":
            if not _is_positive_int(value):
                return validation_error_response(
                    message=f"Invalid {name}. It must be a positive integer.",
                    field=name,
                    value=value,
                    expected="positive integer",
                )
            continue

        # Positive integer counters/period values.
        if lname in {"months", "extraterms", "graceonprincipal", "numberofrepayments"}:
            if not _is_positive_int(value):
                return validation_error_response(
                    message=f"Invalid {name}. It must be a positive integer.",
                    field=name,
                    value=value,
                    expected="positive integer",
                )
            continue

        # Positive monetary/rate values.
        if any(token in lname for token in ("amount", "principal", "rate")):
            if not _is_positive_number(value):
                return validation_error_response(
                    message=f"Invalid {name}. It must be a positive number.",
                    field=name,
                    value=value,
                    expected="positive number",
                )
            continue

        # Date-like strings should not be empty when passed.
        if "date" in lname:
            if not isinstance(value, str) or not value.strip():
                return validation_error_response(
                    message=f"Invalid {name}. It must be a non-empty date string.",
                    field=name,
                    value=value,
                    expected="non-empty string",
                )

    return None
