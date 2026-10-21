#!/bin/sh
# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# Writes this agent's Pi configuration (configure.mjs), then runs Pi with only
# the tools of the agent's MCP server. Arguments are passed on to Pi:
#   -p "<prompt>"              answer one prompt, then exit
#   --mode json "<prompt>"     the same, as JSONL events
#   (none)                     interactive session (needs a terminal)
# Without a terminal Pi reads stdin into the prompt, so close it (< /dev/null).
set -eu

node /opt/pi-agent/configure.mjs

# --tools 'mcp__fineract__*'  only the MCP server's tools. Pi's own tools
#     (bash, read, edit, write, ...) are off, so the model cannot run commands
#     or read files, including this container's environment.
# --no-extensions --extension builtin:mcp  no extension but the MCP client.
# --no-session  nothing is written to disk: conversations hold client data.
# --no-approve, --no-context-files, --no-skills, --no-prompt-templates
#     nothing is loaded from the working folder.
exec pi \
  --offline \
  --no-session \
  --no-approve \
  --no-context-files \
  --no-skills \
  --no-prompt-templates \
  --no-extensions --extension builtin:mcp \
  --tools 'mcp__fineract__*' \
  --provider sandbox --model "$AGENT_MODEL" \
  --system-prompt /opt/pi-agent/system-prompt.md \
  "$@"
