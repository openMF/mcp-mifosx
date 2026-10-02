# Agent Skill Validation Checklist

Use this checklist to perform a final audit of the Fineract Agent Skill before deployment. Every item must be marked as "Pass."

## 1. Metadata & Discovery
- [ ] **Naming:** The `name` field is 1-64 characters and contains only lowercase letters, numbers, and single hyphens.
- [ ] **Directory Match:** The `name` field matches the parent directory name (`fineract-agent-skill`).
- [ ] **Description Length:** The description is under 1,024 characters.
- [ ] **Trigger Optimization:** The description includes use cases ("Use when...") and negative triggers ("Don't use for...").
- [ ] **Third-Person Tone:** The description avoids "I", "me", "my", "you", or "your".

## 2. File Structure & Paths
- [ ] **Flat Hierarchy:** All files in `scripts/`, `references/`, and `assets/` are exactly one level deep.
- [ ] **Standard Folders:** Only uses `scripts/`, `references/`, and `assets/`.
- [ ] **Forward Slashes:** All file paths in `SKILL.md` use forward slashes (`/`).

## 3. Progressive Disclosure
- [ ] **SKILL.md Length:** Under 500 lines.
- [ ] **JiT Loading:** Bulky context (tool catalog, endpoint tables) is in `references/`, not inlined.
- [ ] **Explicit Read Commands:** SKILL.md directs the agent to read specific reference files when needed.

## 4. Procedural Quality
- [ ] **Third-Person Imperative:** Instructions use "Extract the...", "Run the...", not "You should..." or "I will...".
- [ ] **Step-by-Step Numbering:** Workflow is a strict chronological sequence.
- [ ] **Concrete Templates:** Output schemas are in `assets/`, not described in prose.
- [ ] **Consistent Terminology:** Domain terms (client, loan, savings, group) are used consistently.

## 5. Error Handling
- [ ] **Error Section:** SKILL.md includes a dedicated Error Handling section.
- [ ] **Descriptive Messages:** Scripts output human-readable error messages to stderr.
- [ ] **Self-Correction Paths:** Each error scenario includes a corrective action.

## 6. Functionality
- [ ] **Client Ping:** `client.ping()` returns `{"status": "ok"}` against the sandbox.
- [ ] **Tool Registry:** `list_tools()` returns 55+ tools.
- [ ] **Schema Export:** `get_openai_tools()` and `get_mcp_tools()` return valid schemas.
- [ ] **Tests Pass:** `pytest tests/ -v` passes all 28 tests.
