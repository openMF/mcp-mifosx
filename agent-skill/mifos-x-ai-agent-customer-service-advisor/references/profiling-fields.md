# Profiling Fields

Capture these structured details before transferring. Do not probe further than necessary. Do not try to resolve customer questions; record the purpose and route.

Each field maps to a property of `assets/handoff.schema.json`.

## Investment route (`INVESTMENT_ADVISOR`)

| Field | Handoff property |
|---|---|
| Entity type: individual or legal entity | `customer.entity_type` |
| Approximate investment amount | `need.approx_amount`, `need.currency` |
| Desired term, or whether liquidity / periodic income is needed | `need.desired_term`, `need.liquidity_need` |
| Whether they are an existing customer or hold active investments | `customer.existing_customer`, `active_process` |
| Preferred contact channel for follow-up | `customer.preferred_channel` |

## Credit route (`CREDIT_ANALYST`)

| Field | Handoff property |
|---|---|
| Entity type: individual or legal entity | `customer.entity_type` |
| Approximate credit amount needed | `need.approx_amount`, `need.currency` |
| Purpose or destination of the credit (only if mentioned voluntarily) | `need.purpose` |
| Ability to prove formal income (bank statements or payslips) | `need.formal_income_proof` |
| Whether they are an existing customer | `customer.existing_customer` |
| Preferred contact channel for follow-up | `customer.preferred_channel` |

## Operations route (`OPERATIONS`): questions and inquiries

| Field | Handoff property |
|---|---|
| Entity type: individual or legal entity (if applicable) | `customer.entity_type` |
| Reason for the inquiry, in one phrase (status, requirements, process, document, product, etc.) | `need.inquiry_reason` |
| Whether they are an existing customer, and customer/reference number if provided | `customer.existing_customer`, `customer.reference_number` |
| Preferred contact channel for follow-up | `customer.preferred_channel` |

## Question order

Ask the missing fields in this order, at most two per reply. The order follows the ticket's reference script (`references/conversation-script.md`).

- **Investment:** approximate amount → desired term (or liquidity / periodic income need) → existing customer → entity type → preferred channel.
- **Credit:** approximate amount → proof of formal income (bank statements or payslips) → existing customer → entity type → purpose (only if volunteered; never ask) → preferred channel.
- **Operations:** reason for the inquiry in one phrase → existing customer (and customer or case reference number only if offered) → preferred channel. Capture entity type only when the customer provides it or it is applicable.

## Rules

- Ask only for fields that are still missing; reuse anything the customer already said.
- Ask "Are you already a customer of our institution?" as a yes/no question (use the configured institution name if available; never show brackets). Never ask for a name, ID or account number to look the customer up.
- Ask at most two questions per reply.
- Do not request complete documents upon initial contact unless specifically required by the specialist or Operations. Document guidance is the responsibility of the specialist or Operations.
- Never ask for ID numbers, account numbers or other sensitive data. A customer or case reference number (as defined in the ticket) is recorded only if the customer offers it, and only in `customer.reference_number`; ID numbers (INE, CURP, RFC) and account numbers are never recorded anywhere.
- If the customer declines to answer, record `null` / `UNKNOWN` and continue routing.
