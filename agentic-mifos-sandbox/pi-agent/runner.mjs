// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// HTTP front of an agent, so an OpenFn job can hand it a case:
//
//   POST /run    Authorization: Bearer <AGENT_RUNNER_TOKEN>
//                {"input": "<the case>", "timeout_seconds": 150}
//             -> {"output": "<Pi's final answer>", "tool_calls": [{"tool", "arguments", "is_error"}],
//                 "model", "stop_reason", "duration_ms"}
//   GET  /health
//
// Each case runs in a fresh Pi process started by entrypoint.sh, so with the
// same locked tools and settings as a command-line run, plus the agent's skill
// (AGENT_SKILL). Nothing carries over from one case to the next. One case runs
// at a time: the local model answers one request at a time anyway.
import { spawn } from "node:child_process";
import { createHash, timingSafeEqual } from "node:crypto";
import { createServer } from "node:http";
import { pathToFileURL } from "node:url";

const MAX_INPUT_BYTES = 64 * 1024;
const DEFAULT_TIMEOUT_SECONDS = 150;
// The final answer and the tool calls; Pi's event lines also carry tool results.
const MAX_EVENT_LINE_BYTES = 8 * 1024 * 1024;
const PROMPT = "Handle the case above as your instructions say.";

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

export function createRunner({ token, command, skill, maxTimeoutSeconds = 300 }) {
  if (!token) throw new Error("a runner token is required");
  const expected = digest(`Bearer ${token}`);
  const queue = new Queue();

  return createServer(async (req, res) => {
    try {
      const path = new URL(req.url, "http://runner").pathname;
      if (path === "/health" && req.method === "GET") return send(res, 200, { status: "UP" });
      if (path !== "/run") throw new HttpError(404, "not found");
      if (req.method !== "POST") throw new HttpError(405, "use POST");
      if (!timingSafeEqual(digest(req.headers.authorization ?? ""), expected)) throw new HttpError(401, "invalid token");

      const { input, timeoutSeconds } = parse(await readBody(req), maxTimeoutSeconds);
      const deadline = Date.now() + timeoutSeconds * 1000;
      const release = await queue.acquire(deadline);
      try {
        send(res, 200, await runPi({ command, skill, input, deadline }));
      } finally {
        release();
      }
    } catch (error) {
      const status = error instanceof HttpError ? error.status : 500;
      if (status >= 500) console.error(`runner: ${status} ${error.message}`);
      send(res, status, { error: error.message });
    }
  });
}

function parse(body, maxTimeoutSeconds) {
  let request;
  try {
    request = JSON.parse(body);
  } catch {
    throw new HttpError(400, "the body is not JSON");
  }
  const input = request?.input;
  if (typeof input !== "string" || !input.trim()) throw new HttpError(400, "input must be a non-empty string");
  const timeoutSeconds = request.timeout_seconds ?? Math.min(DEFAULT_TIMEOUT_SECONDS, maxTimeoutSeconds);
  if (!Number.isFinite(timeoutSeconds) || timeoutSeconds < 1 || timeoutSeconds > maxTimeoutSeconds) {
    throw new HttpError(400, `timeout_seconds must be between 1 and ${maxTimeoutSeconds}`);
  }
  return { input, timeoutSeconds };
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    req.on("data", (chunk) => {
      size += chunk.length;
      // Past the limit the rest is read and dropped, so the 413 still reaches the caller.
      if (size > MAX_INPUT_BYTES) reject(new HttpError(413, `the body is over ${MAX_INPUT_BYTES} bytes`));
      else chunks.push(chunk);
    });
    req.on("end", () => resolve(Buffer.concat(chunks).toString("utf8")));
    req.on("error", reject);
  });
}

