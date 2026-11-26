// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// Setup against a stand-in Conductor: it fails when Conductor refuses a
// workflow, even with a 200.
import assert from "node:assert/strict";
import { after, before, beforeEach, test } from "node:test";

import { loadDefinitions, register } from "../src/setup.mjs";
import { stub } from "./stub.mjs";

let conductor;
let workflowAnswer;

before(async () => {
  conductor = await stub(({ path }) => (path === "/api/metadata/taskdefs" ? [200, ""] : workflowAnswer));
});
after(() => conductor.close());
beforeEach(() => {
  conductor.requests.length = 0;
});

test("loads the task definitions, then the workflows", async () => {
  const definitions = await loadDefinitions();
  workflowAnswer = [200, { bulkErrorResults: {}, bulkSuccessfulResults: definitions.workflows.map((w) => w.name) }];
  await register(conductor.url, definitions);
  assert.deepEqual(conductor.requests.map((r) => `${r.method} ${r.path}`),
    ["POST /api/metadata/taskdefs", "PUT /api/metadata/workflow"]);
  assert.deepEqual(conductor.requests[1].body, definitions.workflows);
});

test("a workflow Conductor refuses fails setup, though Conductor answers 200", async () => {
  workflowAnswer = [200, { bulkErrorResults: { opportunity_intake: "could not save" }, bulkSuccessfulResults: [] }];
  await assert.rejects(register(conductor.url, await loadDefinitions()),
    /Conductor refused 1 workflow\(s\): opportunity_intake: could not save/);
});

test("an error status fails setup", async () => {
  workflowAnswer = [500, { message: "boom" }];
  await assert.rejects(register(conductor.url, await loadDefinitions()), /PUT \/api\/metadata\/workflow answered 500/);
});
