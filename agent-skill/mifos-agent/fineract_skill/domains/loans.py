# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Loan domain — create, approve, disburse, repay, reschedule, and query loans.
"""

from __future__ import annotations

from typing import Any

from fineract_skill.client import FineractClient, FineractError
from fineract_skill.helpers import fineract_today, fmt_date, safe_result

# ── Read ───────────────────────────────────────────────────────────────

def get_loan_details(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Get key details of a specific loan.

    Parameters
    ----------
    loan_id : int
        Fineract loan ID.

    Returns
    -------
    dict
        Fields: ``loanId``, ``status``, ``principal``, ``outstandingBalance``,
        ``interestRate``, timeline dates, repayment info.
    """
    try:
        data = client.get(f"loans/{loan_id}?associations=all")
        tl = data.get("timeline", {})
        return safe_result({
            "loanId": data.get("id"),
            "accountNo": data.get("accountNo"),
            "productName": data.get("loanProductName"),
            "status": data.get("status", {}).get("value"),
            "loanType": data.get("loanType", {}).get("value"),
            "principal": data.get("approvedPrincipal"),
            "outstandingBalance": data.get("summary", {}).get("totalOutstanding"),
            "interestRate": data.get("annualInterestRate"),
            "submittedDate": fmt_date(tl.get("submittedOnDate")),
            "approvedDate": fmt_date(tl.get("approvedOnDate")),
            "disbursedDate": fmt_date(tl.get("actualDisbursementDate")),
            "expectedMaturityDate": fmt_date(tl.get("expectedMaturityDate")),
            "numberOfRepayments": data.get("numberOfRepayments"),
            "repaymentFrequency": (
                f"Every {data.get('repaymentEvery')} "
                f"{data.get('repaymentFrequencyType', {}).get('value', '')}"
            ),
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_repayment_schedule(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Get the repayment schedule for a loan.

    Parameters
    ----------
    loan_id : int
        Fineract loan ID.
    """
    try:
        data = client.get(f"loans/{loan_id}?associations=repaymentSchedule")
        schedule = data.get("repaymentSchedule", {})
        periods = schedule.get("periods", [])
        return safe_result({
            "loanId": loan_id,
            "periods": [
                {
                    "period": p.get("period"),
                    "dueDate": fmt_date(p.get("dueDate")),
                    "principalDue": p.get("principalDue", 0),
                    "interestDue": p.get("interestDue", 0),
                    "feesDue": p.get("feeChargesDue", 0),
                    "totalDue": p.get("totalDueForPeriod", 0),
                    "totalPaid": p.get("totalPaidForPeriod", 0),
                    "isComplete": p.get("complete", False),
                }
                for p in periods
                if p.get("period")  # skip header period
            ],
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_loan_transactions(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Get the transaction history for a loan (repayments, disbursements, charges).

    Parameters
    ----------
    loan_id : int
        Fineract loan ID.
    """
    try:
        data = client.get(f"loans/{loan_id}?associations=transactions")
        txns = data.get("transactions", [])
        return safe_result({
            "loanId": loan_id,
            "transactions": [
                {
                    "transactionId": t.get("id"),
                    "type": (
                        t.get("type") if isinstance(t.get("type"), str)
                        else (t.get("type") or {}).get("value")
                    ),
                    "date": fmt_date(t.get("date")),
                    "amount": t.get("amount"),
                    "runningBalance": t.get("outstandingLoanBalance"),
                }
                for t in txns
            ],
        })
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def get_loan_template(
    client: FineractClient, client_id: int, product_id: int | None = None
) -> dict[str, Any]:
    """Get the pre-filled loan application template for a client.

    Useful for discovering available products and default values before
    creating a loan.
    """
    try:
        endpoint = f"loans/template?clientId={client_id}"
        if product_id:
            endpoint += f"&productId={product_id}"
        return safe_result(client.get(endpoint))
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Create ─────────────────────────────────────────────────────────────

def create_loan(
    client: FineractClient,
    client_id: int,
    principal: float,
    months: int,
    *,
    product_id: int = 1,
) -> dict[str, Any]:
    """Create a new individual loan application.

    Parameters
    ----------
    client_id : int
        Client to receive the loan.
    principal : float
        Loan principal amount.
    months : int
        Repayment term in months.
    product_id : int
        Loan product ID. Default ``1``.
    """
    today = fineract_today()
    payload = {
        "clientId": client_id,
        "productId": product_id,
        "principal": principal,
        "loanTermFrequency": months,
        "loanTermFrequencyType": 2,  # months
        "numberOfRepayments": months,
        "repaymentEvery": 1,
        "repaymentFrequencyType": 2,  # months
        "interestRatePerPeriod": 1.5,
        "amortizationType": 1,
        "interestType": 0,
        "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy",
        "expectedDisbursementDate": today,
        "submittedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "loanType": "individual",
    }
    try:
        result = client.post("loans", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def create_group_loan(
    client: FineractClient,
    group_id: int,
    principal: float,
    months: int,
    *,
    product_id: int = 1,
) -> dict[str, Any]:
    """Create a group loan application.

    Parameters
    ----------
    group_id : int
        Group to receive the loan.
    principal : float
        Loan principal.
    months : int
        Term in months.
    product_id : int
        Loan product ID.
    """
    today = fineract_today()
    payload = {
        "groupId": group_id,
        "productId": product_id,
        "principal": principal,
        "loanTermFrequency": months,
        "loanTermFrequencyType": 2,
        "numberOfRepayments": months,
        "repaymentEvery": 1,
        "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 1.5,
        "amortizationType": 1,
        "interestType": 0,
        "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy",
        "expectedDisbursementDate": today,
        "submittedOnDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
        "loanType": "group",
    }
    try:
        result = client.post("loans", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


# ── Lifecycle ──────────────────────────────────────────────────────────

def approve_and_disburse_loan(
    client: FineractClient, loan_id: int, amount: float | None = None
) -> dict[str, Any]:
    """Approve and disburse a pending loan in two steps.

    Parameters
    ----------
    loan_id : int
        Loan to approve and disburse.
    amount : float, optional
        Disbursement amount. If omitted, uses the approved principal.
    """
    if amount is not None and amount <= 0:
        return safe_result(None, error="Disbursement amount must be positive.")

    today = fineract_today()
    # Step 1: Approve
    try:
        client.post(f"loans/{loan_id}?command=approve", {
            "approvedOnDate": today,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
    except FineractError as exc:
        return safe_result(None, error=f"Approval failed: {exc}")

    # Step 2: Disburse
    disburse_payload: dict[str, Any] = {
        "actualDisbursementDate": today,
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    if amount is not None:
        disburse_payload["transactionAmount"] = amount

    try:
        result = client.post(f"loans/{loan_id}?command=disburse", disburse_payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=f"Disbursal failed (loan was approved): {exc}")


def reject_loan(
    client: FineractClient, loan_id: int, note: str = "Rejected via Agent"
) -> dict[str, Any]:
    """Reject a pending loan application.

    Parameters
    ----------
    loan_id : int
        Loan to reject.
    note : str
        Rejection reason.
    """
    try:
        result = client.post(f"loans/{loan_id}?command=reject", {
            "rejectedOnDate": fineract_today(),
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
            "note": note,
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def make_repayment(
    client: FineractClient, loan_id: int, amount: float
) -> dict[str, Any]:
    """Make a repayment on an active loan.

    Parameters
    ----------
    loan_id : int
        Active loan ID.
    amount : float
        Repayment amount.
    """
    try:
        result = client.post(f"loans/{loan_id}/transactions?command=repayment", {
            "transactionDate": fineract_today(),
            "transactionAmount": amount,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def waive_interest(
    client: FineractClient,
    loan_id: int,
    amount: float,
    note: str = "Waived via Agent",
) -> dict[str, Any]:
    """Waive interest on a loan.

    Parameters
    ----------
    loan_id : int
        Active loan ID.
    amount : float
        Amount of interest to waive.
    note : str
        Reason for the waiver.
    """
    try:
        result = client.post(f"loans/{loan_id}/transactions?command=waiveInterest", {
            "transactionDate": fineract_today(),
            "transactionAmount": amount,
            "dateFormat": "dd MMMM yyyy",
            "locale": "en",
            "note": note,
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def undo_loan_approval(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Undo a loan approval (returns loan to pending status)."""
    try:
        result = client.post(f"loans/{loan_id}?command=undoApproval", {
            "note": "Undone via Agent",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def undo_loan_disbursal(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Undo a loan disbursal (returns loan to approved status)."""
    try:
        result = client.post(f"loans/{loan_id}?command=undoDisbursal", {
            "note": "Disbursal undone via Agent",
        })
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def update_loan(
    client: FineractClient,
    loan_id: int,
    *,
    principal: float | None = None,
    months: int | None = None,
    product_id: int | None = None,
) -> dict[str, Any]:
    """Update a draft/submitted loan application.

    Only pending or submitted loans can be modified.
    """
    payload: dict[str, Any] = {"locale": "en", "dateFormat": "dd MMMM yyyy"}
    if principal is not None:
        payload["principal"] = principal
    if months is not None:
        payload["loanTermFrequency"] = months
        payload["numberOfRepayments"] = months
    if product_id is not None:
        payload["productId"] = product_id

    if len(payload) <= 2:
        return safe_result(None, error="No fields to update.")

    try:
        result = client.put(f"loans/{loan_id}", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def delete_loan(client: FineractClient, loan_id: int) -> dict[str, Any]:
    """Delete a draft/submitted loan application."""
    try:
        result = client.delete(f"loans/{loan_id}")
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))


def reschedule_loan(
    client: FineractClient,
    loan_id: int,
    reschedule_from_date: str,
    reschedule_reason_id: int,
    *,
    adjusted_due_date: str | None = None,
    new_interest_rate: float | None = None,
    grace_on_principal: int | None = None,
    extra_terms: int | None = None,
    reason: str = "Rescheduled via Agent",
) -> dict[str, Any]:
    """Submit a loan reschedule request.

    At least one modification (adjusted_due_date, new_interest_rate,
    grace_on_principal, or extra_terms) is required.

    Parameters
    ----------
    loan_id : int
        Active loan to reschedule.
    reschedule_from_date : str
        Date from which to reschedule (``"dd MMMM yyyy"``).
    """
    payload: dict[str, Any] = {
        "loanId": loan_id,
        "rescheduleFromDate": reschedule_from_date,
        "rescheduleReasonId": reschedule_reason_id,
        "rescheduleReasonComment": reason,
        "submittedOnDate": fineract_today(),
        "dateFormat": "dd MMMM yyyy",
        "locale": "en",
    }
    if adjusted_due_date:
        payload["adjustedDueDate"] = adjusted_due_date
    if new_interest_rate is not None:
        payload["newInterestRate"] = new_interest_rate
    if grace_on_principal is not None:
        payload["graceOnPrincipal"] = grace_on_principal
    if extra_terms is not None:
        payload["extraTerms"] = extra_terms

    try:
        result = client.post("rescheduleloans", payload)
        return safe_result(result)
    except FineractError as exc:
        return safe_result(None, error=str(exc))
