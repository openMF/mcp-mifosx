#!/usr/bin/env python3
# Copyright since 2025 Mifos Initiative
# SPDX-License-Identifier: MPL-2.0

"""
Validate the Fineract Agent Skill metadata and structure.

Checks:
1. SKILL.md frontmatter (name format, description length, triggers)
2. Directory structure (flat hierarchy, no human docs)
3. SKILL.md line count (< 500 lines)
4. Tool registry integrity

Usage:
    python3 scripts/validate-skill.py
"""

import os
import re
import sys

SKILL_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SKILL_MD = os.path.join(SKILL_DIR, "SKILL.md")

errors = []
warnings = []


def check_skill_md_exists():
    if not os.path.isfile(SKILL_MD):
        errors.append("STRUCTURE ERROR: SKILL.md not found in the skill root directory.")
        return False
    return True


def parse_frontmatter(content: str) -> dict:
    """Extract YAML frontmatter from SKILL.md."""
    match = re.match(r"^---\s*\n(.*?)\n---", content, re.DOTALL)
    if not match:
        errors.append("METADATA ERROR: No YAML frontmatter found. Expected '---' delimiters.")
        return {}
    fm = {}
    for line in match.group(1).splitlines():
        if ":" in line:
            key, _, value = line.partition(":")
            fm[key.strip()] = value.strip()
    return fm


def validate_name(name: str):
    if not name:
        errors.append("NAME ERROR: 'name' field is empty.")
        return
    if not (1 <= len(name) <= 64):
        errors.append(f"NAME ERROR: '{name}' is {len(name)} characters. Must be 1-64.")
    if not re.match(r"^[a-z0-9]+(-[a-z0-9]+)*$", name):
        errors.append(
            f"NAME ERROR: '{name}' contains invalid characters. "
            "Use only lowercase letters, numbers, and single hyphens."
        )


def validate_description(desc: str):
    if not desc:
        errors.append("DESCRIPTION ERROR: 'description' field is empty.")
        return
    if len(desc) > 1024:
        errors.append(
            f"DESCRIPTION ERROR: Description is {len(desc)} characters. Must be ≤1024."
        )
    # Check for trigger keywords
    lower = desc.lower()
    if "use when" not in lower and "use for" not in lower:
        warnings.append("STYLE WARNING: Description lacks positive triggers ('Use when...').")
    if "don't use" not in lower and "do not use" not in lower:
        warnings.append("STYLE WARNING: Description lacks negative triggers ('Don't use for...').")
    # Check for first/second person
    if re.search(r"\b(I|me|my|you|your)\b", desc, re.IGNORECASE):
        errors.append("STYLE ERROR: Description uses first/second person. Use third-person tone.")


def validate_line_count(content: str):
    lines = content.splitlines()
    if len(lines) > 500:
        errors.append(
            f"SIZE ERROR: SKILL.md is {len(lines)} lines. Must be under 500. "
            "Move bulky content to references/."
        )


def validate_directory_structure():
    allowed_dirs = {"scripts", "references", "assets", "fineract_skill", "tests",
                    ".pytest_cache", "__pycache__"}
    for item in os.listdir(SKILL_DIR):
        full = os.path.join(SKILL_DIR, item)
        if os.path.isdir(full):
            basename = os.path.basename(item)
            if basename.startswith("."):
                continue
            if basename not in allowed_dirs:
                warnings.append(f"STRUCTURE WARNING: Unexpected directory '{item}'.")

    # Check flat hierarchy in standard dirs
    for subdir in ["scripts", "references", "assets"]:
        subdir_path = os.path.join(SKILL_DIR, subdir)
        if os.path.isdir(subdir_path):
            for item in os.listdir(subdir_path):
                nested = os.path.join(subdir_path, item)
                if os.path.isdir(nested) and not item.startswith(".") and item != "__pycache__":
                    errors.append(
                        f"STRUCTURE ERROR: '{subdir}/{item}' is a subdirectory. "
                        "Files must be flat (one level deep)."
                    )


