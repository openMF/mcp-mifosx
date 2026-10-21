# Credit Rules (for reviewers)

All rules run in `scripts/credit_analyst.py` with the values in `assets/credit-policy.json`. The model never applies
them itself. Values marked "default" are decisions on points the ticket leaves open; change them in the policy file.

## Rules

| Ticket rule | Implementation |
|---|---|
| 1. National ID valid | `id-check`: the provider's ID platform result must be `VALID` |
| 2. Formal income proof | `income-check`: bank statements or **stamped** payroll stubs for the 3 most recent complete calendar months (M-1, M-2, M-3). During the first 10 days of a month (default `grace_days`), M-2..M-4 is also accepted. Missing months: `INCOME_PROOF_INCOMPLETE`; older months only: `INCOME_PROOF_OUTDATED`; no documents: `NO_FORMAL_INCOME` |
| 3. Authorization links | `request-authorization`: bureau and tax links; at most one resend (default) |
| 4. Download bureau + tax data | `status`: once `AUTHORIZED`, the provider exposes the data |
| 5. Bureau behaviour | Fail (`BUREAU_RISK_EXCEEDED`) if any account, open or closed, ever had a worst risk level above `max_risk_level` (default 3: Buro-style MOP 04+, 60+ days late, fails). Service, communications and tax-credit accounts are excluded from the check **and** from payments (default). No bureau history: refer |
| 6. Bank capacity | 30% x average of the 3 months' inflows, excluding refunds, credit disbursements and reversals (gross deposits, default) |
| 7. Tax capacity | When an annual return exists: 30% x (annual income / 12) (default) |
| 8. Higher capacity | max(bank capacity, tax capacity) |
| 9. Free capacity, maximum financing | free = capacity - monthly payments of open, non-excluded bureau accounts (revolving without a payment: 5% of balance, default). free <= 0: `INSUFFICIENT_CAPACITY`. Maximum = free x (1 - (1 + r)^-n) / r |
| 10. 48 months, 22% minimum | n = 48 (shorter on request, minimum 6), r = 22% / 12 (nominal annual, declining balance, no VAT, default). The rate is shown as "from 22%" |
| 11. Proposal or rejection | `analyze`: `PROPOSAL` (amount = min(requested, maximum)), `REJECT`, `REFER` or `INCOMPLETE` with reason codes; `applicant_summary` gives the applicant's lines, the officer gets all figures plus `officer_notes` (bureau and tax summaries) in the handover |

Other decisions: Tax Status Certificate missing: `INCOMPLETE`; negative compliance opinion: refer; legal entities:
refer in v1. `analyze` collects every reason that applies (for example a negative compliance opinion and a bureau
risk); any rejection reason makes the decision `REJECT`, otherwise `INCOMPLETE`, otherwise `REFER`. Steps that end a
case early (invalid ID, structured income data missing, authorization refused, authorization links resent more than
once: `REFER_AUTHORIZATION_LIMIT`) close it too, so `handover` works for every outcome. Income: one entry per month
(bank statements preferred over payroll stubs), months with no counted income are ignored, and the stamp check applies
only to the months used. Bureau account types are matched case-insensitively; a file with only excluded accounts
counts as no credit history. The 10-day grace window accepts M-4 statements until day 10 of a month; whether that
fits "no older than 3 months" is an open question. The ticket's invoicing data is collected but not used in the
calculation (annual return / 12 is the default tax income). Amounts are rounded to the cent; the maximum is floored so the monthly payment never exceeds the free
capacity. Annuity factor for 48 months at 22%: 31.7399.

## Mock applicants and expected results (tested)

| Case | Situation | Expected |
|---|---|---|
| 1001 | Good income, clean bureau | PROPOSAL 120,000.00 at 3,780.73 / month (max 250,745.27) |
| 1002 | Payments exceed 30% capacity | REJECT `INSUFFICIENT_CAPACITY` |
| 1003 | Bureau history too late | REJECT `BUREAU_RISK_EXCEEDED` |
| 1004 | ID not valid | `INVALID_ID` |
| 1005 | Months 4-6 only | `INCOME_PROOF_OUTDATED` |
| 1006 | Months 1, 2, 4 | `INCOME_PROOF_INCOMPLETE` |
| 1007 | Refunds, disbursement, reversal in inflows | PROPOSAL 100,000.00 (only salary counted) |
| 1008 | Stamped payroll stubs | PROPOSAL 60,000.00 |
| 1009 | Tax income higher than bank | PROPOSAL 250,000.00 (tax capacity used) |
| 1010 | No Tax Status Certificate | INCOMPLETE `TAX_STATUS_CERTIFICATE_MISSING` |
| 1011 | Negative compliance opinion | REFER `REFER_TAX_COMPLIANCE` |
| 1012 | No bureau history | REFER `REFER_NO_CREDIT_HISTORY` |
| 1013 | Authorization pending on first check | PROPOSAL after a second status check |
| 1014 | Authorization refused | `AUTHORIZATION_REFUSED` |
| 1015 | Legal entity | REFER `REFER_LEGAL_ENTITY` |
| 1016 | No income documents | `NO_FORMAL_INCOME` |
| 1017 | Unstamped payroll stubs | `INCOME_PROOF_NOT_STAMPED` |
| 1018 | Documents without structured data | REFER `REFER_NO_STRUCTURED_DATA` |
| 1019 | Authorization link expired | `AUTHORIZATION_EXPIRED` |
| 1020 | Requested 900,000 | PROPOSAL capped at the maximum 250,745.27 |

Examples of the handover record: `assets/assessment-example-proposal.json`, `assets/assessment-example-reject.json`,
`assets/assessment-example-refer.json` (schema: `assets/credit-assessment.schema.json`).

## Placeholders

`assets/institution-profile.template.json` holds `[FINANCIAL INSTITUTION NAME]`, `[REGULATOR 1]`, `[REGULATOR 2]`
(the ticket repeats `[REGULATOR 1]`; treated as a typo), `[LOCALITY/STATE/COUNTRY]`, `[NATIONAL ID]` and
`[RISK SCORING SYSTEM]`. Until configured, the agent says "our institution" and never shows brackets.
