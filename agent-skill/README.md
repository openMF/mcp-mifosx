# Agent Skills for Mifos X

Agent Skills for Pi Agent, Hermes Agent, Prime-Agent and any other harness that supports the
[Agent Skills specification](https://agentskills.io/specification) (Claude Code, Codex CLI, …).

| Folder | Skill | Purpose |
|---|---|---|
| `mifos-agent/` | Fineract tool library | Python tools for the Apache Fineract REST API (AI-259) |
| `mifos-x-ai-agent-customer-service-advisor/` | `mifos-x-ai-agent-customer-service-advisor` | First point of contact: greets, profiles and routes customers to Investment, Credit or Operations (AI-261) |
| `mifos-x-ai-agent-investment-sales-advisor/` | `mifos-x-ai-agent-investment-sales-advisor` | Profiles investors, simulates the Promissory Note and CEDE +D60, hands the case to the Commercial Executive (AI-262) |

## Install as a Pi package

The repository is a [Pi package](https://pi.dev/docs/latest/packages): the root `package.json` lists the
skills under `pi.skills`, so one command installs all of them from npm or from Git:

```bash
pi install npm:mifos-x-agent-skills                  # from npm (published from GitHub Releases)
pi install git:github.com/openMF/mcp-mifosx          # latest main
pi install git:github.com/openMF/mcp-mifosx@<tag>    # pinned to a tag or commit
pi list                                              # shows the package and its skills
```

The npm package contains only the skill folders (see `files` in `package.json`; new `mifos-x-ai-agent-*`
skills are included automatically). It is published by `.github/workflows/npm-publish.yml` when a GitHub
Release is created.

Use `pi install -l …` to install for the current project only. To uninstall, run
`pi remove git:github.com/openMF/mcp-mifosx` (add `-l` for a project-only install). Other harnesses that use the same
SKILL.md format (Claude Code, Codex CLI, Hermes Agent, OpenClaw, …) can copy or link the individual skill folders instead.

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

## Investment Sales Advisor skill

The advisor receives investors (directly or from the Customer Service Advisor's handoff), profiles them,
chooses between Total Investment (Promissory Note) and Total Investment +D60 (CEDE), and simulates gross
rate, estimated ISR, net interest, net rate and total at maturity. Every figure comes from
`scripts/simulate_investment.py`, which implements the Investment Simulator V12 formulas with the rate and
tax tables in `assets/simulator-v12.json`; the model never calculates. It applies the eight AI-262 filters,
explains the account-opening documents, and, after the customer agrees, outputs a handover JSON for the
Commercial Executive (`assets/handover.schema.json`) inside a fenced block labelled `handover`, which the
harness should intercept and not show in the customer chat. It never negotiates, formalizes or accepts funds.

```
mifos-x-ai-agent-investment-sales-advisor/
├── SKILL.md        # rules, fixed phrases, filters, workflow, reply format, documents, handover template
├── scripts/        # simulate_investment.py: Simulator V12 formulas, stdlib only, JSON output
├── assets/         # rate and tax tables, handover JSON Schema and examples, institution profile template
└── references/     # simulation rules and decisions, placeholders
```

### Install and use

Same as the Customer Service Advisor (link the folder into `~/.pi/agent/skills/`, load it explicitly):

```text
/skill:mifos-x-ai-agent-investment-sales-advisor I want to invest 500,000 pesos for six months
```

The agent needs `python3` and a shell tool to run the simulator. In a customer-facing deployment, restrict
the shell to this one command, or wrap `simulate()` from the script as a single tool (for example an MCP
tool), since the model builds the command from the conversation. The script only accepts digits and ISO
dates. Without the tool the advisor escalates instead of simulating.

### Keeping the rates current

Rates, tax years and the 14% campaign switch (`campaign.active`) live only in `assets/simulator-v12.json`.
Update that file when the simulator changes and run the tests. The opening year must be present in
`tax_years`, otherwise the simulator escalates. The $1,000 minimum is marked "see pending items" in the
ticket; confirm it before production. Decisions on fiscal-year crossing, cumulative ISR, rounding and
brackets are in `references/simulation-rules.md`.

### Validate and test

```bash
python3 agent-skill/mifos-agent/scripts/validate-skill.py agent-skill/mifos-x-ai-agent-investment-sales-advisor
pytest agent-skill/tests/test_investment_sales_advisor.py -v
```

The tests check the skill format and size, every rate and tax cell against the ticket, the ticket's worked
examples to the cent, every filter and boundary, the command-line input validation, and the handover schema
and privacy guard.
