# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
#
# One image for the Conductor side of the sandbox: the workers (one container
# per workflow, `node src/worker.mjs <workflow>`), the entry that starts
# workflows (`node src/entry.mjs`) and the one-shot setup that loads the
# definitions into Conductor (`node src/setup.mjs`).
FROM node:24-bookworm-slim
LABEL org.opencontainers.image.title="Agentic Mifos sandbox Conductor workers" \
      org.opencontainers.image.description="Workers, entry and setup for the commercial flow on Conductor OSS" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx"
WORKDIR /opt/conductor
COPY package.json package-lock.json .npmrc ./
# Exact versions from the lockfile; the SDK needs no install scripts.
RUN npm ci --omit=dev --ignore-scripts --no-audit --no-fund \
 && npm cache clean --force
COPY definitions/ definitions/
COPY src/ src/
# The node image's unprivileged user (uid 1000).
USER node
ENTRYPOINT ["node"]
