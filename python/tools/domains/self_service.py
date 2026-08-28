# Copyright since 2025 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

import os

import requests
from langchain_core.tools import tool

from tools.mcp_adapter import fineract_client

# Self-service endpoints (/self/*) accept any user's credentials, not just the
# staff user baked into FineractAdapter via .env. Each tool below takes
# optional username/password; when omitted, the env staff credentials are used
# so the tool still functions for AI-assistant flows that authenticate as staff.


def _resolve_credentials(username: str = None, password: str = None):
    return (
        username or os.getenv("MIFOSX_USERNAME"),
        password or os.getenv("MIFOSX_PASSWORD"),
    )


def _self_get(endpoint: str, username: str = None, password: str = None):
    url = f"{fineract_client.base_url}/{endpoint}"
    user, pw = _resolve_credentials(username, password)
    try:
        response = requests.get(
            url,
            headers={
                "Fineract-Platform-TenantId": fineract_client.tenant_id,
                "Content-Type": "application/json",
            },
            auth=(user, pw),
            verify=False,
        )
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        if e.response is not None:
            return {"error": fineract_client._parse_fineract_error(e.response)}
        return {"error": f"Connection failed: {str(e)}"}


def _self_post(endpoint: str, payload: dict, username: str = None, password: str = None):
    url = f"{fineract_client.base_url}/{endpoint}"
    user, pw = _resolve_credentials(username, password)
    try:
        response = requests.post(
            url,
            headers={
                "Fineract-Platform-TenantId": fineract_client.tenant_id,
                "Content-Type": "application/json",
            },
            auth=(user, pw),
            json=payload,
            verify=False,
        )
        response.raise_for_status()
        return response.json()
    except requests.exceptions.RequestException as e:
        if e.response is not None:
            return {"error": fineract_client._parse_fineract_error(e.response)}
        return {"error": f"Connection failed: {str(e)}"}


# --- T1-1 STAFF AUTHENTICATION ---

@tool
def authenticate(username: str, password: str):
    """Answers: 'Authenticate as staff user'. Returns a base64 auth key plus role info.
    Use the returned key in subsequent calls; this is what the consumer Kotlin client caches."""
    print(f"[Tool] Authenticating staff user '{username}'...")
    payload = {"username": username, "password": password}
    return _self_post("authentication", payload, username, password)


# --- T1-2 SELF-SERVICE AUTHENTICATION ---

@tool
def self_authenticate(username: str, password: str):
    """Answers: 'Authenticate as a self-service end-user'. Returns a base64 auth key + clientId.
    Distinct from staff /authentication: the user must be a self-service-enabled client."""
    print(f"[Tool] Self-authenticating end-user '{username}'...")
    payload = {"username": username, "password": password}
    return _self_post("self/authentication", payload, username, password)


# --- T1-3 GET SELF CLIENT PROFILE ---

@tool
def get_self_client(username: str = None, password: str = None):
    """Answers: 'Show my own client profile'. Self-service users only see themselves."""
    print("[Tool] Fetching self client profile...")
    return _self_get("self/clients", username, password)


# --- T1-4 LIST SELF SAVINGS ACCOUNTS ---

@tool
def list_self_savings(username: str = None, password: str = None):
    """Answers: 'List my own savings accounts'."""
    print("[Tool] Fetching self savings accounts...")
    return _self_get("self/savingsaccounts", username, password)


# --- T1-5 LIST SELF LOAN ACCOUNTS ---

@tool
def list_self_loans(username: str = None, password: str = None):
    """Answers: 'List my own loan accounts'."""
    print("[Tool] Fetching self loan accounts...")
    return _self_get("self/loans", username, password)
