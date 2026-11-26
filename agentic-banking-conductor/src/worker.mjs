// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// A worker for one workflow's tasks:
//
//   node src/worker.mjs <workflow>      e.g. intake
//
// It loads src/jobs/<workflow>.mjs, builds only the clients that workflow
// needs from its environment (so each worker container holds only its own
// credentials), and polls Conductor for the workflow's tasks. Each task gets
// the state the task before it returned, the way OpenFn passes its state from
// job to job, and returns the next state as its output.
//
// Environment:
//   CONDUCTOR_URL              http://conductor:8080
//   LOS_URL, LOS_TOKEN         the LOS and this workflow's caller token
//   WORKER_CONCURRENCY         tasks of each type run at a time (default 2)
//   WORKER_POLL_INTERVAL_MS    between polls when idle (default 500)
//   WORKER_DRAIN_SECONDS       on stop, how long to wait for work in flight
//                              (default: drainSeconds(), intake 188; its
//                              stop_grace_period is 195)
//
// Bounds. A poll under way ends within SDK_CALL_SECONDS. A task's run is cut
// at its time limit (definitions/tasks.json, timeoutSeconds): every call a job
// makes goes through its clients, which abort when the task's deadline fires.
// Reporting its result ends within RESULT_SECONDS. A stop waits for all three,
// so it never cuts a job between two calls. Past the drain, only a report can
// be lost, after a finished job: the workflow then fails and is asked again.
import { createConductorClient, TaskManager } from "@io-orkes/conductor-javascript";

import { bearerClient } from "./http.mjs";
import { loadDefinitions } from "./setup.mjs";

const NAME = /^[a-z]+$/;

// The SDK's calls to Conductor (local), SDK 4.0.0. One call's timeout
// (CONDUCTOR_REQUEST_TIMEOUT_MS) covers the call with its retries, but a
// back-off sleep already started is not cut short: after a connection error
// the SDK waits 1, 2, 3 s (+10%) between tries (retryFetch), so a call ends
// within SDK_CALL_SECONDS. Its 429 back-off (up to 16 s) does not apply:
// Conductor OSS answers 429 only from its MCP and workflow message queue
// endpoints, not task polls or updates.
export const CONDUCTOR_REQUEST_TIMEOUT_MS = 10_000;
export const SDK_CALL_SECONDS = 14;
// Reporting a result: RESULT_ATTEMPTS attempts, with 10 s, 20 s, ... between
// them (TaskRunner.updateTaskWithRetry). Each attempt counts as two calls:
// when /api/tasks/update-v2 answers 404 or 405, the SDK sends the legacy
// update in the same attempt, whatever happened before.
export const RESULT_ATTEMPTS = 3;
export const RESULT_SECONDS = RESULT_ATTEMPTS * 2 * SDK_CALL_SECONDS
  + Array.from({ length: RESULT_ATTEMPTS - 1 }, (_, i) => (i + 1) * 10).reduce((a, b) => a + b, 0);

function required(env, key) {
  const value = env[key];
  if (!value) throw new Error(`${key} is not set`);
  return value;
}

/** The clients a job may ask for (its `needs`), built from the environment, bound to a task's deadline. */
export const CLIENTS = {
  los: (env, signal) => bearerClient(required(env, "LOS_URL"), required(env, "LOS_TOKEN"), { signal }),
};

/** The context a job's task gets: the clients it needs, and nothing else, bound to the task's deadline. */
export function context(needs, env = process.env, signal = undefined) {
  const ctx = {};
  for (const need of needs) {
    if (!CLIENTS[need]) throw new Error(`unknown client ${need}`);
    ctx[need] = CLIENTS[need](env, signal);
  }
  return ctx;
}

