#!/usr/bin/env python3
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""The commercial flow end to end on a running sandbox, for a demo (README 4.9).

A fictitious applicant goes through every stage the way an officer drives it:
intake, eligibility, the identity check by the vision model, the bureau
consent, the assessment, the officer's pre-approval and registration in
Fineract, where a person approves the loan. Run from agentic-mifos-sandbox with
the sandbox up (docker compose --profile ollama up -d --wait):

    python3 scripts/demo.py                 the main run
    python3 scripts/demo.py --scenarios     then three cases the rules stop
    python3 scripts/demo.py --fast          the officer records the identity checks:
                                            no vision model, about a minute
    python3 scripts/demo.py --pause         wait for Enter before each step
    python3 scripts/demo.py --check-config  only check the configuration the demo needs

It calls the published ports only (the LOS, Lightning's webhooks, and Fineract
through the web app, read only), takes its keys and tokens from .env as
Compose resolves them, without printing them, and never starts or stops a
service. Every run makes new
fictitious applicants (CURPs starting with PRUE), so runs do not collide.
Exit status: 0 when every step had its expected outcome, 1 otherwise.
"""

import argparse
import base64
import datetime
import json
import random
import re
import string
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

SANDBOX = Path(__file__).resolve().parent.parent
GENERATOR = Path(__file__).resolve().parent / "demo_constancia.py"
# Published ports, by service and container port
PORTS = {"los": 8000, "lightning": 4000, "web-app": 8080}
TRIGGERS = {
    "intake": "OPENFN_TRIGGER_OPPORTUNITY_INTAKE",
    "eligibility": "OPENFN_TRIGGER_OPPORTUNITY_ELIGIBILITY",
    "identity": "OPENFN_TRIGGER_OPPORTUNITY_IDENTITY",
    "assessment": "OPENFN_TRIGGER_OPPORTUNITY_ASSESSMENT",
    "registration": "OPENFN_TRIGGER_OPPORTUNITY_REGISTRATION",
}
# Keys and tokens, as Compose resolves them from .env: (service, variable)
SECRETS = {
    "LOS_OFFICER_TOKEN": ("los", "LOS_OFFICER_TOKEN"),
    "OPENFN_INTAKE_API_KEY": ("lightning-webhook-auth", "OPENFN_INTAKE_API_KEY"),
    "OPENFN_OFFICER_API_KEY": ("lightning-webhook-auth", "OPENFN_OFFICER_API_KEY"),
    "FINERACT_ADMIN_PASSWORD": ("bootstrap", "FINERACT_PASSWORD"),
}
# The sandbox's own Ollama server; OLLAMA_URL in .env may point at another one
LOCAL_OLLAMA = "http://ollama:11434"
NAME = "Ana Demo Ficticia"
IDENTITY_TIMEOUT = 600  # the identity workflow's own limit for the vision model is 9 minutes
ASSESSMENT_TIMEOUT = 360  # the decision agent may take up to 260 s
STEPS = 8
REQUIRED = ("fineract", "web-app", "lightning", "lightning-worker", "los")


class Failed(Exception):
    """A step did not have its expected outcome, or the sandbox could not be reached."""


# Configuration ---------------------------------------------------------------

def compose(*args: str, **kwargs) -> subprocess.CompletedProcess:
    try:
        return subprocess.run(["docker", "compose", "--profile", "ollama", *args], cwd=SANDBOX, check=False,
                              capture_output=True, timeout=kwargs.pop("timeout", 120), **kwargs)
    except FileNotFoundError:
        raise Failed("docker is not installed, or not on the PATH") from None
    except subprocess.TimeoutExpired:
        raise Failed(f"docker compose {args[0]} did not finish in time") from None


def load_config() -> dict:
    """Ports, webhook triggers, settings, keys and tokens from `docker compose
    config`, so they are what the services get from .env (overrides, quoting
    and inline comments included). Raises Failed with every gap."""
    if not (SANDBOX / ".env").exists():
        raise Failed("no .env: run ./scripts/gen-secrets.sh")
    result = compose("config", "--format", "json", text=True)
    if result.returncode:
        raise Failed("docker compose config failed: " + "\n".join(result.stderr.strip().splitlines()[:3]))
    services = json.loads(result.stdout)["services"]
    problems = []
    ports = {}
    for service, target in PORTS.items():
        published = [p.get("published") for p in services.get(service, {}).get("ports", []) if int(p["target"]) == target]
        if published and published[0]:
            ports[service] = int(published[0])
        else:
            problems.append(f"no published port {target} for {service}")
    bootstrap = services.get("bootstrap", {}).get("environment") or {}
    triggers = {name: bootstrap.get(variable) for name, variable in TRIGGERS.items()}
    problems += [f"no {TRIGGERS[name]} in the bootstrap's environment" for name, value in triggers.items() if not value]
    if "mcp-decision" not in services:
        problems.append("no mcp-decision service: the constancia is drawn in its image")
    secrets = {key: (services.get(service, {}).get("environment") or {}).get(variable)
               for key, (service, variable) in SECRETS.items()}
    problems += [f"{key} has no value (run ./scripts/gen-secrets.sh)" for key, value in secrets.items() if not value]
    if problems:
        raise Failed("; ".join(problems))
    return {"ports": ports, "triggers": triggers, "secrets": secrets,
            "admin": bootstrap.get("FINERACT_USERNAME") or "mifos",
            "vision_model": bootstrap.get("OLLAMA_VISION_MODEL") or "the vision model",
            "ollama_url": bootstrap.get("OLLAMA_URL") or LOCAL_OLLAMA}


def vision_server(config: dict) -> str:
    """Where the vision model runs, for display: scheme, host and port only, as
    OLLAMA_URL may carry credentials or a key in its path or query."""
    if local_ollama(config):
        return "the sandbox's Ollama"
    url = urllib.parse.urlsplit(config["ollama_url"])
    try:
        port = f":{url.port}" if url.port else ""
    except ValueError:
        port = ""
    return f"the Ollama server at {url.scheme}://{url.hostname}{port}" if url.hostname else "the Ollama server in OLLAMA_URL"


def local_ollama(config: dict) -> bool:
    """Whether the workflows use the sandbox's own Ollama server rather than OLLAMA_URL's."""
    return urllib.parse.urlsplit(config["ollama_url"]).hostname == "ollama"


def preflight(config: dict, fast: bool, running: set) -> None:
    """The services the demo needs; the local Ollama only when the workflows use it."""
    stopped = [service for service in REQUIRED if service not in running]
    if stopped:
        raise Failed(f"not running: {', '.join(stopped)}. Start the sandbox: docker compose --profile ollama up -d --wait")
    if not fast and local_ollama(config) and "ollama" not in running:
        raise Failed("the vision model needs the ollama profile: docker compose --profile ollama up -d --wait"
                     " (or set OLLAMA_URL to your Ollama server, or use --fast)")


def running_services() -> set:
    result = compose("ps", "--status", "running", "--services", text=True)
    return set(result.stdout.split()) if result.returncode == 0 else set()


# HTTP ------------------------------------------------------------------------

def call(method: str, url: str, body=None, headers: dict | None = None, timeout: int = 60):
    data = body if isinstance(body, bytes) or body is None else json.dumps(body).encode()
    request = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            status, payload = response.status, response.read()
    except urllib.error.HTTPError as error:
        status, payload = error.code, error.read()
    except (urllib.error.URLError, TimeoutError, ConnectionError) as error:
        reason = getattr(error, "reason", error)
        raise Failed(f"{method} {url.split('?')[0]}: {reason} (is the sandbox up?)") from None
    try:
        return status, json.loads(payload) if payload else {}
    except ValueError:
        return status, {"text": payload[:300].decode(errors="replace")}


# Output ----------------------------------------------------------------------

class Screen:
    def __init__(self, pause: bool):
        self.tty = sys.stdout.isatty()
        self.pause = pause
        self.current = ""

    def bold(self, text: str) -> str:
        return f"\033[1m{text}\033[0m" if self.tty else text

    def heading(self, text: str) -> None:
        if self.pause:
            try:
                input(f"\n{' ' * 6}(Enter for the next step) ")
            except EOFError:
                self.pause = False
        self.current = text
        print("\n" + self.bold(text), flush=True)

    @staticmethod
    def line(text: str = "") -> None:
        print(" " * 6 + text, flush=True)

    def wait(self, label: str, ready, timeout: int):
        """Calls ready() every few seconds until it returns something; shows the time waited."""
        start = time.monotonic()
        if not self.tty:
            self.line(f"{label} (up to {timeout // 60} min)...")
        while True:
            value = ready()
            elapsed = time.monotonic() - start
            if value:
                if self.tty:
                    print("\r" + " " * 70 + "\r", end="", flush=True)
                return value, elapsed
            if elapsed > timeout:
                if self.tty:
                    print()
                raise Failed(f"no result after {timeout // 60} minutes")
            if self.tty:
                print(f"\r{' ' * 6}{label}... {clock(elapsed)}", end="", flush=True)
            time.sleep(3)


def clock(seconds: float) -> str:
    return f"{int(seconds) // 60}:{int(seconds) % 60:02d}"


def reasons_text(reasons) -> str:
    return "; ".join(f"{r.get('code')}: {r.get('detail')}" if isinstance(r, dict) else str(r) for r in reasons or [])


# The demo --------------------------------------------------------------------

class Demo:
    def __init__(self, config: dict, args: argparse.Namespace):
        ports = config["ports"]
        self.config = config
        self.args = args
        self.screen = Screen(args.pause)
        self.los = f"http://127.0.0.1:{ports['los']}"
        self.hooks = f"http://127.0.0.1:{ports['lightning']}/i/"
        self.fineract_api = f"http://127.0.0.1:{ports['web-app']}/fineract-provider/api/v1"
        self.web_app = f"http://localhost:{ports['web-app']}"
        self.lightning = f"http://localhost:{ports['lightning']}"
        # UTC, as the identity workflow dates its checks
        self.today = datetime.datetime.now(datetime.timezone.utc).date().isoformat()
        self.agent_running = False
        self.stamp = "".join(random.choices(string.digits, k=6)) + random.choice(string.digits + string.ascii_uppercase)

    # The applicant's data: new for every run, in the formats the workflows check
    def curp(self, last: int, stamp: str | None = None) -> str:
        stamp = stamp or self.stamp
        return f"PRUE{stamp[:6]}HDFRBA{stamp[6]}{last}"

    @staticmethod
    def phone() -> str:
        return "+52 55 6" + "".join(random.choices(string.digits, k=3)) + " " + "".join(random.choices(string.digits, k=4))

    # Calls
    def los_call(self, method: str, path: str, body=None, content_type: str = "application/json"):
        headers = {"Authorization": "Bearer " + self.config["secrets"]["LOS_OFFICER_TOKEN"],
                   "Content-Type": content_type, "X-Actor": self.args.officer}
        return call(method, self.los + path, body, headers)

    def los_ok(self, method: str, path: str, body=None, content_type: str = "application/json") -> dict:
        status, answer = self.los_call(method, path, body, content_type)
        if status not in (200, 201):
            raise Failed(f"the LOS answered {status} to {method} {path}: {answer.get('detail', answer)}")
        return answer

    def hook(self, name: str, body: dict, key: str) -> tuple[int, dict]:
        headers = {"x-api-key": self.config["secrets"][key], "Content-Type": "application/json"}
        return call("POST", self.hooks + self.config["triggers"][name], body, headers, timeout=60)

    def after_run(self, name: str, status: int, answer: dict, wait_on_timeout: bool = False) -> dict | None:
        """The result of a webhook that answers when its run has finished: Lightning
        replies {"data": ..., "meta": {"state": ...}}, and HTTP 504 with
        "error": "timeout" when the run outlasts its reply deadline (30 s), while
        the run goes on. Returns None for that timeout when the caller can wait."""
        if status == 504 and answer.get("error") == "timeout":
            if wait_on_timeout:
                return None
            raise Failed(f"the {name} run did not finish within Lightning's reply deadline: see {self.lightning} -> History")
        state = (answer.get("meta") or {}).get("state")
        if state and state != "success":
            raise Failed(f"the {name} run ended {state} in Lightning: see {self.lightning} -> History for its log")
        if not 200 <= status < 300:
            raise Failed(f"the {name} webhook answered {status}: {answer}")
        return answer.get("data") or {}

    def officer_hook(self, name: str, opportunity_id: str) -> tuple[int, dict]:
        return self.hook(name, {"opportunity_id": opportunity_id}, "OPENFN_OFFICER_API_KEY")

    def started(self, name: str, opportunity_id: str) -> dict:
        """A webhook that answers at once, with the work order it started."""
        status, answer = self.officer_hook(name, opportunity_id)
        if not 200 <= status < 300:
            raise Failed(f"the {name} webhook answered {status}: {answer}")
        return answer

    def fineract(self, path: str):
        token = base64.b64encode(f"{self.config['admin']}:{self.config['secrets']['FINERACT_ADMIN_PASSWORD']}".encode()).decode()
        status, answer = call("GET", self.fineract_api + path,
                              headers={"Authorization": "Basic " + token, "Fineract-Platform-TenantId": "default"})
        if status != 200:
            raise Failed(f"Fineract answered {status} to GET {path}")
        return answer

    def move(self, opportunity_id: str, stage: str) -> None:
        self.los_ok("POST", f"/opportunities/{opportunity_id}/stage", {"stage": stage})

    def stage(self, opportunity_id: str) -> dict:
        return self.los_ok("GET", f"/opportunities/{opportunity_id}")

    # Building blocks, each a step of the flow
    def intake(self, curp: str, external_id: str, amount: int = 5000) -> str:
        lead = {"channel": "web", "external_id": external_id, "full_name": NAME, "phone": self.phone(), "national_id": curp,
                "requested_amount": amount, "currency": "USD", "term_months": 12,
                "productive_use": "A second sewing machine for the workshop"}
        data = self.after_run("intake", *self.hook("intake", lead, "OPENFN_INTAKE_API_KEY"))
        if data.get("outcome") != "complete":
            raise Failed(f"intake answered {data.get('outcome')}: {data}")
        self.screen.line(f"complete: opportunity {external_id} in stage {data.get('stage')}")
        self.screen.line(f"{NAME}, CURP {curp}, {amount:,} USD over 12 months")
        return data["opportunityId"]

    def eligibility(self, opportunity_id: str, expected: str = "advance") -> dict:
        self.move(opportunity_id, "qualifying")
        data = self.after_run("eligibility", *self.officer_hook("eligibility", opportunity_id))
        decision = data.get("decision")
        self.screen.line(f"{decision} -> {data.get('stage')}" + (f" ({reasons_text(data.get('reasons'))})" if data.get("reasons") else ""))
        if decision != expected:
            raise Failed(f"eligibility decided {decision}, expected {expected}: {data}")
        return data

    def draw(self, curp: str) -> bytes:
        result = compose("run", "--rm", "--no-deps", "-T", "--entrypoint", "python", "mcp-decision", "-", curp, NAME,
                         input=GENERATOR.read_bytes(), timeout=600)
        if b"No module named 'PIL'" in result.stderr:
            raise Failed("the MCP server's image has no Pillow; rebuild it: docker compose --profile ollama build mcp-decision")
        if result.returncode or not result.stdout.startswith(b"\x89PNG\r\n\x1a\n"):
            raise Failed("drawing the constancia failed: " + result.stderr.decode(errors="replace").strip()[-300:])
        return result.stdout

    def upload(self, opportunity_id: str, image: bytes) -> str:
        document = self.los_ok("POST", f"/opportunities/{opportunity_id}/documents/curp", image, "image/png")
        self.screen.line(f"uploaded the CURP constancia ({len(image) // 1024} KB, drawn for this run, marked fictitious)")
        return document["id"]

    def record_check(self, opportunity_id: str, document_id: str, read_curp: str, result: str, reasons: list) -> dict:
        """The officer records the identity check by hand: --fast, or a scenario past identity."""
        opportunity = self.stage(opportunity_id)
        body = {"result": result, "reasons": reasons,
                "extracted": {"curpClave": read_curp, "fullName": NAME.upper(), "issueDate": self.today, "civilRegistryVerified": True},
                "compared": {"national_id": opportunity["national_id"], "full_name": opportunity["full_name"]}}
        self.los_ok("POST", f"/opportunities/{opportunity_id}/documents/{document_id}/check", body)
        return {"check_result": result, "check_reasons": reasons, "extracted": body["extracted"]}

    def vision_check(self, opportunity_id: str, document_id: str) -> dict:
        answer = self.started("identity", opportunity_id)
        self.screen.line(f"work order {answer.get('work_order_id', '?')}: see Lightning ({self.lightning}) -> History")

        def checked():
            documents = self.los_ok("GET", f"/opportunities/{opportunity_id}/documents")["documents"]
            document = next((d for d in documents if d["id"] == document_id), None)
            return document if document and document.get("check_result") else None
        document, elapsed = self.screen.wait(f"{self.config['vision_model']} is reading it", checked, IDENTITY_TIMEOUT)
        self.screen.line(f"read in {clock(elapsed)}")
        return document

    def show_check(self, document: dict) -> None:
        extracted = document.get("extracted") or {}
        self.screen.line(f"{document['check_result']}: read CURP {extracted.get('curpClave')}, name {extracted.get('fullName')}")
        if document.get("check_reasons"):
            self.screen.line("reasons: " + reasons_text(document["check_reasons"]))

    def assessment(self, opportunity_id: str, expected: str) -> dict:
        before = {a["id"] for a in self.los_ok("GET", f"/opportunities/{opportunity_id}/assessments")["assessments"]}
        self.started("assessment", opportunity_id)

        def assessed():
            listed = self.los_ok("GET", f"/opportunities/{opportunity_id}/assessments")["assessments"]
            return next((a for a in listed if a["id"] not in before), None)
        assessment, elapsed = self.screen.wait("assessing", assessed, ASSESSMENT_TIMEOUT)
        bureau, agent = assessment.get("bureau") or {}, assessment.get("agent") or {}
        if bureau:
            self.screen.line(f"bureau (simulated): score {bureau.get('score')}, {bureau.get('delinquencies')} delinquent accounts")
        recommendation = assessment["recommendation"]
        self.screen.line(f"{recommendation}" + (f" ({reasons_text(assessment.get('reasons'))})" if assessment.get("reasons") else "")
                         + f", in {clock(elapsed)}")
        if agent.get("source") == "agent-decision":
            tightened = any(r.get("code") == "decision_agent" for r in assessment.get("reasons") or [])
            self.screen.line(f"the decision agent ({agent.get('model')}) recommended {agent.get('recommendation')}"
                             + (": stricter than the rules, so it counts" if tightened else "; the rules' result stands"))
        elif agent.get("source") == "rules-fallback":
            self.screen.line("the rules decided: the decision agent is not running" if not self.agent_running
                             else f"the rules decided; the agent's answer did not count ({agent.get('error')})")
        if recommendation != expected:
            raise Failed(f"the assessment recommended {recommendation}, expected {expected}")
        return assessment

    def paused(self, opportunity_id: str, reason_code: str) -> None:
        opportunity = self.stage(opportunity_id)
        self.screen.line(f"stage {opportunity['stage']}, reason code {opportunity.get('reason_code')}")
        if opportunity["stage"] != "paused" or opportunity.get("reason_code") != reason_code:
            raise Failed(f"expected paused with {reason_code}, the opportunity is {opportunity['stage']} ({opportunity.get('reason_code')})")

    # The main run
    def main_run(self) -> dict:
        screen, officer = self.screen, self.args.officer
        curp, external_id = self.curp(1), f"demo-{self.stamp}-main"
        n = iter(range(1, STEPS + 1))

        screen.heading(f"[{next(n)}/{STEPS}] A lead arrives from the web channel (intake)")
        opportunity_id = self.intake(curp, external_id)

        screen.heading(f"[{next(n)}/{STEPS}] Officer {officer} moves it to qualifying; eligibility checks the product and Fineract")
        self.eligibility(opportunity_id)

        if self.args.fast:
            screen.heading(f"[{next(n)}/{STEPS}] Officer {officer} uploads the CURP and records the check (--fast: no vision model)")
            document_id = self.upload(opportunity_id, self.draw(curp))
            document = self.record_check(opportunity_id, document_id, curp, "valid", [])
        else:
            screen.heading(f"[{next(n)}/{STEPS}] Officer {officer} uploads the CURP; the vision model reads it")
            document_id = self.upload(opportunity_id, self.draw(curp))
            document = self.vision_check(opportunity_id, document_id)
        self.show_check(document)
        if document["check_result"] != "valid":
            raise Failed(f"the identity check found {document['check_result']}, expected valid")
        screen.line(f"stage {self.stage(opportunity_id)['stage']}")

        screen.heading(f"[{next(n)}/{STEPS}] Officer {officer} records the client's bureau consent")
        self.los_ok("POST", f"/opportunities/{opportunity_id}/consents/bureau")
        screen.line("recorded with the LOS's timestamp: nothing is queried without it")

        screen.heading(f"[{next(n)}/{STEPS}] Assessment: the bureau, the rules, and the decision agent")
        self.assessment(opportunity_id, "approve")
        screen.line("a proposal only: a person confirms it")

        screen.heading(f"[{next(n)}/{STEPS}] Officer {officer} confirms the pre-approval and qualifies the opportunity")
        self.move(opportunity_id, "pre_approved")
        self.move(opportunity_id, "qualified")
        screen.line("pre_approved -> qualified")

        screen.heading(f"[{next(n)}/{STEPS}] Registration in Fineract, as agent-registration (which may not approve)")
        data = self.after_run("registration", *self.officer_hook("registration", opportunity_id), wait_on_timeout=True)
        if data is None:  # Lightning stopped waiting for the run, which goes on: follow it in the LOS
            screen.line("the webhook stopped waiting after 30 s; following the run in the LOS")
            screen.wait("registering", lambda: self.stage(opportunity_id)["stage"] == "registered", 120)
            opportunity = self.stage(opportunity_id)
            data = {"decision": "registered", "clientId": opportunity.get("fineract_client_id"), "loanId": opportunity.get("fineract_loan_id")}
        screen.line(f"{data['decision']}: client {data.get('clientId')}, loan application {data.get('loanId')}")
        if data["decision"] != "registered":
            raise Failed(f"registration decided {data['decision']}: {reasons_text(data.get('reasons'))}")

        screen.heading(f"[{next(n)}/{STEPS}] In Fineract, what Credit sees")
        client = self.fineract(f"/clients/{data['clientId']}")
        screen.line(f"client {client.get('displayName')} ({client.get('status', {}).get('value')}), external ID {client.get('externalId')}")
        for attached in self.fineract(f"/clients/{data['clientId']}/documents"):
            screen.line(f"document: {attached.get('name')} ({attached.get('fileName')})")
        loan = self.fineract(f"/loans/{data['loanId']}")
        screen.line(f"loan {loan.get('accountNo')}: {loan.get('status', {}).get('value')}, "
                    f"{float(loan.get('principal') or 0):,.2f} {loan.get('currency', {}).get('code')}")
        for note in self.fineract(f"/loans/{data['loanId']}/notes"):
            screen.line("note for Credit:")
            for text in str(note.get("note", "")).splitlines():
                screen.line("  " + text)
        screen.line(f"open: {self.web_app}/#/clients/{data['clientId']}/general")
        screen.line("Credit approves or rejects the application there (a person, maker-checker)")
        return data

    # The scenarios the rules stop
    def scenarios(self) -> None:
        screen, officer = self.screen, self.args.officer

        screen.heading("[Scenario 1/3] An amount outside the loan product (250,000 USD; the product allows up to 100,000)")
        opportunity_id = self.intake(self.curp(2), f"demo-{self.stamp}-amount", amount=250000)
        self.eligibility(opportunity_id, expected="not_eligible")
        self.paused(opportunity_id, "amount_out_of_range")

        screen.heading("[Scenario 2/3] A constancia with someone else's CURP")
        curp = self.curp(3)
        other = self.curp(3, stamp="".join(random.choices(string.digits, k=6)) + self.stamp[6])
        opportunity_id = self.intake(curp, f"demo-{self.stamp}-mismatch")
        self.eligibility(opportunity_id)
        document_id = self.upload(opportunity_id, self.draw(other))
        screen.line(f"the image shows CURP {other}; the applicant's is {curp}")
        if self.args.fast:
            screen.line(f"--fast: officer {officer} records the mismatch, without the vision model")
            document = self.record_check(opportunity_id, document_id, other, "document_mismatch",
                                         [{"code": "curp_mismatch", "detail": f"the document's CURP {other} is not the opportunity's {curp}"}])
        else:
            document = self.vision_check(opportunity_id, document_id)
        self.show_check(document)
        if document["check_result"] != "document_mismatch":
            raise Failed(f"the identity check found {document['check_result']}, expected document_mismatch")
        self.paused(opportunity_id, "document_mismatch")

        screen.heading("[Scenario 3/3] A bureau score below 550 (in the sandbox, a CURP ending in 8)")
        curp = self.curp(8)
        opportunity_id = self.intake(curp, f"demo-{self.stamp}-bureau")
        self.eligibility(opportunity_id)
        document_id = self.upload(opportunity_id, self.draw(curp))
        self.record_check(opportunity_id, document_id, curp, "valid", [])
        screen.line(f"officer {officer} records the identity check as valid, without the vision model (the main run showed it)")
        self.los_ok("POST", f"/opportunities/{opportunity_id}/consents/bureau")
        screen.line(f"officer {officer} records the bureau consent")
        self.assessment(opportunity_id, "reject")
        self.paused(opportunity_id, "bureau_score_low")


def officer_name(value: str) -> str:
    """The LOS's rule for X-Actor (los/auth.py), checked before the demo creates anything."""
    if not re.fullmatch(r"[A-Za-z0-9 ._@:-]{1,64}", value):
        raise argparse.ArgumentTypeError("1 to 64 letters, digits, spaces or . _ @ : -")
    return value


