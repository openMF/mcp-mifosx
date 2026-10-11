# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""One-shot sandbox bootstrap. Safe to run on every `docker compose up`.

1. Fineract: create the loan product the agentic flow submits loans against
   (looked up by short name, created only if missing).
2. Lightning: create the raw "fineract" credential the write-back job uses
   (created only if missing).
3. Lightning: provision the OpenFn project from project.yaml.

Every id in the provisioned project is derived from its key in project.yaml
(UUID v5), and the two webhook trigger ids come from the compose file, so
webhook URLs never change and a re-run updates the same project instead of
creating a second one.
"""

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


def stable_id(*parts):
    return str(uuid.uuid5(ID_NAMESPACE, "/".join(parts)))


# --- Fineract ----------------------------------------------------------------


def fineract_headers():
    auth = b64encode(f"{env('FINERACT_USERNAME')}:{env('FINERACT_PASSWORD')}".encode()).decode()
    return {"Authorization": f"Basic {auth}", "Fineract-Platform-TenantId": env("FINERACT_TENANT")}


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


# --- Lightning ---------------------------------------------------------------


def lightning_headers():
    token_file = Path(env("LIGHTNING_API_TOKEN_FILE"))
    return {"Authorization": f"Bearer {token_file.read_text().strip()}"}


def ensure_fineract_credential(name, product_id):
    base = env("LIGHTNING_URL")
    existing = request("GET", f"{base}/api/credentials", lightning_headers())
    for credential in existing["credentials"]:
        if credential.get("name") == name:
            log(f"Lightning credential '{name}' exists")
            return
    body = {
        "baseUrl": env("FINERACT_URL"),
        "username": env("FINERACT_USERNAME"),
        "password": env("FINERACT_PASSWORD"),
        "tenantId": env("FINERACT_TENANT"),
        "productId": product_id,
        "officeId": int(os.environ.get("FINERACT_OFFICE_ID", "1")),
        # Fineract inside the sandbox uses a self-signed certificate.
        "tls": {"rejectUnauthorized": False},
    }
    # The API ignores a top-level `body`; bodies go in `credential_bodies`,
    # and a project without environments resolves the one named "main".
    request(
        "POST",
        f"{base}/api/credentials",
        lightning_headers(),
        {"name": name, "schema": "raw", "credential_bodies": [{"name": "main", "body": body}]},
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
        ensure_fineract_credential(cred["name"], product_id)
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

    product_id = ensure_loan_product()
    provision_project(product_id)
    log("done")


if __name__ == "__main__":
    main()