/**
 * Wraps a job's task as a Conductor worker: the task's input `state` in, the
 * returned state out as its output; `log` lines go to the task's logs in
 * Conductor (as console.log goes to a run's log in Lightning) and to stdout.
 * A thrown error fails the task with its message, and with retries off
 * (definitions/tasks.json) the workflow fails: the officer asks again.
 *
 * @param {(signal: AbortSignal) => object} contextFor  the job's clients, bound to a deadline
 * @param {object} options
 * @param {number} options.timeoutSeconds  the task's time limit: its calls are aborted after it
 */
export function conductorWorker(taskDefName, run, contextFor, { timeoutSeconds, concurrency = 2, pollInterval = 500 }) {
  return {
    taskDefName,
    concurrency,
    pollInterval,
    execute: async (task) => {
      const logs = [];
      const log = (...parts) => {
        const line = parts.map((p) => (typeof p === "string" ? p : JSON.stringify(p))).join(" ");
        logs.push({ log: line, taskId: task.taskId, createdTime: Date.now() });
        console.log(`${taskDefName} ${task.workflowInstanceId}: ${line}`);
      };
      const deadline = new AbortController();
      const timer = setTimeout(() => deadline.abort(new Error(`${taskDefName} ran past its time limit of ${timeoutSeconds} s`)),
        timeoutSeconds * 1000);
      try {
        const state = (task.inputData && task.inputData.state) || {};
        const outputData = await run(state, { ...contextFor(deadline.signal), log });
        return { status: "COMPLETED", outputData, logs };
      } catch (e) {
        // A call cut by the deadline rejects with the deadline's reason
        const reason = deadline.signal.aborted ? deadline.signal.reason : e;
        log(`failed: ${reason.message}`);
        return { status: "FAILED", reasonForIncompletion: String(reason.message).slice(0, 500), logs };
      } finally {
        clearTimeout(timer);
      }
    },
  };
}

/**
 * Follows the work a worker has taken on, through the SDK's events, so a stop
 * can wait for it: stopPolling() takes no new tasks but waits neither for a
 * poll already under way (whose tasks the SDK still runs) nor for running
 * tasks, and exiting mid-task could stop a job between two LOS calls (intake:
 * after creating a lead, before setting it aside). Busy while a poll is under
 * way, while tasks a poll returned have not started, and until each started
 * task's result has reached Conductor.
 */
export function inFlight() {
  const tasks = new Set();
  let polls = 0;
  let claimed = 0; // returned by a poll, not started yet
  let waiting = [];
  const busy = () => polls > 0 || claimed > 0 || tasks.size > 0;
  const settle = () => {
    if (!busy()) {
      waiting.forEach((resolve) => resolve(true));
      waiting = [];
    }
  };
  const finished = (event) => {
    tasks.delete(event.taskId);
    settle();
  };
  return {
    listener: {
      onPollStarted: () => {
        polls++;
      },
      onPollCompleted: (event) => {
        polls = Math.max(0, polls - 1);
        claimed += event.tasksReceived || 0;
        settle();
      },
      onPollFailure: () => {
        polls = Math.max(0, polls - 1);
        settle();
      },
      onTaskExecutionStarted: (event) => {
        claimed = Math.max(0, claimed - 1);
        tasks.add(event.taskId);
      },
      // Conductor accepted the result, or the SDK gave up sending it
      onTaskUpdateCompleted: finished,
      onTaskUpdateFailure: finished,
    },
    /** Tasks taken on and not finished: started, or returned by a poll and not started yet. */
    get size() {
      return tasks.size + claimed;
    },
    /** True once nothing is in flight; false if something still is after timeoutMs. */
    drained(timeoutMs) {
      if (!busy()) return Promise.resolve(true);
      return new Promise((resolve) => {
        const timer = setTimeout(() => resolve(false), timeoutMs);
        waiting.push(() => {
          clearTimeout(timer);
          resolve(true);
        });
      });
    },
  };
}

// The longest delay setTimeout takes (2^31 - 1 ms); a longer one runs at once
const MAX_TIMER_SECONDS = Math.floor((2 ** 31 - 1) / 1000);