def validate_no_human_docs():
    human_docs = ["CHANGELOG.md", "INSTALLATION.md", "INSTALLATION_GUIDE.md"]
    for doc in human_docs:
        if os.path.isfile(os.path.join(SKILL_DIR, doc)):
            errors.append(f"STRUCTURE ERROR: Found human-centric doc '{doc}'. Remove it.")


def validate_tool_registry():
    try:
        sys.path.insert(0, SKILL_DIR)
        from fineract_skill.tools import TOOL_REGISTRY, get_mcp_tools, get_openai_tools

        if len(TOOL_REGISTRY) == 0:
            errors.append("REGISTRY ERROR: Tool registry is empty.")
            return

        # Check all tools have required fields
        names = set()
        for tool in TOOL_REGISTRY:
            if not tool.name:
                errors.append("REGISTRY ERROR: Tool missing 'name'.")
            if not tool.description:
                errors.append(f"REGISTRY ERROR: Tool '{tool.name}' missing 'description'.")
            if not callable(tool.handler):
                errors.append(f"REGISTRY ERROR: Tool '{tool.name}' handler is not callable.")
            if tool.name in names:
                errors.append(f"REGISTRY ERROR: Duplicate tool name '{tool.name}'.")
            names.add(tool.name)

        # Check schema exports — include opt-in tools, since this validates
        # that every registry tool converts, not what defaults expose
        openai = get_openai_tools(allow_opt_in=True)
        mcp = get_mcp_tools(allow_opt_in=True)
        if len(openai) != len(TOOL_REGISTRY):
            errors.append("REGISTRY ERROR: OpenAI schema count mismatch.")
        if len(mcp) != len(TOOL_REGISTRY):
            errors.append("REGISTRY ERROR: MCP schema count mismatch.")

        print(f"  ✓ Tool registry: {len(TOOL_REGISTRY)} tools, {len(names)} unique names")
        print(f"  ✓ OpenAI schemas: {len(openai)}")
        print(f"  ✓ MCP schemas: {len(mcp)}")

    except ImportError as e:
        errors.append(
            f"REGISTRY ERROR: Cannot import tool registry: {e}. "
            "Run 'pip install -e .[dev]' first."
        )


def main():
    print("=" * 60)
    print("Fineract Agent Skill — Metadata & Structure Validation")
    print("=" * 60)

    if not check_skill_md_exists():
        print_results()
        return

    with open(SKILL_MD, "r") as f:
        content = f.read()

    fm = parse_frontmatter(content)

    print("\n[1/6] Validating metadata...")
    validate_name(fm.get("name", ""))
    validate_description(fm.get("description", ""))

    print("[2/6] Validating SKILL.md length...")
    validate_line_count(content)
    line_count = len(content.splitlines())
    print(f"  ✓ {line_count} lines (limit: 500)")

    print("[3/6] Validating directory structure...")
    validate_directory_structure()

    print("[4/6] Checking for human-centric docs...")
    validate_no_human_docs()

    print("[5/6] Validating tool registry...")
    validate_tool_registry()

    print("[6/6] Cross-referencing checklist...")
    checklist = os.path.join(SKILL_DIR, "references", "checklist.md")
    if os.path.isfile(checklist):
        print("  ✓ Checklist found at references/checklist.md")
    else:
        warnings.append("STRUCTURE WARNING: references/checklist.md not found.")

    print_results()


def print_results():
    print("\n" + "=" * 60)
    if errors:
        print(f"FAILED — {len(errors)} error(s), {len(warnings)} warning(s)\n")
        for e in errors:
            print(f"  ✗ {e}", file=sys.stderr)
        for w in warnings:
            print(f"  ⚠ {w}")
        sys.exit(1)
    else:
        if warnings:
            print(f"PASSED with {len(warnings)} warning(s)\n")
            for w in warnings:
                print(f"  ⚠ {w}")
        else:
            print("PASSED — All checks successful ✓")
        sys.exit(0)


if __name__ == "__main__":
    main()
