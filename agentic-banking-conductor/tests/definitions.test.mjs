// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The definitions, the workers and the entry agree: every task in a workflow
// has a definition and a worker, every worker a definition, tasks hand their
// state on in order, and every route of the entry names a workflow.
import assert from "node:assert/strict";
import { readdir } from "node:fs/promises";
import { test } from "node:test";

import { ROUTES } from "../src/entry.mjs";
import { loadDefinitions } from "../src/setup.mjs";

const { tasks: taskDefs, workflows } = await loadDefinitions();
const defined = new Map(taskDefs.map((t) => [t.name, t]));

const workers = new Map(); // task name -> job module
for (const file of await readdir(new URL("../src/jobs/", import.meta.url))) {
  const job = await import(`../src/jobs/${file}`);
  for (const name of Object.keys(job.tasks)) {
    assert.ok(!workers.has(name), `${name} has two workers`);
    workers.set(name, file);
  }
}

test("task definitions: unique, retries off, time limits set", () => {
  assert.equal(defined.size, taskDefs.length, "task names are unique");
  for (const t of taskDefs) {
    assert.equal(t.retryCount, 0, `${t.name}: retries stay off, as in OpenFn`);
    assert.ok(t.timeoutSeconds > 0 && t.responseTimeoutSeconds > 0 && t.pollTimeoutSeconds > 0, `${t.name}: time limits`);
    assert.ok(t.responseTimeoutSeconds <= t.timeoutSeconds, `${t.name}: responseTimeoutSeconds <= timeoutSeconds`);
  }
});

test("every task in a workflow has a definition and a worker; every worker and definition is used", () => {
  const used = new Set();
  for (const wf of workflows) {
    for (const task of wf.tasks) {
      assert.equal(task.type, "SIMPLE", `${wf.name}/${task.name}: work happens in workers`);
      assert.ok(defined.has(task.name), `${wf.name}/${task.name} has a task definition`);
      assert.ok(workers.has(task.name), `${wf.name}/${task.name} has a worker`);
      used.add(task.name);
    }
  }
  for (const name of defined.keys()) assert.ok(used.has(name), `task definition ${name} is used by a workflow`);
  for (const name of workers.keys()) assert.ok(defined.has(name), `worker ${name} has a task definition`);
});

test("each task gets the state the one before returned, and the last one's is the result", () => {
  for (const wf of workflows) {
    assert.equal(wf.version, 1, `${wf.name}: version 1`);
    wf.tasks.forEach((task, i) => {
      assert.equal(task.taskReferenceName, task.name, `${wf.name}: reference names are the task names`);
      const expected = i === 0 ? { data: "${workflow.input}" } : `\${${wf.tasks[i - 1].taskReferenceName}.output}`;
      assert.deepEqual(task.inputParameters, { state: expected }, `${wf.name}/${task.name}: its input state`);
    });
    assert.deepEqual(wf.outputParameters, { data: `\${${wf.tasks.at(-1).taskReferenceName}.output}` }, `${wf.name}: output`);
  }
});

test("every route of the entry starts a defined workflow", () => {
  const names = new Map(workflows.map((w) => [w.name, w.version]));
  assert.equal(names.size, workflows.length, "workflow names are unique");
  for (const [route, { workflow, version, reply }] of Object.entries(ROUTES)) {
    assert.equal(names.get(workflow), version, `route ${route}: ${workflow} v${version} is defined`);
    assert.ok(["after_completion", "at_once"].includes(reply), `route ${route}: a known reply`);
  }
});
