#!/usr/bin/env node
// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// Stands in for entrypoint.sh in the runner tests. Reads the case from stdin
// and acts on it: "sleep" never answers, "crash" exits 1 without output,
// anything else answers with one tool call and its arguments and stdin echoed.
import { readFileSync } from "node:fs";

const input = readFileSync(0, "utf8");
const emit = (event) => process.stdout.write(`${JSON.stringify(event)}\n`);

if (input.includes("sleep")) {
  setTimeout(() => {}, 60_000);
} else if (input.includes("crash")) {
  process.stderr.write("fake pi: crashed\n");
  process.exit(1);
} else {
  emit({ type: "session", version: 3 });
  emit({ type: "tool_execution_start", toolCallId: "c1", toolName: "mcp__fineract__search_clients", args: { nameQuery: "Ana" } });
  emit({ type: "tool_execution_end", toolCallId: "c1", toolName: "mcp__fineract__search_clients", result: { content: [] }, isError: false });
  emit({ type: "tool_execution_start", toolCallId: "c2", toolName: "mcp__fineract__get_client", args: { clientId: 7 } });
  emit({ type: "tool_execution_end", toolCallId: "c2", toolName: "mcp__fineract__get_client", result: { content: [] }, isError: true });
  emit({ type: "message_end", message: { role: "assistant", content: [{ type: "toolCall", name: "x" }], model: "fake" } });
  emit({
    type: "message_end",
    message: {
      role: "assistant",
      content: [{ type: "text", text: JSON.stringify({ args: process.argv.slice(2), stdin: input }) }],
      model: "fake-model",
      stopReason: "stop",
    },
  });
}
