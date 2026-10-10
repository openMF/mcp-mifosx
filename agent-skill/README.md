# Agent Skills for Mifos X

Agent Skills for Pi Agent, Hermes Agent, Prime-Agent and any other harness that supports the
[Agent Skills specification](https://agentskills.io/specification) (Claude Code, Codex CLI, …).

| Folder | Skill | Purpose |
|---|---|---|
| `mifos-agent/` | Fineract tool library | Python tools for the Apache Fineract REST API (AI-259) |
| `mifos-x-ai-agent-customer-service-advisor/` | `mifos-x-ai-agent-customer-service-advisor` | First point of contact: greets, profiles and routes customers to Investment, Credit or Operations (AI-261) |

## Customer Service Advisor skill

The advisor receives individual or legal-entity customers, identifies their need, asks at most two
profiling questions per reply, applies the eight eligibility filters from AI-261, and routes the case to
the Investment Commercial Advisor, the Credit Analyst or Operations. It never quotes rates, gives advice,
promises approval, accepts funds or formalizes anything. When the customer agrees to the transfer it
outputs a handoff JSON that conforms to `assets/handoff.schema.json`, inside a fenced block labelled
`handoff`. The block is meant for the next specialist: the harness or gateway should intercept it, route it,
and not show it in the customer chat.

```
mifos-x-ai-agent-customer-service-advisor/
├── SKILL.md        # rules, routing and filter tables, workflow, handoff template
├── references/     # ticket detail: routing matrix, filters, profiling fields, script, can/cannot, placeholders, read-only tools
└── assets/         # handoff JSON Schema, one example per route, institution profile template
```

### Install

Pi (user-level):

```bash
ln -s "$PWD/agent-skill/mifos-x-ai-agent-customer-service-advisor" ~/.pi/agent/skills/
```

Claude Code: link the folder into `~/.claude/skills/`. Codex CLI: `~/.codex/skills/`.

Fill in `assets/institution-profile.template.json` (institution name, regulators, currency) for your
deployment; until then the advisor refers to "our institution".

### Use

Load the skill explicitly for every customer conversation. When the model chooses skills on its own it
may skip this one for off-topic first messages.

```text
/skill:mifos-x-ai-agent-customer-service-advisor hi, I need a loan for my shop
```

Optional: connect the Mifos X MCP server with read-only tools only (see
`references/fineract-tools.md`) so the advisor can detect an existing customer or active process.

### Validate and test

```bash
python3 agent-skill/mifos-agent/scripts/validate-skill.py agent-skill/mifos-x-ai-agent-customer-service-advisor
pip install pytest jsonschema
pytest agent-skill/tests/test_customer_service_advisor.py -v
```

The tests check the skill format, that every referenced file exists, that the handoff examples match
the schema, and that the schema rejects ID or account numbers in free-text fields.
