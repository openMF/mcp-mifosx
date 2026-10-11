from typing import Any, Dict


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


def _require_positive_resource_ids(params: Dict[str, Any]) -> None:
    """Reject non-positive Fineract resource IDs before any request.

    Resource IDs are always positive in Fineract, so a value like
    ``clientId=-5`` is certain to fail. Checking it here avoids a request
    that cannot succeed — and, for wrappers that pre-fetch the record to
    confirm it exists, avoids reporting "not found" when the real cause
    was a bad argument.

    Only values that are already integers are checked: optional IDs are
    either absent from ``params`` or ``None``, strings such as
    ``externalId`` are left to the schema, and ``bool`` is excluded
    because it subclasses ``int``.

    Parameters
    ----------
    params : Dict[str, Any]
        Bound arguments for the tool, including defaults.
    """
    for name, value in params.items():
        if isinstance(value, bool) or not isinstance(value, int):
            continue
        if (name == "id" or name.endswith("Id")) and value <= 0:
            raise ValidationError(f"{name} must be a positive integer")


def validate_input(tool_name: str, params: Dict[str, Any]) -> None:
    # Applies to every tool: covers the ones with no specific rules below
    _require_positive_resource_ids(params)

    if tool_name == "get_loan":
        _require_positive_int(params, "loanId")

    if tool_name == "make_repayment":
        _require_positive_int(params, "loanId")
        _require_positive_number(params, "amount")
