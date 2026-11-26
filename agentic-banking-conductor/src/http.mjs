// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// The HTTP plumbing the workers share: a small client for one service (the
// LOS, Fineract, Ollama, an agent's runner) that answers like OpenFn's
// language-http, so the jobs' code ports unchanged: `(await los.get(path)).data`,
// and an error carrying `statusCode` and the parsed `body` when the service
// answers with an error status.

export class HttpError extends Error {
  constructor(method, path, statusCode, body) {
    // The path only: query strings and bodies can carry personal data, and
    // this message becomes the task's reason for failing, shown in Conductor.
    super(`${method} ${path} answered ${statusCode}`);
    this.name = "HttpError";
    this.statusCode = statusCode;
    this.body = body;
  }
}

/**
 * A client for one service.
 *
 * @param {string} baseUrl  e.g. http://los:8000
 * @param {object} [options]
 * @param {Record<string, string>} [options.headers]  sent with every request (a bearer token)
 * @param {number} [options.timeoutMs]  per request (default 60 s)
 * @param {AbortSignal} [options.signal]  the task's deadline: aborts any request
 *   still running when it fires, so a job's calls together cannot outlast it
 */
export function client(baseUrl, { headers = {}, timeoutMs = 60_000, signal } = {}) {
  async function request(method, path, { query, body, headers: extra, timeoutMs: timeout = timeoutMs } = {}) {
    signal?.throwIfAborted();
    const url = new URL(path, baseUrl);
    for (const [key, value] of Object.entries(query || {})) {
      if (value !== undefined && value !== null) url.searchParams.set(key, String(value));
    }
    const limit = AbortSignal.timeout(timeout);
    const init = { method, headers: { accept: "application/json", ...headers, ...extra },
      signal: signal ? AbortSignal.any([limit, signal]) : limit };
    if (body !== undefined) {
      init.body = JSON.stringify(body);
      init.headers["content-type"] = "application/json";
    }
    const response = await fetch(url, init);
    const text = await response.text();
    let data = text;
    if (text) {
      try {
        data = JSON.parse(text);
      } catch {
        // Not JSON: keep the text
      }
    } else {
      data = null;
    }
    if (!response.ok) throw new HttpError(method, url.pathname, response.status, data);
    return { status: response.status, data };
  }
  return {
    get: (path, options) => request("GET", path, options),
    post: (path, body, options) => request("POST", path, { ...options, body }),
    put: (path, body, options) => request("PUT", path, { ...options, body }),
    patch: (path, body, options) => request("PATCH", path, { ...options, body }),
    request,
  };
}

/** A client that sends `Authorization: Bearer <token>`, as language-http does with `access_token`. */
export function bearerClient(baseUrl, token, options = {}) {
  return client(baseUrl, { ...options, headers: { ...(options.headers || {}), authorization: `Bearer ${token}` } });
}
