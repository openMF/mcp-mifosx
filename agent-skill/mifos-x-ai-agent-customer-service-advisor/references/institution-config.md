# Institution Configuration

The ticket defines the advisor for a generic regulated institution. Deployment-specific values live in `assets/institution-profile.template.json`.

## Fields

| Field | Placeholder in the ticket | Used for |
|---|---|---|
| `financial_institution_name` | `[FINANCIAL INSTITUTION NAME]` | Greeting, self-identification, existing-customer question |
| `regulator_1` | `[REGULATOR 1]` | "Regulated entity" statements (Rule 8) |
| `regulator_2` | `[REGULATOR 2]` | "Regulated entity" statements (Rule 8) |
| `location` | `[LOCALITY/STATE/COUNTRY]` | Context for the role; not shown unless relevant |
| `complaints_authority` | — (CONDUSEF in the ticket) | Formal complaints routing note |
| `currency` | — | Default currency for `need.currency` in the handoff |
| `investment_minimum_amount` | — ($1,000 in filter 4) | Detecting filter 4 only; not quoted as a figure |

## How the values are filled

1. The operator deploying the skill copies `assets/institution-profile.template.json`, replaces every bracketed value, and provides the filled profile to the agent (for example in the system prompt, or by editing the template in a private deployment).
2. If a value still contains brackets, treat it as unknown: refer to "our institution" or "our regulators", and never show the bracketed text to the customer.
3. The advisor never invents a regulator, institution name, minimum amount or currency.
