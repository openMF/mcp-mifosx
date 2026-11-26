// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// One-shot: loads the task definitions and the workflows in definitions/ into
// Conductor, on every `docker compose up`. Both calls create what is missing
// and update what is there, so a changed definition takes effect on the next
// `up`, and a failed setup can simply run again. Runs before the entry and
// the workers start.
//
//   node src/setup.mjs        CONDUCTOR_URL=http://conductor:8080
import { readdir, readFile } from "node:fs/promises";

const ROOT = new URL("../definitions/", import.meta.url);

/** The task definitions and workflows, read from definitions/. */
export async function loadDefinitions(root = ROOT) {
  const tasks = JSON.parse(await readFile(new URL("tasks.json", root), "utf8"));
  const dir = new URL("workflows/", root);
  const workflows = [];
  for (const file of (await readdir(dir)).filter((f) => f.endsWith(".json")).sort()) {
    workflows.push(JSON.parse(await readFile(new URL(file, dir), "utf8")));
  }
  return { tasks, workflows };
}

async function call(base, method, path, body) {
  const response = await fetch(new URL(path, base), {
    method,
    headers: { "content-type": "application/json", accept: "application/json" },
    body: JSON.stringify(body),
    signal: AbortSignal.timeout(30_000),
  });
  const text = await response.text();
  if (!response.ok) throw new Error(`${method} ${path} answered ${response.status}: ${text.slice(0, 500)}`);
  return text ? JSON.parse(text) : null;
}

/** Loads the definitions into Conductor; fails if Conductor refuses any of them. */
export async function register(base, { tasks, workflows }) {
  // POST creates task definitions and replaces existing ones with the same name
  await call(base, "POST", "/api/metadata/taskdefs", tasks);
  // PUT creates or updates each workflow (name and version). Conductor answers
  // 200 even when some fail, listing them in bulkErrorResults (name: error).
  const result = await call(base, "PUT", "/api/metadata/workflow", workflows);
  const errors = Object.entries((result && result.bulkErrorResults) || {});
  if (errors.length) {
    throw new Error(`Conductor refused ${errors.length} workflow(s): ${errors.map(([name, error]) => `${name}: ${error}`).join("; ")}`);
  }
}

async function main() {
  const base = process.env.CONDUCTOR_URL;
  if (!base) throw new Error("CONDUCTOR_URL is not set");
  const definitions = await loadDefinitions();
  await register(base, definitions);
  const { tasks, workflows } = definitions;
  console.log(`setup: ${tasks.length} task definitions, ${workflows.length} workflows: ${workflows.map((w) => w.name).join(", ")}`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch((e) => {
    console.error(`setup: ${e.message}`);
    process.exit(1);
  });
}
