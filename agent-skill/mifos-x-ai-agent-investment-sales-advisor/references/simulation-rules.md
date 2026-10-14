# Simulation Rules (Investment Simulator V12)

For reviewers and maintainers. The advisor never applies these formulas itself: `scripts/simulate_investment.py` does, reading every rate and tax parameter from `assets/simulator-v12.json`. To update rates, tax years or the campaign, edit that file only and re-run the tests.

## Minimum data

- Investment amount and term in days (or maturity date).
- Product: Promissory Note (interest at maturity) or CEDE +D60 (monthly interest, minimum term 60 days).
- Opening date (default: today in Mexico City).
- Whether the client holds other active investments with the institution (affects cumulative ISR).

## Formulas

- Annual gross rate = table rate by amount and term (+ surcharge only if registered by an authorized area; the advisor never adds one).
- Gross interest = amount × annual gross rate ÷ 360 × days.
- Estimated ISR = (amount − exempt amount) × annual withholding rate ÷ 360 × days; $0 when the amount does not exceed the exempt amount.
- Net interest = gross interest − estimated ISR. Total at maturity = amount + net interest.
- Annual net rate = (net interest ÷ amount) ÷ days × 360.
- CEDE: interest is calculated in 30-day periods; the final period is proportional to the remaining days.

## Implementation decisions

| Topic | Decision |
|---|---|
| Amount brackets | Lower bound inclusive: an amount below 100,000 (for example 99,999.50) uses the first row. |
| Term ranges | Lower bound inclusive: 29 days is "7 to 29", 30 days is "30 to 59". |
| Rounding | Decimal arithmetic; each displayed figure rounded half-up to cents; totals and net rate come from unrounded values. The table rate is never rounded. CEDE period interest is summed unrounded (rounding each period would give 137,499.96 instead of 137,500.00 for CEDE 1,000,000 / 360 days). |
| Tax year | The opening date's year. A year missing from the table returns `ESCALATE_NO_TAX_DATA`. |
| Fiscal-year crossing | The ticket requires the "Sim P_Ejerccio Fiscal" tab, which is not available. ISR is estimated with the opening year's parameters (the ticket's own 360-day example does the same), the output is flagged `fiscal_year_crossing`, and the Commercial Executive confirms ISR. |
| Other active investments | The ticket requires the "Sim Acumulado" tab, which is not available. ISR is estimated for this investment alone, flagged `cumulative_isr_unverified`, and recalculated by the Commercial Executive. |
| Campaign 14% | Controlled only by `campaign.active` in the asset. Eligible from 360 days, both products, and only when 14% is above the table rate (a Promissory Note of $1,000,000+ at 360+ days is already 14.00%). Returned as a separate `campaign` block next to the standard figures. |
| Opening date | Today or later; a past date returns `INVALID_DATE`. |
| Currency | MXN only; anything else returns `UNSUPPORTED_CURRENCY`. |
| Extra output fields | Every `OK` result also carries `disclaimer` (simulation date, rate table, rate phrase and ISR note, to copy word for word), `handover_id` and `generated_at` (copied into the handover so the model never runs other commands) and, for CEDE, `each_30_day_period` (gross, ISR and net for a 30-day period). |
| Self-check | Before returning figures the script checks 0 ≤ ISR ≤ gross interest, net ≤ gross and total = amount + net; otherwise `ESCALATE_INCONSISTENT`. |

## Statuses

`OK`, `INVALID_INPUT`, `INVALID_AMOUNT` (filter 1), `INVALID_TERM` (filter 2), `CEDE_MIN_TERM` (filter 3), `REINVEST_LIMIT` (filter 5), `REINVEST_NOT_APPLICABLE` (CEDE), `MISSING_TERM`, `INVALID_DATE`, `UNSUPPORTED_CURRENCY`, `ESCALATE_NO_TAX_DATA`, `ESCALATE_INCONSISTENT`.

## Worked examples (from the ticket; unit-tested to the cent)

| Case | Gross rate | Gross interest | Estimated ISR | Net interest | Net rate |
|---|---|---|---|---|---|
| Promissory Note · $100,000 · 90 days | 10.00% | $2,500.00 | $0.00 | $2,500.00 | 10.00% |
| Promissory Note · $500,000 · 180 days | 12.45% | $31,125.00 | $1,287.12 | $29,837.88 | 11.94% |
| CEDE · $1,000,000 · 360 days | 13.75% | $137,500.00 | $7,074.24 | $130,425.76 | 13.04% |

Standard table, no surcharge or campaign, 2026 ISR (0.90%, exempt amount $213,973.20 = 5 × annual UMA $42,794.64).

## Handover examples

`assets/handover-example-ready.json` (individual, ready to formalize), `assets/handover-example-onboarding.json` (new legal entity, CEDE with the campaign rate), `assets/handover-example-escalation.json` (minor account holder, filter 8). All validate against `assets/handover.schema.json`.
