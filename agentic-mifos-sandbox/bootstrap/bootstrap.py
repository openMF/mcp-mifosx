# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""One-shot sandbox bootstrap. Safe to run on every `docker compose up`.

1. Fineract: move the admin user off the image's default password onto the
   one from .env (only while the default still works).
2. Fineract: create the loan product the agentic flow submits loans against
   (looked up by short name, created only if missing).
3. Fineract: one service user per agent, with a role granting exactly the
   permissions in fineract-rbac.json, then check that each agent is allowed
   inside its role and refused (HTTP 403) outside it.
4. Lightning: the raw credentials the jobs use, built from the environment and
   replaced when their settings change (a rotated password or token):
   "fineract" (the write-back job's login, the agent-writeback user) and
   "agent-<name>" (the URL and token of an agent's runner, for the job that
   calls it).
5. Lightning: provision the OpenFn project from project.yaml.

Every id in the provisioned project is derived from its key in project.yaml
(UUID v5), and the two webhook trigger ids come from the compose file, so
webhook URLs never change and a re-run updates the same project instead of
creating a second one.
"""

import hashlib
import json
import os
import ssl
import sys
import time
import urllib.error
import urllib.request
import uuid
from base64 import b64encode
from pathlib import Path

import yaml

# Fixed namespace for deriving project ids; changing it creates a new project.
ID_NAMESPACE = uuid.UUID("5c1f3a44-9d0e-4f53-9a0b-6b2a6f0e7c11")

LOAN_PRODUCT = {
    "name": "Agentic Personal Loan",
    "shortName": "AGPL",
    "currencyCode": "USD",
    "digitsAfterDecimal": 2,
    "inMultiplesOf": 0,
    "principal": 20000,
    "minPrincipal": 1000,
    "maxPrincipal": 100000,
    "numberOfRepayments": 12,
    "minNumberOfRepayments": 1,
    "maxNumberOfRepayments": 60,
    "repaymentEvery": 1,
    "repaymentFrequencyType": 2,
    "interestRatePerPeriod": 1,
    "interestRateFrequencyType": 2,
    "amortizationType": 1,
    "interestType": 1,
    "interestCalculationPeriodType": 1,
    "transactionProcessingStrategyCode": "mifos-standard-strategy",
    "loanScheduleType": "CUMULATIVE",
    "daysInYearType": 1,
    "daysInMonthType": 1,
    "isInterestRecalculationEnabled": False,
    "accountingRule": 1,
    "locale": "en",
}


def env(name):
    value = os.environ.get(name)
    if not value:
        sys.exit(f"bootstrap: {name} is not set")
    return value


def log(message):
    print(f"bootstrap: {message}", flush=True)


def request(method, url, headers, body=None, insecure=False):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **headers})
    context = ssl._create_unverified_context() if insecure else None
    try:
        with urllib.request.urlopen(req, context=context, timeout=60) as resp:
            payload = resp.read()
            return json.loads(payload) if payload else None
    except urllib.error.HTTPError as err:
        sys.exit(f"bootstrap: {method} {url} failed with HTTP {err.code}: {err.read().decode(errors='replace')[:2000]}")


def status(method, url, headers, body=None):
    """HTTP status of a Fineract request, for the access checks (never exits)."""
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers={"Content-Type": "application/json", **headers})
    try:
        with urllib.request.urlopen(req, context=ssl._create_unverified_context(), timeout=60) as resp:
            return resp.status
    except urllib.error.HTTPError as err:
        return err.code


def stable_id(*parts):
    return str(uuid.uuid5(ID_NAMESPACE, "/".join(parts)))


# --- Fineract ----------------------------------------------------------------


def fineract_headers(username=None, password=None):
    username = username or env("FINERACT_USERNAME")
    password = password or env("FINERACT_PASSWORD")
    auth = b64encode(f"{username}:{password}".encode()).decode()
    return {"Authorization": f"Basic {auth}", "Fineract-Platform-TenantId": env("FINERACT_TENANT")}


def ensure_admin_password():
    """Sign in as the admin with FINERACT_PASSWORD; while the image's default
    password still works instead, change it to FINERACT_PASSWORD. The old
    password is not known after that, so a later change starts in the web app."""
    base = env("FINERACT_URL")
    username = env("FINERACT_USERNAME")
    probe = f"{base}/clients?limit=1"
    code = status("GET", probe, fineract_headers())
    if code == 200:
        log(f"Fineract admin '{username}' signs in with the password from .env")
        return
    if code != 401:
        sys.exit(f"bootstrap: signing in to Fineract as '{username}' failed with HTTP {code}")
    default = fineract_headers(password=env("FINERACT_DEFAULT_PASSWORD"))
    if status("GET", probe, default) != 200:
        sys.exit(
            f"bootstrap: the password of Fineract's admin '{username}' is neither FINERACT_ADMIN_PASSWORD"
            " nor the default: set FINERACT_ADMIN_PASSWORD to the current one, or change it in the web app"
        )
    users = request("GET", f"{base}/users", default, insecure=True)
    user = next(u for u in users if u["username"] == username)
    password = env("FINERACT_PASSWORD")
    request("PUT", f"{base}/users/{user['id']}", default, {"password": password, "repeatPassword": password}, insecure=True)
    if status("GET", probe, fineract_headers()) != 200:
        sys.exit(f"bootstrap: Fineract admin '{username}' does not sign in with the new password")
    log(f"changed the password of Fineract's admin '{username}' from the default to the one from .env")


def ensure_loan_product():
    base = env("FINERACT_URL")
    products = request("GET", f"{base}/loanproducts", fineract_headers(), insecure=True)
    for product in products:
        if product.get("shortName") == LOAN_PRODUCT["shortName"]:
            log(f"Fineract loan product {LOAN_PRODUCT['shortName']} exists (id {product['id']})")
            return product["id"]
    created = request("POST", f"{base}/loanproducts", fineract_headers(), LOAN_PRODUCT, insecure=True)
    log(f"created Fineract loan product {LOAN_PRODUCT['shortName']} (id {created['resourceId']})")
    return created["resourceId"]


def ensure_role(base, name, description, wanted, known):
    """Create the role if missing and make its permissions exactly `wanted`."""
    unknown = sorted(set(wanted) - known)
    if unknown:
        sys.exit(f"bootstrap: role '{name}' lists permissions Fineract does not have: {', '.join(unknown)}")
    roles = request("GET", f"{base}/roles", fineract_headers(), insecure=True)
    role_id = next((r["id"] for r in roles if r["name"] == name), None)
    if role_id is None:
        role_id = request("POST", f"{base}/roles", fineract_headers(), {"name": name, "description": description}, insecure=True)[
            "resourceId"
        ]
        log(f"created Fineract role '{name}' (id {role_id})")
    current = request("GET", f"{base}/roles/{role_id}/permissions", fineract_headers(), insecure=True)
    granted = {p["code"] for p in current["permissionUsageData"] if p.get("selected")}
    if granted != set(wanted):
        # Grant what is missing and revoke anything else, e.g. a permission
        # added by hand in the web app.
        changes = {code: code in wanted for code in granted | set(wanted)}
        request("PUT", f"{base}/roles/{role_id}/permissions", fineract_headers(), {"permissions": changes}, insecure=True)
        log(f"Fineract role '{name}': permissions set to {', '.join(sorted(wanted))}")
    return role_id


def ensure_agent_user(base, username, agent, role_id, password):
    """Create the agent's service user, or bring its role and password in line."""
    users = request("GET", f"{base}/users", fineract_headers(), insecure=True)
    user = next((u for u in users if u["username"] == username), None)
    if user is None:
        body = {
            "username": username,
            "firstname": "Agent",
            "lastname": agent,
            "email": f"{username}@sandbox.local",
            "officeId": int(os.environ.get("FINERACT_OFFICE_ID", "1")),
            "roles": [role_id],
            "sendPasswordToEmail": False,
            "passwordNeverExpires": True,
            "password": password,
            "repeatPassword": password,
        }
        request("POST", f"{base}/users", fineract_headers(), body, insecure=True)
        log(f"created Fineract user '{username}'")
        return
    changes = {}
    if [r["id"] for r in user.get("selectedRoles", [])] != [role_id]:
        changes["roles"] = [role_id]
    # A password changed in .env since the user was created no longer logs in.
    if status("GET", f"{base}/clients?limit=1", fineract_headers(username, password)) == 401:
        changes.update(password=password, repeatPassword=password)
    if changes:
        request("PUT", f"{base}/users/{user['id']}", fineract_headers(), changes, insecure=True)
        log(f"updated Fineract user '{username}' ({', '.join(k for k in changes if k != 'repeatPassword')})")
    else:
        log(f"Fineract user '{username}' is up to date")


def check_agent_access(base, username, password, permissions):
    """Prove the role is enforced: a read inside it works, requests outside it get 403."""
    headers = fineract_headers(username, password)
    allowed = status("GET", f"{base}/clients?limit=1", headers)
    # Every agent is refused user administration and disbursing a loan; a
    # read-only agent is also refused creating a client. An empty body would
    # fail validation anyway, so a misconfigured role still creates nothing.
    refused = {
        "GET /users": status("GET", f"{base}/users", headers),
        "POST /loans/0?command=disburse": status("POST", f"{base}/loans/0?command=disburse", headers, {}),
    }
    if "CREATE_CLIENT" not in permissions:
        refused["POST /clients"] = status("POST", f"{base}/clients", headers, {})
    if allowed != 200 or any(code != 403 for code in refused.values()):
        details = ", ".join(f"{req} -> {code}" for req, code in refused.items())
        sys.exit(f"bootstrap: '{username}' access check failed: GET /clients -> {allowed}, {details}")
    log(f"  {username}: GET /clients -> 200, " + ", ".join(f"{req} -> 403" for req in refused))


def ensure_agent_users():
    base = env("FINERACT_URL")
    spec = json.loads(Path(env("FINERACT_AGENT_RBAC")).read_text())
    known = {p["code"] for p in request("GET", f"{base}/permissions", fineract_headers(), insecure=True)}
    for agent, entry in spec["agents"].items():
        username = f"agent-{agent}"
        password = env(f"FINERACT_AGENT_{agent.upper()}_PASSWORD")
        role_id = ensure_role(base, entry["role"], entry["description"], entry["permissions"], known)
        ensure_agent_user(base, username, agent, role_id, password)
    log("checking agent access")
    for agent, entry in spec["agents"].items():
        check_agent_access(base, f"agent-{agent}", env(f"FINERACT_AGENT_{agent.upper()}_PASSWORD"), entry["permissions"])


# --- Lightning ---------------------------------------------------------------


def lightning_headers():
    token_file = Path(env("LIGHTNING_API_TOKEN_FILE"))
    return {"Authorization": f"Bearer {token_file.read_text().strip()}"}


def credential_body(name, product_id):
    """The body of a Lightning credential that project.yaml names."""
    if name == "fineract":
        # The write-back job signs in as its agent user, whose role allows only
        # the job's calls (fineract-rbac.json); never as the admin.
        return {
            "baseUrl": env("FINERACT_URL"),
            "username": "agent-writeback",
            "password": env("FINERACT_AGENT_WRITEBACK_PASSWORD"),
            "tenantId": env("FINERACT_TENANT"),
            "productId": product_id,
            "officeId": int(os.environ.get("FINERACT_OFFICE_ID", "1")),
            # Fineract inside the sandbox uses a self-signed certificate.
            "tls": {"rejectUnauthorized": False},
        }
    if name.startswith("agent-"):
        # An agent's HTTP runner: AGENT_<NAME>_URL and AGENT_<NAME>_TOKEN.
        prefix = "AGENT_" + name.removeprefix("agent-").upper().replace("-", "_")
        return {"url": env(f"{prefix}_URL"), "token": env(f"{prefix}_TOKEN")}
    sys.exit(f"bootstrap: no settings known for the Lightning credential '{name}'")


def ensure_credential(name, body):
    """Creates the credential if missing, and replaces one whose settings
    changed: the API returns no bodies and cannot update one, so a fingerprint
    of the body is kept in external_id. Provisioning then links the new
    credential to the project. An edit made in Lightning stays until the
    settings here change."""
    base = env("LIGHTNING_URL")
    fingerprint = "sha256:" + hashlib.sha256(json.dumps(body, sort_keys=True).encode()).hexdigest()[:16]
    existing = request("GET", f"{base}/api/credentials", lightning_headers())
    for credential in existing["credentials"]:
        if credential.get("name") != name:
            continue
        if credential.get("external_id") == fingerprint:
            log(f"Lightning credential '{name}' exists")
            return
        request("DELETE", f"{base}/api/credentials/{credential['id']}", lightning_headers())
        log(f"replacing Lightning credential '{name}': its settings changed")
        break
    # The API ignores a top-level `body`; bodies go in `credential_bodies`,
    # and a project without environments resolves the one named "main".
    request(
        "POST",
        f"{base}/api/credentials",
        lightning_headers(),
        {
            "name": name,
            "schema": "raw",
            "external_id": fingerprint,
            "credential_bodies": [{"name": "main", "body": body}],
        },
    )
    log(f"created Lightning credential '{name}'")


def provisioning_document(spec, trigger_ids, owner):
    """Converts a project.yaml spec into Lightning's provisioning JSON."""
    project_key = spec["name"]
    credentials = {}
    for key, cred in (spec.get("credentials") or {}).items():
        credentials[key] = {"id": stable_id(project_key, "credential", cred["name"]), "name": cred["name"], "owner": owner}

    workflows = []
    for wf_key, wf in spec["workflows"].items():
        job_ids = {key: stable_id(project_key, wf_key, "job", key) for key in wf.get("jobs", {})}
        trigger_ids_by_key = {}
        triggers = []
        for key, trigger in (wf.get("triggers") or {}).items():
            trigger_id = trigger_ids.get(key) or stable_id(project_key, wf_key, "trigger", key)
            trigger_ids_by_key[key] = trigger_id
            triggers.append({"id": trigger_id, "type": trigger["type"], "enabled": trigger.get("enabled", True)})
        jobs = []
        for key, job in (wf.get("jobs") or {}).items():
            entry = {"id": job_ids[key], "name": job["name"], "adaptor": job["adaptor"], "body": job["body"]}
            if job.get("credential"):
                entry["project_credential_id"] = credentials[job["credential"]]["id"]
            jobs.append(entry)
        edges = []
        for key, edge in (wf.get("edges") or {}).items():
            entry = {
                "id": stable_id(project_key, wf_key, "edge", key),
                "condition_type": edge.get("condition_type", "always"),
                "enabled": edge.get("enabled", True),
                "target_job_id": job_ids[edge["target_job"]],
            }
            if edge.get("source_trigger"):
                entry["source_trigger_id"] = trigger_ids_by_key[edge["source_trigger"]]
            else:
                entry["source_job_id"] = job_ids[edge["source_job"]]
            if edge.get("condition_expression"):
                entry["condition_expression"] = edge["condition_expression"]
            edges.append(entry)
        workflows.append(
            {"id": stable_id(project_key, wf_key), "name": wf["name"], "jobs": jobs, "triggers": triggers, "edges": edges}
        )

    return {
        "id": stable_id(project_key),
        "name": project_key,
        "description": (spec.get("description") or "").strip(),
        "project_credentials": list(credentials.values()),
        "workflows": workflows,
    }


def provision_project(product_id):
    spec = yaml.safe_load(Path(env("OPENFN_PROJECT_SPEC")).read_text())
    trigger_ids = {
        "webhook-loan-submit": env("OPENFN_TRIGGER_LOAN_SUBMIT"),
        "webhook-loan-review": env("OPENFN_TRIGGER_LOAN_REVIEW"),
    }
    for cred in (spec.get("credentials") or {}).values():
        name = cred["name"]
        ensure_credential(name, credential_body(name, product_id))
    document = provisioning_document(spec, trigger_ids, env("LIGHTNING_ADMIN_EMAIL"))
    request("POST", f"{env('LIGHTNING_URL')}/api/provision", lightning_headers(), document)
    log(f"provisioned OpenFn project '{document['name']}' ({document['id']})")
    for key, trigger_id in trigger_ids.items():
        log(f"  {key}: {env('LIGHTNING_URL')}/i/{trigger_id}")


def main():
    # The token file is written by lightning-setup; it can lag a few seconds.
    token_file = Path(env("LIGHTNING_API_TOKEN_FILE"))
    for _ in range(30):
        if token_file.exists() and token_file.read_text().strip():
            break
        time.sleep(2)
    else:
        sys.exit(f"bootstrap: {token_file} was not written by lightning-setup")

    ensure_admin_password()
    product_id = ensure_loan_product()
    ensure_agent_users()
    provision_project(product_id)
    log("done")


if __name__ == "__main__":
    main()
