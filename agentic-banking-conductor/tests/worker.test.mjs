// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The wrapper that turns a job's task into a Conductor worker, the clients a
// worker builds from its environment, and the bounds a stop relies on.
import assert from "node:assert/strict";
import { after, before, test } from "node:test";

import { tasks as intakeTasks } from "../src/jobs/intake.mjs";
import { loadDefinitions } from "../src/setup.mjs";
import {
  conductorWorker, context, drainSeconds, inFlight, RESULT_SECONDS, SDK_CALL_SECONDS, timeLimits,
} from "../src/worker.mjs";
import { stub } from "./stub.mjs";

const TASK = { taskId: "t-1", workflowInstanceId: "wf-1", inputData: { state: { data: { a: 1 } } } };
const NO_CLIENTS = () => ({});

// A stand-in LOS that answers each request after 300 ms
let slowLos;
before(async () => {
  slowLos = await stub(async ({ path }) => {
    await new Promise((resolve) => setTimeout(resolve, 300));
    return path === "/opportunities" ? [200, { opportunities: [] }] : [200, {}];
  });
});
after(() => slowLos.close());

test("a task gets its input state and returns the next state as its output, with its logs", async () => {
  const worker = conductorWorker("demo_task", async (state, { log }) => {
    log("seen", state.data.a);
    return { ...state, done: true };
  }, NO_CLIENTS, { timeoutSeconds: 5 });
  const result = await worker.execute(TASK);
  assert.equal(result.status, "COMPLETED");
  assert.deepEqual(result.outputData, { data: { a: 1 }, done: true });
  assert.equal(result.logs[0].log, "seen 1");
  assert.equal(result.logs[0].taskId, "t-1");
});

test("an error fails the task with its message", async () => {
  const worker = conductorWorker("demo_task", async () => {
    throw new Error("GET /opportunities answered 500");
  }, NO_CLIENTS, { timeoutSeconds: 5 });
  const result = await worker.execute(TASK);
  assert.equal(result.status, "FAILED");
  assert.equal(result.reasonForIncompletion, "GET /opportunities answered 500");
});

test("a task without input state gets an empty one", async () => {
  const worker = conductorWorker("demo_task", async (state) => state, NO_CLIENTS, { timeoutSeconds: 5 });
  assert.deepEqual((await worker.execute({ taskId: "t", inputData: {} })).outputData, {});
});

test("LOS calls that together outlast the task's time limit are cut at it", async () => {
  // Intake makes up to five LOS calls; at 300 ms each they take 1.5 s, and
  // the limit here is 0.7 s
  const env = { LOS_URL: slowLos.url, LOS_TOKEN: "t" };
  const worker = conductorWorker("intake_opportunity", intakeTasks.intake_opportunity,
    (signal) => context(["los"], env, signal), { timeoutSeconds: 0.7 });
  slowLos.requests.length = 0;
  const started = Date.now();
  const result = await worker.execute({ taskId: "t", workflowInstanceId: "wf",
    inputData: { state: { data: { channel: "web", external_id: "x", phone: "+525512345678", national_id: "LOPA800101MDFRRN09" } } } });
  const took = Date.now() - started;
  assert.equal(result.status, "FAILED");
  assert.equal(result.reasonForIncompletion, "intake_opportunity ran past its time limit of 0.7 s");
  assert.ok(took >= 650 && took < 1000, `cut at the limit, not after every call (${took} ms)`);
  assert.ok(slowLos.requests.length <= 3, `no call after the deadline (${slowLos.requests.length} made)`);
  assert.ok(!slowLos.requests.some((r) => r.method === "POST"), "the lead was never created");
});

test("on stop, the worker waits for tasks in flight until Conductor has their results", async () => {
  const tracker = inFlight();
  assert.equal(await tracker.drained(10), true, "nothing in flight: at once");
  tracker.listener.onTaskExecutionStarted({ taskId: "a" });
  tracker.listener.onTaskExecutionStarted({ taskId: "b" });
  assert.equal(tracker.size, 2);
  const drained = tracker.drained(1000);
  tracker.listener.onTaskUpdateCompleted({ taskId: "a" });
  tracker.listener.onTaskUpdateFailure({ taskId: "b" });
  assert.equal(await drained, true);
  tracker.listener.onTaskExecutionStarted({ taskId: "c" });
  assert.equal(await tracker.drained(20), false, "still running after the drain: false");
});

test("a poll under way, and the tasks it returns, keep the worker from stopping until they have run", async () => {
  const tracker = inFlight();
  tracker.listener.onPollStarted({ pollCount: 2 });
  let settled = false;
  const drained = tracker.drained(1000).then((value) => {
    settled = true;
    return value;
  });
  tracker.listener.onPollCompleted({ tasksReceived: 1 });
  await new Promise((resolve) => setImmediate(resolve));
  assert.equal(settled, false, "a returned task that has not started yet");
  assert.equal(tracker.size, 1);
  tracker.listener.onTaskExecutionStarted({ taskId: "t" });
  tracker.listener.onTaskUpdateCompleted({ taskId: "t" });
  assert.equal(await drained, true);

  tracker.listener.onPollStarted({ pollCount: 1 });
  const failed = tracker.drained(1000);
  tracker.listener.onPollFailure({ cause: new Error("down") });
  assert.equal(await failed, true, "a failed poll returned nothing");
});

test("an SDK call ends within 14 s; a result within 3 attempts of two calls each and the back-offs between them", () => {
  // 10 s timeout, and the longest connection-error back-off (3 s + 10%) already started
  assert.equal(SDK_CALL_SECONDS, 14);
  // each attempt: update-v2, then the legacy update when it answers 404 or 405
  assert.equal(RESULT_SECONDS, 3 * 2 * 14 + 10 + 20);
});

test("a stop waits for a poll under way, the task it returned run to its limit, and its report", async () => {
  const { tasks } = await loadDefinitions();
  assert.equal(drainSeconds(tasks, ["intake_opportunity"], {}), 14 + 60 + 114);
  assert.equal(drainSeconds([{ name: "a", timeoutSeconds: 60 }, { name: "b", timeoutSeconds: 600 }], ["a", "b"], {}),
    14 + 600 + 114);
  assert.equal(drainSeconds(tasks, ["intake_opportunity"], { WORKER_DRAIN_SECONDS: "5" }), 5);
  assert.equal(drainSeconds(tasks, ["intake_opportunity"], { WORKER_DRAIN_SECONDS: "195" }), 195);
  assert.equal(drainSeconds(tasks, ["intake_opportunity"], { WORKER_DRAIN_SECONDS: "" }), 14 + 60 + 114, "empty: the default");
  for (const bad of ["195s", "-1", "1e3", "abc", " 5", "9999999999"]) {
    assert.throws(() => drainSeconds(tasks, ["intake_opportunity"], { WORKER_DRAIN_SECONDS: bad }),
      /WORKER_DRAIN_SECONDS must be a number of seconds/, `refuses ${JSON.stringify(bad)}`);
  }
  assert.throws(() => timeLimits(tasks, ["no_such_task"]), /no_such_task has no time limit/);
});

test("a worker builds only the clients its job needs, from its environment", () => {
  const ctx = context(["los"], { LOS_URL: "http://los:8000", LOS_TOKEN: "t" });
  assert.deepEqual(Object.keys(ctx), ["los"]);
  assert.throws(() => context(["los"], { LOS_URL: "http://los:8000" }), /LOS_TOKEN is not set/);
  assert.throws(() => context(["fineract"], {}), /unknown client fineract/);
});
