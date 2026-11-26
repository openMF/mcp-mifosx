// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The way into the Conductor workflows for channels and officers. Conductor
// OSS has no authentication, so its API is not published for them: they call
// this entry with an API key, as they call Lightning's webhooks.
//
//   POST /i/<workflow>   x-api-key: <the channels' or the officers' key>
//                        {the workflow's input, a JSON object}
//   GET  /health
//
// Replies, the same as Lightning's webhooks:
//   after_completion   201 {"data": <the workflow's result>, "meta": {"state": "success", "workflow_id"}}
//                      once the workflow has finished; 504 {"error": "timeout", ...} when it
//                      outlasts the reply deadline (30 s) and goes on; 500 with meta.state
//                      "failed" when it fails
//   at_once            202 {"workflow_id"}: the result is recorded in the LOS
//   401 without the route's key, 404 for an unknown workflow, 502 when
//   Conductor refuses ("conductor_error") or does not answer ("conductor_unavailable"),
//   with Conductor's status in the entry's log only
//
// Environment: CONDUCTOR_URL (http://conductor:8080), ENTRY_INTAKE_API_KEY
// (channels), ENTRY_OFFICER_API_KEY (officers), ENTRY_PORT (8000).
import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";

/** The workflows the entry starts: who may, and how it replies. */
export const ROUTES = {
  intake: { workflow: "opportunity_intake", version: 1, key: "intake", reply: "after_completion" },
};

const KEY_VARIABLES = { intake: "ENTRY_INTAKE_API_KEY", officer: "ENTRY_OFFICER_API_KEY" };
const MAX_BODY = 64 * 1024;
const FAILED = new Set(["FAILED", "TIMED_OUT", "TERMINATED"]);

function digest(value) {
  return createHash("sha256").update(String(value)).digest();
}

/** The same answer for a missing and a wrong key, compared in constant time. */
function keyMatches(given, expected) {
  return typeof given === "string" && timingSafeEqual(digest(given), digest(expected));
}

function send(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json", "content-length": Buffer.byteLength(text) });
  res.end(text);
}

// A body over the limit is read to its end but not kept, so the 413 still
// reaches the caller (nginx in front refuses one over 64 KiB already).
function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size <= MAX_BODY) chunks.push(chunk);
    });
    req.on("end", () => {
      if (size > MAX_BODY) reject(Object.assign(new Error("too large"), { status: 413 }));
      else resolve(Buffer.concat(chunks).toString("utf8"));
    });
    req.on("error", reject);
  });
}

/** Keys from the environment: each route's key must be set, and long enough to be a secret. */
export function keysFromEnv(env = process.env, routes = ROUTES) {
  const keys = {};
  for (const route of Object.values(routes)) {
    const variable = KEY_VARIABLES[route.key];
    const value = env[variable];
    if (!value || value.length < 32) throw new Error(`${variable} must be set, at least 32 characters`);
    keys[route.key] = value;
  }
  return keys;
}

/**
 * @param {object} options
 * @param {string} options.conductorUrl
 * @param {Record<string, string>} options.keys   by route key ("intake", "officer")
 * @param {Record<string, object>} [options.routes]
 * @param {number} [options.waitSeconds]  the reply deadline of after_completion routes
 */
