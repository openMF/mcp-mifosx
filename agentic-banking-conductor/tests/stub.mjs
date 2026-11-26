// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// A stand-in HTTP service for the tests (the LOS, Conductor): answers with a
// handler and records every request it gets.
import { createServer } from "node:http";

/**
 * @param {(req: {method, path, query, headers, body}) => [number, any] | Promise<[number, any]>} handler
 * @returns {Promise<{url: string, requests: object[], close: () => Promise<void>}>}
 */
export async function stub(handler) {
  const requests = [];
  const server = createServer(async (req, res) => {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const text = Buffer.concat(chunks).toString("utf8");
    const url = new URL(req.url, "http://stub");
    const request = {
      method: req.method,
      path: url.pathname,
      query: Object.fromEntries(url.searchParams),
      headers: req.headers,
      body: text ? JSON.parse(text) : undefined,
    };
    requests.push(request);
    const [status, body] = await handler(request);
    const out = typeof body === "string" ? body : JSON.stringify(body ?? null);
    res.writeHead(status, { "content-type": typeof body === "string" ? "text/plain" : "application/json" });
    res.end(out);
  });
  await new Promise((resolve) => server.listen(0, "127.0.0.1", resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
