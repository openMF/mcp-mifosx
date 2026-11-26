// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// A stop with the real SDK against a stand-in Conductor: a poll still under
// way when the worker stops returns a task, which runs and is reported before
// the worker counts as drained.
import assert from "node:assert/strict";
import { after, before, test } from "node:test";

import { createConductorClient, TaskManager } from "@io-orkes/conductor-javascript";

import { conductorWorker, inFlight } from "../src/worker.mjs";
import { stub } from "./stub.mjs";

const TASK = { taskId: "task-1", workflowInstanceId: "wf-1", taskDefName: "demo_task", taskType: "demo_task",
  status: "IN_PROGRESS", inputData: { state: { data: { n: 1 } } } };

let conductor;
let polls = 0;
const events = [];

before(async () => {
  conductor = await stub(async ({ method, path, body }) => {
    if (method === "GET" && path.startsWith("/api/tasks/poll/batch/")) {
      polls++;
      if (polls === 1) {
        // The first poll is slow: the worker is stopped while it is under way
        await new Promise((resolve) => setTimeout(resolve, 600));
        events.push("poll returned a task");
        return [200, [TASK]];
      }
      return [200, []];
    }
    if (method === "POST" && path === "/api/tasks/update-v2") return [404, { message: "not found" }];
    if (method === "POST" && path === "/api/tasks") {
      events.push(`result reported: ${body.status}`);
      return [200, "ok"];
    }
    return [404, { message: `unexpected ${method} ${path}` }];
  });
});
after(() => conductor.close());

test("a poll under way at the stop: its task runs and is reported before the worker is drained", async (t) => {
  const client = await createConductorClient({ serverUrl: conductor.url, requestTimeoutMs: 10_000 });
  const worker = conductorWorker("demo_task", async (state) => {
    await new Promise((resolve) => setTimeout(resolve, 200));
    events.push("task ran");
    return { ...state, done: true };
  }, () => ({}), { timeoutSeconds: 5, concurrency: 1, pollInterval: 50 });
  const tracker = inFlight();
  const manager = new TaskManager(client, [worker], {
    options: { workerID: "test-worker" }, maxRetries: 3, eventListeners: [tracker.listener],
  });
  // Stops polling even when an assertion below fails; the explicit stop stays the one tested
  t.after(() => manager.stopPolling());
  manager.startPolling();
  await new Promise((resolve) => setTimeout(resolve, 150)); // the first poll is under way
  assert.equal(polls, 1);
  await manager.stopPolling();
  events.push("stopped polling");
  const drained = await tracker.drained(5000);
  events.push("drained");
  assert.equal(drained, true);
  assert.deepEqual(events, ["stopped polling", "poll returned a task", "task ran", "result reported: COMPLETED", "drained"]);
  assert.equal(polls, 1, "no poll after the stop");
});