export function createEntry({ conductorUrl, keys, routes = ROUTES, waitSeconds = 30 }) {
  // accept: the execute call answers JSON; the start call answers the new
  // workflow's id as text/plain
  async function conductor(path, body, accept = "application/json") {
    const response = await fetch(new URL(path, conductorUrl), {
      method: "POST",
      headers: { "content-type": "application/json", accept },
      body: JSON.stringify(body),
      signal: AbortSignal.timeout((waitSeconds + 15) * 1000),
    });
    const text = await response.text();
    if (!response.ok) {
      // Conductor is up but refused: an undefined workflow (404), an invalid
      // input. The status only: its answer can echo the input, so it is neither
      // returned nor logged
      throw Object.assign(new Error(`Conductor answered ${response.status}`), { refused: true });
    }
    return text;
  }

  // The execute response carries no failure reason of its own (3.34.1): it is
  // on the failed task, and in the workflow's record as a summary of it.
  async function failureReason(run, workflowId) {
    const failed = (run.tasks || []).find((t) => t.reasonForIncompletion && /FAILED|TIMED_OUT|CANCELED/.test(t.status));
    if (failed) return failed.reasonForIncompletion;
    if (run.reasonForIncompletion) return run.reasonForIncompletion;
    if (!workflowId) return null;
    try {
      const response = await fetch(new URL(`/api/workflow/${encodeURIComponent(workflowId)}`, conductorUrl),
        { headers: { accept: "application/json" }, signal: AbortSignal.timeout(5000) });
      if (response.ok) return (await response.json()).reasonForIncompletion || null;
    } catch {
      // No reason, rather than no answer
    }
    return null;
  }

  async function afterCompletion(route, input) {
    const query = new URLSearchParams({ waitForSeconds: String(waitSeconds) });
    const text = await conductor(`/api/workflow/execute/${route.workflow}/${route.version}?${query}`,
      { name: route.workflow, version: route.version, input });
    const run = JSON.parse(text);
    const workflowId = run.workflowId || run.targetWorkflowId;
    const status = run.status || run.targetWorkflowStatus;
    if (status === "COMPLETED") {
      // 201, as Lightning answers a webhook whose run succeeded
      return [201, { data: (run.output && run.output.data) || {}, meta: { state: "success", workflow_id: workflowId } }];
    }
    if (FAILED.has(status)) {
      return [500, { error: "workflow_failed",
        meta: { state: "failed", status, reason: await failureReason(run, workflowId), workflow_id: workflowId } }];
    }
    // Still running after the deadline: it goes on, as a Lightning run does
    return [504, { error: "timeout", meta: { status, workflow_id: workflowId } }];
  }

  async function atOnce(route, input) {
    const workflowId = await conductor(`/api/workflow/${route.workflow}?version=${route.version}`, input,
      "text/plain, application/json");
    return [202, { workflow_id: workflowId.trim() }];
  }

  return createServer(async (req, res) => {
    const started = Date.now();
    const path = new URL(req.url, "http://entry").pathname;
    let status = 500;
    let workflowId = "";
    try {
      if (req.method === "GET" && path === "/health") {
        status = 200;
        return send(res, 200, { status: "ok" });
      }
      const match = /^\/i\/([a-z]+)$/.exec(path);
      const route = match && Object.hasOwn(routes, match[1]) ? routes[match[1]] : null;
      if (!route) {
        status = 404;
        return send(res, 404, { error: "not_found" });
      }
      if (req.method !== "POST") {
        status = 405;
        return send(res, 405, { error: "method_not_allowed" });
      }
      if (!keyMatches(req.headers["x-api-key"], keys[route.key])) {
        status = 401;
        return send(res, 401, { error: "unauthorized" });
      }
      let input;
      try {
        input = JSON.parse((await readBody(req)) || "{}");
      } catch (e) {
        status = e.status || 400;
        return send(res, status, { error: status === 413 ? "too_large" : "invalid_json" });
      }
      if (!input || typeof input !== "object" || Array.isArray(input)) {
        status = 400;
        return send(res, 400, { error: "invalid_json", detail: "the body must be a JSON object" });
      }
      const [code, body] = await (route.reply === "at_once" ? atOnce(route, input) : afterCompletion(route, input));
      status = code;
      workflowId = (body.meta && body.meta.workflow_id) || body.workflow_id || "";
      return send(res, code, body);
    } catch (e) {
      // conductor_error: Conductor refused the request; conductor_unavailable: no
      // answer. Callers get only the code; the log gets Conductor's status or
      // the connection error.
      status = 502;
      console.error(`${req.method} ${path}: ${e.message}`);
      return send(res, 502, { error: e.refused ? "conductor_error" : "conductor_unavailable" });
    } finally {
      // No body or key in the log: they carry personal data and secrets
      console.log(`${req.method} ${path} ${status} ${workflowId} ${Date.now() - started}ms`);
    }
  });
}

if (import.meta.url === `file://${process.argv[1]}`) {
  try {
    const conductorUrl = process.env.CONDUCTOR_URL;
    if (!conductorUrl) throw new Error("CONDUCTOR_URL is not set");
    const server = createEntry({ conductorUrl, keys: keysFromEnv() });
    const port = Number(process.env.ENTRY_PORT || 8000);
    server.listen(port, "0.0.0.0", () => console.log(`entry: listening on ${port} for ${Object.keys(ROUTES).join(", ")}`));
    process.on("SIGTERM", () => server.close(() => process.exit(0)));
  } catch (e) {
    console.error(`entry: ${e.message}`);
    process.exit(1);
  }
}
