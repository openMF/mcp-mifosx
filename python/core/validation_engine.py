from typing import Dict, Any


class ValidationError(Exception):
    pass


def validate_input(tool_name: str, params: Dict[str, Any]) -> None:
    if tool_name == "get_loan":
        if not params.get("loanId"):
            raise ValidationError("loanId is required")

    if tool_name == "make_repayment":
        if params.get("amount", 0) <= 0:
            raise ValidationError("Amount must be positive")