// Runs one case: the case on stdin (Pi puts piped input before the prompt),
// Pi's JSON events on stdout.
function runPi({ command, skill, input, deadline }) {
  const args = ["--mode", "json", ...(skill ? ["--append-system-prompt", skill] : []), PROMPT];
  const started = Date.now();
  return new Promise((resolve, reject) => {
    const child = spawn(command, args, { stdio: ["pipe", "pipe", "pipe"] });
    const result = { output: "", tool_calls: [], model: null, stop_reason: null };
    const calls = new Map();
    let stderr = "";
    let pending = "";
    let timedOut = false;

    const timer = setTimeout(() => {
      timedOut = true;
      child.kill("SIGTERM");
      setTimeout(() => child.kill("SIGKILL"), 2000).unref();
    }, Math.max(0, deadline - Date.now()));

    child.stdout.setEncoding("utf8");
    child.stdout.on("data", (chunk) => {
      pending += chunk;
      if (pending.length > MAX_EVENT_LINE_BYTES && !pending.includes("\n")) {
        child.kill("SIGKILL");
        return;
      }
      let newline;
      while ((newline = pending.indexOf("\n")) >= 0) {
        record(pending.slice(0, newline), result, calls);
        pending = pending.slice(newline + 1);
      }
    });
    child.stderr.setEncoding("utf8");
    child.stderr.on("data", (chunk) => {
      stderr = (stderr + chunk).slice(-2000);
    });
    child.on("error", (error) => {
      clearTimeout(timer);
      reject(new HttpError(502, `could not start the agent: ${error.message}`));
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      record(pending, result, calls);
      result.tool_calls = [...calls.values()];
      result.duration_ms = Date.now() - started;
      if (timedOut) return reject(new HttpError(504, "the agent did not answer in time"));
      if (code !== 0 && !result.output) {
        return reject(new HttpError(502, `the agent exited with code ${code}: ${stderr.trim() || "no output"}`));
      }
      resolve(result);
    });

    child.stdin.on("error", () => {});
    child.stdin.end(input);
  });
}

// Collects the tool calls and the last assistant message from Pi's events.
function record(line, result, calls) {
  if (!line.trim()) return;
  let event;
  try {
    event = JSON.parse(line);
  } catch {
    return;
  }
  if (event.type === "tool_execution_start") {
    calls.set(event.toolCallId, { tool: toolName(event.toolName), arguments: event.args ?? {}, is_error: false });
  } else if (event.type === "tool_execution_end") {
    const call = calls.get(event.toolCallId) ?? { tool: toolName(event.toolName), arguments: {} };
    calls.set(event.toolCallId, { ...call, is_error: event.isError === true });
  } else if (event.type === "message_end" && event.message?.role === "assistant") {
    const text = (event.message.content ?? [])
      .filter((block) => block.type === "text")
      .map((block) => block.text)
      .join("");
    if (text.trim()) result.output = text;
    result.model = event.message.model ?? result.model;
    result.stop_reason = event.message.stopReason ?? result.stop_reason;
  }
}

// mcp__fineract__search_clients -> search_clients
function toolName(name = "") {
  return name.replace(/^mcp__.+?__/, "");
}

function digest(value) {
  return createHash("sha256").update(value).digest();
}

function send(res, status, body) {
  if (res.headersSent) return;
  res.writeHead(status, { "Content-Type": "application/json" });
  res.end(JSON.stringify(body));
}

// One case at a time; a case that cannot start before its deadline gets 503.
class Queue {
  #busy = false;
  #waiting = [];

  acquire(deadline) {
    if (!this.#busy) {
      this.#busy = true;
      return Promise.resolve(() => this.#release());
    }
    return new Promise((resolve, reject) => {
      const waiter = { resolve };
      waiter.timer = setTimeout(() => {
        this.#waiting = this.#waiting.filter((w) => w !== waiter);
        reject(new HttpError(503, "the agent is busy with another case"));
      }, Math.max(0, deadline - Date.now()));
      this.#waiting.push(waiter);
    });
  }

  #release() {
    const next = this.#waiting.shift();
    if (!next) {
      this.#busy = false;
      return;
    }
    clearTimeout(next.timer);
    next.resolve(() => this.#release());
  }
}

if (import.meta.url === pathToFileURL(process.argv[1] ?? "").href) {
  const port = Number(process.env.AGENT_RUNNER_PORT ?? 8080);
  createRunner({
    token: process.env.AGENT_RUNNER_TOKEN,
    command: process.env.AGENT_PI_COMMAND ?? "/opt/pi-agent/entrypoint.sh",
    skill: process.env.AGENT_SKILL,
    maxTimeoutSeconds: Number(process.env.AGENT_RUN_MAX_SECONDS ?? 300),
  }).listen(port, () => console.log(`runner: listening on :${port}`));
}
