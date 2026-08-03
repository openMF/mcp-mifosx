# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""VSLA / group-banking domain wrappers — TIER 2 datatable extensions.

Each datatable is created via /mifos-bridge once; these wrappers expose
CRUD-style domain functions over the generic datatable API so consumers
(and AI agents) can speak in domain terms (record_attendance, push_notification)
instead of raw datatable JSON.

All 15 datatables and their wrappers are scoped to `mifos-x-group-banking`.
"""

from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# --- Helpers ---

DATE_FMT = {"dateFormat": "yyyy-MM-dd", "locale": "en"}
DATETIME_FMT = {"dateFormat": "yyyy-MM-dd HH:mm:ss", "locale": "en"}


def _entries(table: str, entity_id: int):
    return fineract_client.execute_get(f"datatables/{table}/{entity_id}")


def _create_entry(table: str, entity_id: int, data: dict, with_dates: bool = False):
    payload = dict(data)
    if with_dates:
        payload.update(DATE_FMT)
    return fineract_client.execute_post(f"datatables/{table}/{entity_id}", payload)


def _update_entry(table: str, entity_id: int, data: dict, with_dates: bool = False):
    payload = dict(data)
    if with_dates:
        payload.update(DATE_FMT)
    return fineract_client.execute_put(f"datatables/{table}/{entity_id}", payload)


# ════════════════════════════════════════════════════════════════════
# T2-1  dt_group_config  (m_center, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_group_config(center_id: int):
    """Answers: 'Show the cycle rules for group #N' — savings min/max, loan multiplier, fines, status."""
    print(f"[Tool] Fetching dt_group_config for center #{center_id}...")
    return _entries("dt_group_config", center_id)


@tool
def upsert_group_config(center_id: int, config: dict):
    """Answers: 'Save the group's cycle rules'. config keys match dt_group_config columns
    (cycle_number, cycle_length_months, contribution_min/max, loan_multiplier, ...)."""
    print(f"[Tool] Upserting dt_group_config for center #{center_id}...")
    existing = _entries("dt_group_config", center_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_group_config", center_id, config, with_dates=True)
    return _create_entry("dt_group_config", center_id, config, with_dates=True)


@tool
def advance_cycle(center_id: int, new_cycle_number: int, new_start_date: str, new_end_date: str):
    """Answers: 'Start a new cycle for group #N after share-out'. Increments cycle_number,
    sets new dates, resets cycle_status to 'active'."""
    print(f"[Tool] Advancing center #{center_id} to cycle #{new_cycle_number}...")
    return _update_entry("dt_group_config", center_id, {
        "cycle_number": new_cycle_number,
        "cycle_start_date": new_start_date,
        "cycle_end_date": new_end_date,
        "cycle_status": "active",
    }, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-2  dt_meeting_record  (m_center, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_meetings(center_id: int):
    """Answers: 'Show all meeting records for group #N'."""
    print(f"[Tool] Fetching meeting records for center #{center_id}...")
    return _entries("dt_meeting_record", center_id)


@tool
def record_meeting(center_id: int, meeting: dict):
    """Answers: 'Save a meeting summary'. meeting keys: meeting_number, meeting_date, status,
    members_present, members_absent, total_savings_collected, total_fines_collected,
    loans_approved_count, loans_approved_amount, notes."""
    print(f"[Tool] Recording meeting for center #{center_id}...")
    return _create_entry("dt_meeting_record", center_id, meeting, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-3  dt_meeting_attendance  (m_client, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_attendance(client_id: int):
    """Answers: 'Show member #N's attendance history'."""
    print(f"[Tool] Fetching attendance for client #{client_id}...")
    return _entries("dt_meeting_attendance", client_id)


