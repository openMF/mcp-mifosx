// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// node --test tests/*.test.mjs   (no dependencies; tests/fake-pi.mjs stands in for Pi)
import assert from "node:assert/strict";
import { after, before, test } from "node:test";
import { fileURLToPath } from "node:url";

import { createRunner } from "../runner.mjs";

const TOKEN = "test-token";
let server;
let base;

before(async () => {
  server = createRunner({
    token: TOKEN,
    command: fileURLToPath(new URL("./fake-pi.mjs", import.meta.url)),
    skill: "/opt/pi-agent/skills/decision.md",
    maxTimeoutSeconds: 10,
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});

after(() => server.close());

function run(body, token = TOKEN) {
  return fetch(`${base}/run`, {
    method: "POST",
    headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: typeof body === "string" ? body : JSON.stringify(body),
  });
}

test("health", async () => {
  const res = await fetch(`${base}/health`);
  assert.equal(res.status, 200);
  assert.deepEqual(await res.json(), { status: "UP" });
});

test("refuses a missing or wrong token", async () => {
  assert.equal((await run({ input: "case" }, null)).status, 401);
  assert.equal((await run({ input: "case" }, "wrong")).status, 401);
});

test("refuses a bad request", async () => {
  assert.equal((await run("not json")).status, 400);
  assert.equal((await run({ input: "  " })).status, 400);
  assert.equal((await run({ input: "case", timeout_seconds: 11 })).status, 400);
  assert.equal((await fetch(`${base}/run`)).status, 405);
  assert.equal((await fetch(`${base}/other`)).status, 404);
});

test("refuses a body over 64 KB", async () => {
  assert.equal((await run({ input: "x".repeat(70 * 1024) })).status, 413);
});

test("answers with the final message and the tool calls", async () => {
  const res = await run({ input: '{"applicant": "Ana"}' });
  assert.equal(res.status, 200);
  const body = await res.json();
  const answer = JSON.parse(body.output);
  assert.equal(answer.stdin, '{"applicant": "Ana"}', "the case arrives on stdin");
  assert.deepEqual(answer.args.slice(0, 4), ["--mode", "json", "--append-system-prompt", "/opt/pi-agent/skills/decision.md"]);
  assert.deepEqual(body.tool_calls, [
    { tool: "search_clients", arguments: { nameQuery: "Ana" }, is_error: false },
    { tool: "get_client", arguments: { clientId: 7 }, is_error: true },
  ]);
  assert.equal(body.model, "fake-model");
  assert.equal(body.stop_reason, "stop");
  assert.ok(body.duration_ms >= 0);
});

test("reports an agent that fails without answering", async () => {
  const res = await run({ input: "crash" });
  assert.equal(res.status, 502);
  assert.match((await res.json()).error, /crashed/);
});

test("stops an agent that runs over its time, and keeps the next case waiting", async () => {
  const slow = run({ input: "sleep", timeout_seconds: 2 });
  // Queued behind the slow case, with less time than it takes: refused as busy.
  await new Promise((resolve) => setTimeout(resolve, 200));
  const busy = await run({ input: "case", timeout_seconds: 1 });
  assert.equal(busy.status, 503);
  assert.equal((await slow).status, 504);
  // The slot is free again.
  assert.equal((await run({ input: "case" })).status, 200);
});
