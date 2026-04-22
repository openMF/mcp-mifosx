from typing import Dict, Any

class ValidationError(Exception):
    pass


def _require_positive_int(params: Dict[str, Any], name: str) -> None:
    value = params.get(name)
    if not isinstance(value, int) or value <= 0:
        raise ValidationError(f"{name} must be a positive integer")


def _require_positive_number(params: Dict[str, Any], name: str) -> None:
    value = params.get(name)
    if not isinstance(value, (int, float)) or value <= 0:
        raise ValidationError(f"{name} must be positive")


def validate_input(tool_name: str, params: Dict[str, Any]) -> None:
    if tool_name == "get_loan":
        _require_positive_int(params, "loanId")

    if tool_name == "make_repayment":
        _require_positive_int(params, "loanId")
        _require_positive_number(params, "amount")