def main() -> int:
    parser = argparse.ArgumentParser(description="The commercial flow end to end on a running sandbox (README 4.9).")
    parser.add_argument("--scenarios", action="store_true", help="after the main run, three cases the rules stop")
    parser.add_argument("--fast", action="store_true", help="the officer records the identity checks: no vision model")
    parser.add_argument("--pause", action="store_true", help="wait for Enter before each step")
    parser.add_argument("--officer", default="maria", type=officer_name,
                        help="the officer's name in the audit trail (default: maria)")
    parser.add_argument("--check-config", action="store_true", help="only check the configuration the demo needs")
    args = parser.parse_args()
    demo = None
    try:
        config = load_config()
        if args.check_config:
            print("ports: " + ", ".join(f"{service} {port}" for service, port in config["ports"].items()))
            print("webhook triggers: " + ", ".join(config["triggers"]))
            print("keys and tokens, as Compose resolves them from .env: " + ", ".join(SECRETS))
            print("vision model: " + config["vision_model"] + " on " + vision_server(config))
            print("configuration ok")
            return 0
        demo = Demo(config, args)
        running = running_services()
        demo.agent_running = "agent-decision" in running
        preflight(config, args.fast, running)
        print(demo.screen.bold(f"Commercial flow demo, run {demo.stamp}: fictitious applicant {NAME}"))
        print(f"Officer: {args.officer}. Identity checks: "
              + ("recorded by the officer (--fast)" if args.fast else
                 f"{config['vision_model']} on " + vision_server(config)
                 + ", a few minutes on a CPU"))
        if "agent-decision" in running:
            print("The decision agent is running: each assessment waits for it (1 to 3 minutes on a CPU).\n"
                  "To let the rules decide at once: docker compose stop agent-decision")
        else:
            print("The decision agent is not running: the rules decide each assessment.")
        demo.main_run()
        if args.scenarios:
            demo.scenarios()
        print("\n" + demo.screen.bold("Done: every step had its expected outcome."))
        return 0
    except Failed as failure:
        where = f" at {demo.screen.current}" if demo and demo.screen.current else ""
        print(f"\nFAILED{where}: {failure}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\ninterrupted: the opportunity stays where it is in the LOS", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
