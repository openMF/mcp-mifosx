#!/usr/bin/env python3
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Checks that every service in docker-compose.yml keeps the sandbox's hardening.

Reads the rendered compose configuration on stdin (run from agentic-mifos-sandbox,
with a .env from scripts/gen-secrets.sh):

    docker compose --profile ollama --profile agents config --format json \\
      | python3 scripts/check-hardening.py

Prints one line per problem and exits 1 if there is any.
"""

import json
import sys
from fnmatch import fnmatchcase

SCRIPT = "scripts/check-hardening.py"

# One-shot jobs that run as root to hand a volume to an unprivileged user. They
# get no network and only the capabilities listed here.
ROOT_JOBS = {
    "state-init": {"CHOWN", "FOWNER"},
    "ollama-init": {"CHOWN"},
}

# Internal networks and the services allowed on them (`*` matches any
# characters). Each agent adds no entry here: agent-<name> runs with
# mcp-<name> on a network named agent-<name> (see allowed_on), the agent
# reaches Ollama on agents-llm and its MCP server reaches Fineract on core.
INTERNAL_NETWORKS = {
    "fineract-db": {"fineract-db", "fineract"},
    "core": {"fineract", "web-app", "lightning-worker", "bootstrap", "mcp-*"},
    "lightning-db": {"lightning-db", "lightning", "lightning-setup"},
    "openfn": {"lightning", "lightning-worker", "bootstrap", "gateway"},
    "llm": {"ollama", "ollama-pull", "lightning-worker"},
    "agents-llm": {"ollama", "agent-*"},
    "los": {"los-db", "los"},
}

# The only networks with a route outside Docker, and the services allowed on
# them: one per published port, and the services that download from the internet.
OUTER_NETWORKS = {
    "web-app-edge": {"web-app"},
    "lightning-edge": {"lightning", "lightning-setup"},
    "gateway-edge": {"gateway"},
    "los-edge": {"los"},
    "worker-egress": {"lightning-worker"},
    "llm-egress": {"ollama"},
}


def capability(name: str) -> str:
    return name.upper().removeprefix("CAP_")


def allowed_on(network: str, internal: bool) -> set[str] | None:
    """The services allowed on a network, or None if it is not listed."""
    if not internal:
        return OUTER_NETWORKS.get(network)
    if network in INTERNAL_NETWORKS:
        return INTERNAL_NETWORKS[network]
    if network.startswith("agent-"):
        return {network, "mcp-" + network.removeprefix("agent-")}
    return None


def problems(config: dict) -> list[str]:
    found = []
    networks = config.get("networks") or {}
    for name, service in sorted(config["services"].items()):
        def fail(rule: str) -> None:
            found.append(f"{name}: {rule}")

        if service.get("read_only") is not True:
            fail("needs read_only: true (writable paths as tmpfs or volumes)")
        if [capability(c) for c in service.get("cap_drop") or []] != ["ALL"]:
            fail("needs cap_drop: [ALL]")
        added = {capability(c) for c in service.get("cap_add") or []}
        if added - ROOT_JOBS.get(name, set()):
            fail(f"adds capabilities {sorted(added - ROOT_JOBS.get(name, set()))} it is not allowed")
        if "no-new-privileges:true" not in (service.get("security_opt") or []):
            fail("needs security_opt: [no-new-privileges:true]")
        if not service.get("mem_limit"):
            fail("needs mem_limit")
        if not service.get("pids_limit"):
            fail("needs pids_limit")
        if service.get("privileged"):
            fail("must not be privileged (it would get every capability back)")

        user = str(service.get("user") or "").split(":")[0]
        if name in ROOT_JOBS:
            if service.get("network_mode") != "none":
                fail("runs as root, so it needs network_mode: none")
        else:
            if user in ("", "root", "0"):
                fail("needs an explicit, non-root user")
            # host or service:<name> would bypass the networks checked below.
            if service.get("network_mode"):
                fail(f"must not set network_mode ({service['network_mode']}); use the networks in {SCRIPT}")

        for network in service.get("networks") or {}:
            internal = bool((networks.get(network) or {}).get("internal"))
            allowed = allowed_on(network, internal)
            if allowed is None:
                kind = "internal network" if internal else "network with a route out,"
                fail(f"is on {kind} {network}, which is not listed in {SCRIPT}")
            elif not any(fnmatchcase(name, pattern) for pattern in allowed):
                fail(f"is on {network}, which only {', '.join(sorted(allowed))} may use")

        for port in service.get("ports") or []:
            if port.get("host_ip") != "127.0.0.1":
                fail(f"publishes port {port.get('published')} on {port.get('host_ip') or 'every interface'}; bind it to 127.0.0.1")
    return found


def main() -> int:
    config = json.load(sys.stdin)
    found = problems(config)
    for line in found:
        print(line)
    print(f"{len(config['services'])} services checked, {len(found)} problem(s)", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
