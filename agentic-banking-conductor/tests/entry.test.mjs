// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The entry against a stand-in Conductor: keys, routes, and replies shaped
// like Lightning's webhooks.
import assert from "node:assert/strict";
import { after, before, beforeEach, test } from "node:test";

import { createEntry, keysFromEnv, ROUTES } from "../src/entry.mjs";
import { stub } from "./stub.mjs";

const KEYS = { intake: "i".repeat(48), officer: "o".repeat(48) };
const ROUTES_UNDER_TEST = {
  ...ROUTES,
  // An at_once route, as the identity and assessment workflows will have
  check: { workflow: "opportunity_check", version: 1, key: "officer", reply: "at_once" },
};

let conductor;
let answer; // what the stand-in Conductor answers next: [status, body]
let workflowRecord; // what it answers to GET /api/workflow/<id>
let entry;
let base;

before(async () => {
  conductor = await stub(({ method }) => (method === "GET" ? workflowRecord : answer));
  entry = createEntry({ conductorUrl: conductor.url, keys: KEYS, routes: ROUTES_UNDER_TEST, waitSeconds: 30 });
  await new Promise((resolve) => entry.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${entry.address().port}`;
});
after(async () => {
  entry.close();
  await conductor.close();
});
beforeEach(() => {
  conductor.requests.length = 0;
  answer = [200, { workflowId: "wf-1", status: "COMPLETED", output: { data: { outcome: "complete" } } }];
  workflowRecord = [404, { message: "not found" }];
});

async function post(path, body, key = KEYS.intake, raw = false) {
  const response = await fetch(base + path, {
    method: "POST",
    headers: { "content-type": "application/json", ...(key ? { "x-api-key": key } : {}) },
    body: raw ? body : JSON.stringify(body),
  });
  return [response.status, await response.json()];
}

test("health answers without a key", async () => {
  const response = await fetch(`${base}/health`);
  assert.equal(response.status, 200);
});

test("an unknown workflow is 404, another method 405", async () => {
  assert.deepEqual(await post("/i/nothing", {}), [404, { error: "not_found" }]);
  assert.equal((await fetch(`${base}/i/intake`)).status, 405);
  assert.equal(conductor.requests.length, 0);
});

test("without the route's key: 401, and Conductor is not called", async () => {
  assert.equal((await post("/i/intake", {}, null))[0], 401);
  assert.equal((await post("/i/intake", {}, "wrong"))[0], 401);
  assert.equal((await post("/i/intake", {}, KEYS.officer))[0], 401, "the officers' key does not start intake");
  assert.equal((await post("/i/check", {}, KEYS.intake))[0], 401, "the channels' key does not start an officers' workflow");
  assert.equal(conductor.requests.length, 0);
});

test("a body that is not a JSON object is 400, one over 64 KiB 413", async () => {
  assert.equal((await post("/i/intake", "{not json", KEYS.intake, true))[0], 400);
  assert.equal((await post("/i/intake", [1, 2]))[0], 400);
  assert.equal((await post("/i/intake", { note: "x".repeat(70 * 1024) }))[0], 413);
  assert.equal(conductor.requests.length, 0);
});

test("after_completion: runs the workflow and replies with its result, as Lightning does", async () => {
  const [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(status, 201, "201, as Lightning answers");
  assert.deepEqual(body, { data: { outcome: "complete" }, meta: { state: "success", workflow_id: "wf-1" } });
  const [request] = conductor.requests;
  assert.equal(request.path, "/api/workflow/execute/opportunity_intake/1");
  assert.equal(request.query.waitForSeconds, "30");
  assert.deepEqual(request.body, { name: "opportunity_intake", version: 1, input: { channel: "web" } });
});

test("a failed workflow: 500 with meta.state failed, and the reason from the failed task", async () => {
  // As Conductor 3.34.1 answers: no reason of its own, the failed task's on the task
  answer = [200, { workflowId: "wf-2", status: "FAILED", tasks: [
    { referenceTaskName: "intake_opportunity", status: "FAILED", reasonForIncompletion: "GET /opportunities answered 500" },
  ] }];
  const [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(status, 500);
  assert.deepEqual(body.meta, { state: "failed", status: "FAILED", reason: "GET /opportunities answered 500", workflow_id: "wf-2" });
  assert.equal(conductor.requests.length, 1, "no second call when the task has the reason");
});

test("a failed workflow whose tasks carry no reason: read from the workflow's record, else null", async () => {
  answer = [200, { workflowId: "wf-5", status: "TIMED_OUT", tasks: [] }];
  workflowRecord = [200, { workflowId: "wf-5", reasonForIncompletion: "Workflow timed out after 900 seconds" }];
  let [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(status, 500);
  assert.equal(body.meta.reason, "Workflow timed out after 900 seconds");
  assert.equal(conductor.requests.at(-1).path, "/api/workflow/wf-5");
  workflowRecord = [404, { message: "not found" }];
  [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(body.meta.reason, null);
});

test("a workflow still running after the deadline: 504 timeout, and it goes on", async () => {
  answer = [200, { workflowId: "wf-3", status: "RUNNING" }];
  const [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(status, 504);
  assert.deepEqual(body, { error: "timeout", meta: { status: "RUNNING", workflow_id: "wf-3" } });
});

test("Conductor refusing: 502 conductor_error; unreachable: 502 conductor_unavailable; only the code reaches the caller", async () => {
  answer = [404, { message: "No such workflow found by name: opportunity_intake, version: 1" }];
  const [status, body] = await post("/i/intake", { channel: "web" });
  assert.equal(status, 502);
  assert.equal(body.error, "conductor_error");
  assert.deepEqual(body, { error: "conductor_error" }, "Conductor's own answer stays in the entry's log");
  const down = createEntry({ conductorUrl: "http://127.0.0.1:9", keys: KEYS, routes: ROUTES_UNDER_TEST });
  await new Promise((resolve) => down.listen(0, "127.0.0.1", resolve));
  const response = await fetch(`http://127.0.0.1:${down.address().port}/i/intake`, {
    method: "POST", headers: { "x-api-key": KEYS.intake }, body: "{}",
  });
  down.close();
  assert.equal(response.status, 502);
  assert.deepEqual(await response.json(), { error: "conductor_unavailable" });
});

test("at_once: starts the workflow and replies 202 with its id", async () => {
  answer = [200, "wf-4"];
  const [status, body] = await post("/i/check", { opportunity_id: "x" }, KEYS.officer);
  assert.equal(status, 202);
  assert.deepEqual(body, { workflow_id: "wf-4" });
  const [request] = conductor.requests;
  assert.equal(request.path, "/api/workflow/opportunity_check");
  assert.equal(request.query.version, "1");
  assert.deepEqual(request.body, { opportunity_id: "x" });
  assert.match(request.headers.accept, /text\/plain/, "the start call answers text/plain");
});

test("after_completion asks for JSON", async () => {
  await post("/i/intake", { channel: "web" });
  assert.equal(conductor.requests[0].headers.accept, "application/json");
});

test("keys: each route's key is required, and short ones are refused", () => {
  assert.deepEqual(keysFromEnv({ ENTRY_INTAKE_API_KEY: KEYS.intake }), { intake: KEYS.intake });
  assert.throws(() => keysFromEnv({}), /ENTRY_INTAKE_API_KEY/);
  assert.throws(() => keysFromEnv({ ENTRY_INTAKE_API_KEY: "short" }), /at least 32/);
});
