# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# Pi agent (github.com/earendil-works/pi) for the sandbox agents. An agent
# reaches Fineract only through its own MCP server and gets no other tools:
# no shell, no file access (see entrypoint.sh).
FROM node:24-bookworm-slim
LABEL org.opencontainers.image.title="Agentic Mifos sandbox Pi agent" \
      org.opencontainers.image.description="Pi coding agent limited to the tools of one Mifos X MCP server" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx"
WORKDIR /opt/pi
COPY package.json package-lock.json ./
# Exact versions from the lockfile; Pi needs no install scripts.
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
 && npm cache clean --force
COPY configure.mjs entrypoint.sh system-prompt.md /opt/pi-agent/
# PI_OFFLINE, PI_SKIP_VERSION_CHECK, PI_TELEMETRY: no update checks, model
# catalogue downloads or telemetry. The agent networks have no route out anyway.
ENV PATH=/opt/pi/node_modules/.bin:$PATH \
    PI_CODING_AGENT_DIR=/home/node/.pi/agent \
    PI_OFFLINE=1 \
    PI_SKIP_VERSION_CHECK=1 \
    PI_TELEMETRY=0
# The node image's unprivileged user (uid 1000).
USER node
WORKDIR /home/node
ENTRYPOINT ["/opt/pi-agent/entrypoint.sh"]
