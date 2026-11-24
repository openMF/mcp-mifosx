// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The intake job's rules against a stand-in LOS: the same outcomes as the
// OpenFn job (sandbox README, 4.3).
import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { after, before, beforeEach, test } from "node:test";

import { bearerClient } from "../src/http.mjs";
import { tasks } from "../src/jobs/intake.mjs";
import { stub } from "./stub.mjs";

const TOKEN = "intake-token";
const FIELDS = ["channel", "external_id", "full_name", "phone", "email", "national_id",
  "requested_amount", "currency", "term_months", "productive_use"];

let los;
let store;
let refuse; // a status the LOS answers every create with, when set

function fakeLos({ method, path, query, headers, body }) {
  if (headers.authorization !== `Bearer ${TOKEN}`) return [401, { detail: "unauthorized" }];
  if (method === "GET" && path === "/opportunities") {
    const found = store.filter((o) => ["external_id", "national_id", "phone"].every((k) => !query[k] || o[k] === query[k]));
    return [200, { opportunities: found }];
  }
  if (method === "POST" && path === "/opportunities") {
    if (refuse) return [refuse, { detail: [{ loc: ["body", "phone"], msg: "not a phone number" }] }];
    const id = randomUUID();
    const opportunity = { id, stage: "new", ...Object.fromEntries(FIELDS.map((k) => [k, null])), ...body };
    opportunity.external_id = body.external_id || `opp-${id.slice(0, 8)}`;
    store.push(opportunity);
    return [201, opportunity];
  }
  const move = /^\/opportunities\/([^/]+)\/stage$/.exec(path);
  if (method === "POST" && move) {
    const opportunity = store.find((o) => o.id === move[1]);
    Object.assign(opportunity, { stage: body.stage, reason_code: body.reason_code, reason_note: body.reason_note });
    return [200, opportunity];
  }
  return [404, { detail: "not found" }];
}

before(async () => {
  los = await stub(fakeLos);
});
after(() => los.close());
beforeEach(() => {
  store = [];
  refuse = null;
  los.requests.length = 0;
});

const lines = [];
const run = (data) => tasks.intake_opportunity({ data }, { los: bearerClient(los.url, TOKEN), log: (...p) => lines.push(p.join(" ")) });

const LEAD = {
  channel: "web", external_id: "web-1", full_name: "Ana López", phone: "+525512345678",
  national_id: "LOPA800101MDFRRN09", requested_amount: 15000, currency: "MXN", term_months: 12,
  productive_use: "Stock for the shop",
};

test("a lead without a channel is invalid, and the LOS is not called", async () => {
  const { channel, ...rest } = LEAD;
  assert.deepEqual(await run(rest), { outcome: "invalid", errors: ["channel is required"] });
  assert.equal(los.requests.length, 0);
});

test("a complete lead starts in new, and the LOS is called with the intake token", async () => {
  const result = await run(LEAD);
  assert.equal(result.outcome, "complete");
  assert.equal(result.stage, "new");
  assert.deepEqual(result.missing, []);
  assert.equal(result.externalId, "web-1");
  assert.ok(los.requests.every((r) => r.headers.authorization === `Bearer ${TOKEN}`));
  assert.match(lines.at(-1), /intake web-1 complete/);
});

test("an incomplete lead says what is missing; blank values count as missing", async () => {
  const result = await run({ ...LEAD, productive_use: "  ", term_months: null });
  assert.equal(result.outcome, "incomplete");
  assert.deepEqual(result.missing, ["term_months", "productive_use"]);
  assert.equal(store[0].productive_use, null, "a blank value is not sent to the LOS");
});

test("the same external_id again is answered with the opportunity already recorded", async () => {
  const first = await run(LEAD);
  const again = await run(LEAD);
  assert.equal(again.outcome, "already_received");
  assert.equal(again.opportunityId, first.opportunityId);
  assert.equal(store.length, 1);
});

test("a lead with neither phone nor email is recorded as discarded, no_contact", async () => {
  const { phone, ...rest } = LEAD;
  const result = await run(rest);
  assert.equal(result.outcome, "discarded");
  assert.equal(result.stage, "discarded");
  assert.equal(store[0].reason_code, "no_contact");
});

test("the CURP of an open opportunity: paused, possible_duplicate, naming the match", async () => {
  await run(LEAD);
  const result = await run({ ...LEAD, external_id: "web-2", phone: "+525599999999" });
  assert.equal(result.outcome, "possible_duplicate");
  assert.equal(result.stage, "paused");
  assert.equal(result.duplicateOf, "web-1");
  assert.equal(store[1].reason_code, "possible_duplicate");
  assert.equal(store[1].reason_note, "Matches web-1 on national_id");
});

test("the phone of an open opportunity: possible_duplicate on phone", async () => {
  await run(LEAD);
  const result = await run({ ...LEAD, external_id: "web-2", national_id: "DIAR850505MDFZSS02" });
  assert.equal(result.outcome, "possible_duplicate");
  assert.equal(store[1].reason_note, "Matches web-1 on phone");
});

test("a match that is registered or discarded is no duplicate", async () => {
  await run(LEAD);
  store[0].stage = "registered";
  const result = await run({ ...LEAD, external_id: "web-2" });
  assert.equal(result.outcome, "complete");
});

test("a lead the LOS refuses (422) is invalid, with the LOS's errors, and nothing is recorded", async () => {
  refuse = 422;
  const result = await run(LEAD);
  assert.equal(result.outcome, "invalid");
  assert.deepEqual(result.errors, { detail: [{ loc: ["body", "phone"], msg: "not a phone number" }] });
  assert.equal(store.length, 0);
});

test("any other LOS error fails the task", async () => {
  refuse = 500;
  await assert.rejects(run(LEAD), { statusCode: 500, message: "POST /opportunities answered 500" });
});
