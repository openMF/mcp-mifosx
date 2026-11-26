// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// Workflow opportunity_intake: ported from the OpenFn job "intake-opportunity"
// of opportunity-intake (agentic-banking-openfn/openfn/project.yaml). The
// rules are the job's, unchanged; only the plumbing differs: `los.get` and
// `los.post` for language-http's `get` and `post`, `log` for `console.log`.

/** What each task of this workflow needs from the worker's environment. */
export const needs = ["los"];

// A lead from a channel (landing page, Mifos Mobile, ...), checked by rules and
// recorded in the LOS, the record of opportunities until they reach Fineract.
// No model: completeness and duplicates have exact answers. The eligibility
// step decides what happens next. The worker calls the LOS with its intake
// token, so the LOS records it as `intake`. The entry replies with the result
// once the workflow has finished, as Lightning's after_completion webhook does.
async function intakeOpportunity(state, { los, log }) {
  const FIELDS = ["channel", "external_id", "full_name", "phone", "email", "national_id",
    "requested_amount", "currency", "term_months", "productive_use"];
  // What a complete lead has. The list per product is the institution's to
  // set; this is the sandbox's.
  const REQUIRED = ["full_name", "phone", "national_id", "requested_amount", "term_months", "productive_use"];

  const input = state.data || {};
  const lead = {};
  for (const key of FIELDS) {
    const value = input[key];
    if (value !== undefined && value !== null && String(value).trim() !== "") lead[key] = value;
  }
  const missingIn = (record) => REQUIRED.filter((key) => record[key] === undefined || record[key] === null);
  const missing = missingIn(lead);
  const result = (opportunity, outcome, extra = {}) => ({
    opportunityId: opportunity.id,
    externalId: opportunity.external_id,
    stage: opportunity.stage,
    outcome,
    missing: missingIn(opportunity),
    ...extra,
  });

  const search = async (query) => (await los.get("/opportunities", { query: { ...query, limit: 200 } })).data.opportunities;
  const create = async () => (await los.post("/opportunities", lead)).data;
  const move = async (opportunity, stage, reason_code, reason_note) =>
    (await los.post(`/opportunities/${opportunity.id}/stage`, { stage, reason_code, reason_note })).data;

  if (!lead.channel) {
    return { outcome: "invalid", errors: ["channel is required"] };
  }
  try {
    // 1. A channel sending the same lead again
    if (lead.external_id) {
      const [same] = await search({ external_id: lead.external_id });
      if (same) return result(same, "already_received");
    }

    // 2. Nobody can contact the lead: kept for the numbers, not worked on
    if (!lead.phone && !lead.email) {
      const created = await create();
      return result(await move(created, "discarded", "no_contact", "No phone or email"), "discarded");
    }

    // 3. The same person in an open or paused opportunity: a person decides
    const open = (opportunity) => !["registered", "discarded"].includes(opportunity.stage);
    let match = null;
    for (const on of ["national_id", "phone"]) {
      if (!match && lead[on]) {
        const [found] = (await search({ [on]: lead[on] })).filter(open);
        if (found) match = { opportunity: found, on };
      }
    }
    if (match) {
      const created = await create();
      const note = `Matches ${match.opportunity.external_id} on ${match.on}`;
      return result(await move(created, "paused", "possible_duplicate", note), "possible_duplicate",
        { duplicateOf: match.opportunity.external_id });
    }

    // 4. A new opportunity, complete or not
    const created = await create();
    log("intake", created.external_id, missing.length ? `incomplete: ${missing.join(", ")}` : "complete");
    return result(created, missing.length ? "incomplete" : "complete");
  } catch (e) {
    // The LOS refused the lead's data (a phone, email or CURP it cannot read,
    // an amount without a currency): nothing is recorded.
    if (e.statusCode === 422) return { outcome: "invalid", errors: e.body };
    throw e;
  }
}

/** The workflow's tasks, by Conductor task name, in the order they run. */
export const tasks = {
  intake_opportunity: intakeOpportunity,
};
