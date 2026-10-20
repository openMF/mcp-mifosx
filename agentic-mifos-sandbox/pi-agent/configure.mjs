// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
//
// Writes this agent's Pi configuration from the environment:
//   AGENT_MCP_URL  the agent's MCP server (streamable HTTP), e.g. http://mcp-decision:8000/mcp
//   AGENT_LLM_URL  an OpenAI-compatible API, e.g. Ollama's http://ollama:11434/v1
//   AGENT_MODEL    the model to use, e.g. llama3.2:3b
import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";

function required(name) {
  const value = process.env[name]?.trim();
  if (!value) fail(`set ${name}`);
  return value;
}

function url(name) {
  const value = required(name);
  if (!URL.canParse(value)) fail(`${name} is not a URL: ${value}`);
  return value;
}

function fail(message) {
  console.error(`pi-agent: ${message}`);
  process.exit(2);
}

const agentDir = required("PI_CODING_AGENT_DIR");
const mcpUrl = url("AGENT_MCP_URL");
const llmUrl = url("AGENT_LLM_URL");
const model = required("AGENT_MODEL");

mkdirSync(agentDir, { recursive: true, mode: 0o700 });
const write = (file, config) =>
  writeFileSync(join(agentDir, file), `${JSON.stringify(config, null, 2)}\n`, { mode: 0o600 });

// One MCP server named "fineract": its tools reach the model as
// mcp__fineract__<tool>. "direct" declares them to the model like Pi's own
// tools. The tool profiles are small, and small local models cannot write the
// scripts that Pi's default "codemode" exposure needs.
write("mcp.json", {
  mcpServers: {
    fineract: {
      url: mcpUrl,
      exposure: "direct",
      description: "Mifos X core banking (Apache Fineract), limited to what this agent may do",
    },
  },
});

// Ollama ignores the API key, but Pi only offers models that have one.
write("models.json", {
  providers: {
    sandbox: { baseUrl: llmUrl, api: "openai-completions", apiKey: "none", models: [{ id: model }] },
  },
});
