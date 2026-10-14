# Placeholders

The ticket writes institution details as placeholders. Fill them in `assets/institution-profile.template.json` for each deployment (or have the harness inject them into the system prompt).

| Placeholder | Profile key | Used in |
|---|---|---|
| `[FINANCIAL INSTITUTION NAME]` | `financial_institution_name` | Greeting, regulator mention, documents ("register as a client of …") |
| `[REGULATOR 1]`, `[REGULATOR 2]` | `regulator_1`, `regulator_2` | Rule 9: "… is a regulated entity, supervised by [REGULATOR 1] and [REGULATOR 2]" |
| `[LOCALITY/STATE/COUNTRY]` | `location` | Only when the customer asks where the institution operates |
| — | `complaints_authority` | Complaints and the data phrase (Condusef) |
| — | `currency` | Always MXN for Simulator V12 |

Rules:

- Never show square brackets to the customer.
- If a value is not configured, say "our institution" and "a regulated entity" without naming regulators. Never guess a name.
- The minimum amount ($1,000), rates, terms and tax parameters are not placeholders: they live in `assets/simulator-v12.json`. The ticket marks the $1,000 minimum as "see pending items"; confirm it before production.