/**
 * How long a stop waits, at most: a poll under way (SDK_CALL_SECONDS), then a
 * task it returned run to its time limit (definitions/tasks.json; its run is
 * cut there), then its result reported (RESULT_SECONDS).
 */
export function drainSeconds(taskDefs, taskNames, env = process.env) {
  const override = env.WORKER_DRAIN_SECONDS;
  if (override) {
    // Seconds as a plain number: "195s" would be NaN, which setTimeout runs
    // after 1 ms, and a stop would then not wait at all
    const seconds = /^\d+(\.\d+)?$/.test(override) ? Number(override) : NaN;
    if (!(seconds <= MAX_TIMER_SECONDS)) {
      throw new Error(`WORKER_DRAIN_SECONDS must be a number of seconds up to ${MAX_TIMER_SECONDS}, e.g. 195; got ${JSON.stringify(override)}`);
    }
    return seconds;
  }
  return SDK_CALL_SECONDS + Math.max(0, ...timeLimits(taskDefs, taskNames).values()) + RESULT_SECONDS;
}

/** Each of the worker's tasks with its time limit; a task without a definition is an error. */
export function timeLimits(taskDefs, taskNames) {
  const limits = new Map();
  for (const name of taskNames) {
    const def = taskDefs.find((t) => t.name === name);
    if (!def || !(def.timeoutSeconds > 0)) throw new Error(`${name} has no time limit in definitions/tasks.json`);
    limits.set(name, def.timeoutSeconds);
  }
  return limits;
}

async function main() {
  const name = process.argv[2];
  if (!NAME.test(name || "")) throw new Error("usage: node src/worker.mjs <workflow>, e.g. intake");
  const job = await import(`./jobs/${name}.mjs`);
  context(job.needs); // fails at start when a credential is missing
  const options = {
    concurrency: Number(process.env.WORKER_CONCURRENCY || 2),
    pollInterval: Number(process.env.WORKER_POLL_INTERVAL_MS || 500),
  };
  const { tasks: taskDefs } = await loadDefinitions();
  const limits = timeLimits(taskDefs, Object.keys(job.tasks));
  const drain = drainSeconds(taskDefs, Object.keys(job.tasks));
  const client = await createConductorClient({
    serverUrl: required(process.env, "CONDUCTOR_URL"),
    requestTimeoutMs: CONDUCTOR_REQUEST_TIMEOUT_MS,
  });
  const workers = Object.entries(job.tasks).map(([taskDefName, run]) => conductorWorker(taskDefName, run,
    (signal) => context(job.needs, process.env, signal), { ...options, timeoutSeconds: limits.get(taskDefName) }));
  const tracker = inFlight();
  const manager = new TaskManager(client, workers, {
    options: { workerID: `${name}-worker` },
    maxRetries: RESULT_ATTEMPTS,
    eventListeners: [tracker.listener],
  });
  manager.startPolling();
  console.log(`${name} worker: polling for ${workers.map((w) => w.taskDefName).join(", ")}`);
  // docker stop: take no new tasks, let the poll under way and the running
  // tasks finish and report, then exit (the container's stop_grace_period
  // must exceed the drain). A task Conductor hands over with the last result
  // (update-v2) is dropped by the SDK once polling stops: it never started, so
  // it changed nothing, and Conductor times it out, failing its workflow.
  const stop = async (signal) => {
    console.log(`${name} worker: ${signal}: no new tasks; ${tracker.size} in flight; waiting up to ${drain} s`);
    await manager.stopPolling();
    const drained = await tracker.drained(drain * 1000);
    console.log(drained ? `${name} worker: stopped` : `${name} worker: stopped with ${tracker.size} task(s) unfinished`);
    process.exit(0);
  };
  process.once("SIGTERM", () => stop("SIGTERM"));
  process.once("SIGINT", () => stop("SIGINT"));
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => {
    console.error(`worker: ${e.message}`);
    process.exit(1);
  });
}
