# External Systems (provider contract)

The ticket needs systems that are not part of Mifos X: the `[NATIONAL ID]` platform and the `[RISK SCORING SYSTEM]`
(credit-bureau and tax authorization links and data). The skill never calls them directly. `scripts/credit_analyst.py`
reads every applicant fact through a **provider**; the bundled provider serves the mock applicants in
`assets/mock-applicants.json`. A real deployment replaces the provider with an adapter (for example an MCP tool or an
HTTP client) that returns the same shapes, without changing `SKILL.md`.

The model only ever passes a **case id** (digits), supplied by the gateway session or the handoff. It never sees
or passes ID numbers, account numbers or document contents.

## Case record

| Field | Type | Meaning |
|---|---|---|
| `case_id` | digits | Case identifier issued by the gateway |
| `entity_type` | `INDIVIDUAL` \| `LEGAL_ENTITY` | Applicant type (legal entities are referred to an officer in v1) |
| `requested_amount` | number or null | Amount the applicant asked for (from the AI-261 handoff if present) |

## National ID platform (rule 1)

The applicant verifies the ID on the platform link. The provider returns `id_status`: `VALID`, `INVALID`.

## Income data (rule 2, rule 6)

`income`: list of months, or `null` when documents were uploaded but no structured data is available (result: refer).

| Field | Type | Meaning |
|---|---|---|
| `month_offset` | integer | 1 = last complete calendar month, 2 = the month before, ... |
| `source` | `bank_statement` \| `payroll_stub` | Document type |
| `stamped` | boolean | Payroll stubs only: digitally stamped (for example CFDI) |
| `inflows[]` | `{type, amount}` | Classified inflows: `salary`, `transfer_in`, `refund`, `credit_disbursement`, `reversal`, ... |

Classification comes from the scoring platform, open banking or officer data entry. The agent never reads amounts
from uploaded files. For payroll stubs, the net pay is the `salary` inflow.

## Scoring platform (rules 3-4)

- Authorization links: one for the credit bureau, one for tax information. Outcome: `AUTHORIZED`, `REFUSED`,
  `EXPIRED` (`pending_checks` in the mock = how many status checks return `PENDING` first).
- Bureau report `bureau[]`: `{type, status: open|closed, worst_risk_level, monthly_payment, revolving, balance}`.
  Payments are normalized to monthly by the provider.
- Tax information `tax`: `{annual_return_income, tax_status_certificate, compliance_opinion: positive|negative,
  monthly_invoicing[]}`.