@tool
def record_attendance(
    client_id: int,
    meeting_number: int,
    meeting_date: str,
    present: bool,
    late: bool = False,
    fine_applied: float = 0.0,
    fine_reason: str = None,
):
    """Answers: 'Mark member #N present/absent at meeting M'. fine_reason is the audit string
    when a fine is applied (e.g. 'late', 'absent_unexcused')."""
    print(f"[Tool] Recording attendance for client #{client_id}, meeting #{meeting_number}...")
    return _create_entry("dt_meeting_attendance", client_id, {
        "meeting_number": meeting_number,
        "meeting_date": meeting_date,
        "present": present,
        "late": late,
        "fine_applied": fine_applied,
        "fine_reason": fine_reason,
    }, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-4  dt_member_role  (m_client, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_member_role(client_id: int):
    """Answers: 'What role does member #N hold?' — chairperson, treasurer, secretary, member."""
    print(f"[Tool] Fetching role for client #{client_id}...")
    return _entries("dt_member_role", client_id)


@tool
def assign_member_role(client_id: int, role: str, assigned_date: str, assigned_by: str = None):
    """Answers: 'Make member #N the chairperson'. role: chairperson | treasurer | secretary | member."""
    print(f"[Tool] Assigning role '{role}' to client #{client_id}...")
    payload = {"role": role, "assigned_date": assigned_date, "assigned_by": assigned_by}
    existing = _entries("dt_member_role", client_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_member_role", client_id, payload, with_dates=True)
    return _create_entry("dt_member_role", client_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-5  dt_share_out  (m_center, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_share_outs(center_id: int):
    """Answers: 'Show all share-out records for group #N'."""
    print(f"[Tool] Fetching share-outs for center #{center_id}...")
    return _entries("dt_share_out", center_id)


@tool
def record_share_out(center_id: int, share_out: dict):
    """Answers: 'Record this cycle's share-out distribution'. share_out keys: cycle_number,
    share_out_date, total_pool, total_interest_earned, total_fines_collected, members_count,
    distribution_json, status."""
    print(f"[Tool] Recording share-out for center #{center_id}...")
    return _create_entry("dt_share_out", center_id, share_out, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-6  dt_social_fund  (m_center, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_social_fund(center_id: int):
    """Answers: 'How much is in the social fund for group #N?'"""
    print(f"[Tool] Fetching social fund for center #{center_id}...")
    return _entries("dt_social_fund", center_id)


@tool
def update_social_fund(center_id: int, payload: dict):
    """Answers: 'Update the group's social-fund balance / disbursement state'."""
    print(f"[Tool] Updating social fund for center #{center_id}...")
    existing = _entries("dt_social_fund", center_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_social_fund", center_id, payload, with_dates=True)
    return _create_entry("dt_social_fund", center_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-7  dt_loan_vote  (m_loan, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_loan_vote(loan_id: int):
    """Answers: 'How did the group vote on loan #N?'"""
    print(f"[Tool] Fetching vote record for loan #{loan_id}...")
    return _entries("dt_loan_vote", loan_id)


@tool
def record_loan_vote(loan_id: int, payload: dict):
    """Answers: 'Save the in-meeting vote for loan #N'. payload keys: meeting_number, votes_for,
    votes_against, approved, approved_by_chairperson, notes."""
    print(f"[Tool] Recording vote for loan #{loan_id}...")
    existing = _entries("dt_loan_vote", loan_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_loan_vote", loan_id, payload)
    return _create_entry("dt_loan_vote", loan_id, payload)


# ════════════════════════════════════════════════════════════════════
# T2-8  dt_sync_metadata  (m_center, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_sync_state(center_id: int):
    """Answers: 'When did group #N last sync, and how many ops are pending?'"""
    print(f"[Tool] Fetching sync state for center #{center_id}...")
    return _entries("dt_sync_metadata", center_id)


@tool
def update_sync_state(center_id: int, payload: dict):
    """Answers: 'Update the device's sync metadata for group #N'."""
    print(f"[Tool] Updating sync state for center #{center_id}...")
    existing = _entries("dt_sync_metadata", center_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_sync_metadata", center_id, payload, with_dates=True)
    return _create_entry("dt_sync_metadata", center_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-9  dt_loan_request  (m_client, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_loan_requests(client_id: int):
    """Answers: 'Show member #N's loan requests'."""
    print(f"[Tool] Fetching loan requests for client #{client_id}...")
    return _entries("dt_loan_request", client_id)


@tool
def submit_loan_request(
    client_id: int,
    requested_amount: float,
    purpose: str,
    desired_duration_weeks: int,
    request_date: str,
    eligibility_amount: float = None,
):
    """Answers: 'Submit a loan request from member #N'. status auto-set to 'pending'."""
    print(f"[Tool] Submitting loan request for client #{client_id}...")
    return _create_entry("dt_loan_request", client_id, {
        "requested_amount": requested_amount,
        "purpose": purpose,
        "desired_duration_weeks": desired_duration_weeks,
        "eligibility_amount": eligibility_amount,
        "request_date": request_date,
        "status": "pending",
    }, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-10  dt_group_corpus  (m_center, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_group_corpus(center_id: int):
    """Answers: 'What's the running corpus balance for group #N?'"""
    print(f"[Tool] Fetching corpus for center #{center_id}...")
    return _entries("dt_group_corpus", center_id)


@tool
def update_group_corpus(center_id: int, payload: dict):
    """Answers: 'Update the running corpus after a meeting inflow/outflow'."""
    print(f"[Tool] Updating corpus for center #{center_id}...")
    existing = _entries("dt_group_corpus", center_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_group_corpus", center_id, payload, with_dates=True)
    return _create_entry("dt_group_corpus", center_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-11  dt_group_loan_policy  (m_center, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_loan_policy(center_id: int):
    """Answers: 'What's the loan-ceiling policy for group #N?' — flat | savings_multiplier | hybrid."""
    print(f"[Tool] Fetching loan policy for center #{center_id}...")
    return _entries("dt_group_loan_policy", center_id)


@tool
def set_loan_policy(center_id: int, payload: dict):
    """Answers: 'Configure the group's loan ceiling rule'. payload: ceiling_rule, flat_cap,
    savings_multiplier, hybrid_minimum."""
    print(f"[Tool] Setting loan policy for center #{center_id}...")
    existing = _entries("dt_group_loan_policy", center_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_group_loan_policy", center_id, payload, with_dates=True)
    return _create_entry("dt_group_loan_policy", center_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-12  dt_member_ceiling_override  (m_client, single-row)
# ════════════════════════════════════════════════════════════════════

@tool
def get_member_ceiling(client_id: int):
    """Answers: 'Does member #N have a ceiling override?' — returns override_pct, reason, granted_by."""
    print(f"[Tool] Fetching ceiling override for client #{client_id}...")
    return _entries("dt_member_ceiling_override", client_id)


@tool
def set_member_ceiling(client_id: int, override_pct: float, reason: str, granted_by: str, granted_at: str):
    """Answers: 'Grant member #N a +20% ceiling override'."""
    print(f"[Tool] Setting ceiling override for client #{client_id}...")
    payload = {"override_pct": override_pct, "reason": reason,
               "granted_by": granted_by, "granted_at": granted_at}
    existing = _entries("dt_member_ceiling_override", client_id)
    if isinstance(existing, list) and len(existing) > 0:
        return _update_entry("dt_member_ceiling_override", client_id, payload, with_dates=True)
    return _create_entry("dt_member_ceiling_override", client_id, payload, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-13  dt_loan_guarantor  (m_loan, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_loan_guarantors(loan_id: int):
    """Answers: 'Who is guaranteeing loan #N and what's their pro-rata share?'"""
    print(f"[Tool] Fetching guarantors for loan #{loan_id}...")
    return _entries("dt_loan_guarantor", loan_id)


@tool
def add_loan_guarantor(loan_id: int, guarantor_client_id: int, share_pct: float):
    """Answers: 'Add member #N as guarantor for X% of loan #M'. signed=False until they
    confirm in-meeting. deducted_at_share_out tracks default-recovery state."""
    print(f"[Tool] Adding guarantor #{guarantor_client_id} to loan #{loan_id}...")
    return _create_entry("dt_loan_guarantor", loan_id, {
        "guarantor_client_id": guarantor_client_id,
        "share_pct": share_pct,
        "signed": False,
        "deducted_at_share_out": False,
        "deducted_amount": 0,
    })


# ════════════════════════════════════════════════════════════════════
# T2-14  dt_notification  (m_client, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_notifications(client_id: int):
    """Answers: 'What notifications does member #N have?' Returns full list with read/unread state."""
    print(f"[Tool] Fetching notifications for client #{client_id}...")
    return _entries("dt_notification", client_id)


@tool
def push_notification(
    client_id: int,
    event_type: str,
    title: str,
    body: str,
    event_created_at: str,
    priority: str = "normal",
    delivered_via: str = "in-app",
    payload_json: str = None,
):
    """Answers: 'Send notification to member #N'. event_type tags ('loan_approved', 'meeting_t1h', etc.).
    priority: low | normal | high. delivered_via: in-app | fcm | sms.
    event_created_at: app-level event timestamp (Fineract auto-tracks db row created_at separately)."""
    print(f"[Tool] Pushing notification to client #{client_id}: {event_type}...")
    return _create_entry("dt_notification", client_id, {
        "event_type": event_type,
        "title": title,
        "body": body,
        "payload_json": payload_json,
        "event_created_at": event_created_at,
        "read": False,
        "priority": priority,
        "delivered_via": delivered_via,
    }, with_dates=True)


# ════════════════════════════════════════════════════════════════════
# T2-15  dt_member_invitation  (m_client, multi-row)
# ════════════════════════════════════════════════════════════════════

@tool
def list_invitations(client_id: int):
    """Answers: 'Show invitations issued by member #N'."""
    print(f"[Tool] Fetching invitations issued by client #{client_id}...")
    return _entries("dt_member_invitation", client_id)


@tool
def create_invitation(
    client_id: int,
    stewardship_code: str,
    invited_at: str,
    expires_at: str,
    target_center_id: int,
    channel: str = "sms",
):
    """Answers: 'Issue an invitation code from member #N'. stewardship_code is a 6-char one-time code;
    channel: sms | email."""
    print(f"[Tool] Creating invitation from client #{client_id} (code={stewardship_code})...")
    return _create_entry("dt_member_invitation", client_id, {
        "stewardship_code": stewardship_code,
        "invited_at": invited_at,
        "expires_at": expires_at,
        "used": False,
        "used_at": None,
        "target_center_id": target_center_id,
        "channel": channel,
    }, with_dates=True)
