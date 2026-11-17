#!/usr/bin/env python3
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Checks that the adaptors built into the worker image (worker/package.json)
are exactly the ones the OpenFn jobs use (project.yaml). A job's adaptor that
is not in the image makes the worker try to install it from npm, which it has
no route to. Run from agentic-mifos-sandbox:

    python3 scripts/check-worker-adaptors.py ../agentic-banking-openfn/openfn/project.yaml worker/package.json

Prints one line per difference and exits 1 if there is any.
"""

import json
import re
import sys

# The form the CI job "OpenFn adaptors pinned" requires of every job.
ADAPTOR = re.compile(r'^\s*adaptor: "(@openfn/language-[a-z0-9-]+)@(\d+\.\d+\.\d+)"', re.MULTILINE)


def problems(spec: str, package: dict) -> list[str]:
    used = {f"{name}@{version}" for name, version in ADAPTOR.findall(spec)}
    built = {f"{name}@{version}" for name, version in (package.get("dependencies") or {}).items()}
    found = [f"{a}: a job uses it, but worker/package.json does not have it" for a in sorted(used - built)]
    found += [f"{a}: in worker/package.json, but no job uses it" for a in sorted(built - used)]
    return found


def main() -> int:
    with open(sys.argv[1]) as spec, open(sys.argv[2]) as package:
        found = problems(spec.read(), json.load(package))
    for line in found:
        print(line)
    print(f"{len(found)} problem(s)", file=sys.stderr)
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
