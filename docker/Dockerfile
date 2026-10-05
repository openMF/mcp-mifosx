# ── Stage 1: Build the Python MCP server ──────────────────────────────
FROM node:22-bookworm-slim AS mcp-builder

RUN apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-venv && rm -rf /var/lib/apt/lists/*

WORKDIR /build
COPY python/requirements.txt .
RUN python3 -m venv /opt/venv && \
    /opt/venv/bin/pip install --no-cache-dir -r requirements.txt
COPY python/ .

# ── Stage 2: Pi Agent with MCP integration ────────────────────────────
FROM node:22-bookworm-slim

LABEL org.opencontainers.image.title="mifos-pi-agent" \
      org.opencontainers.image.description="Pi Coding Agent with Mifos X MCP Server — Dockerized for banking AI workflows" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.vendor="Mifos Initiative"

# ── System dependencies ──────────────────────────────────────────────
RUN apt-get update && apt-get install -y --no-install-recommends \
        git ripgrep ca-certificates curl python3 jq \
    && rm -rf /var/lib/apt/lists/*

# ── Install Pi Agent globally ────────────────────────────────────────
RUN npm install -g --ignore-scripts @earendil-works/pi-coding-agent

# ── Copy the pre-built Python MCP server ─────────────────────────────
COPY --from=mcp-builder /opt/venv /opt/venv
COPY --from=mcp-builder /build /opt/mcp-mifosx

# ── Create non-root user ─────────────────────────────────────────────
ARG PI_UID=1000
RUN useradd -m -u ${PI_UID} -s /bin/bash pi \
    && mkdir -p /home/pi/.pi/agent /workspace \
    && chown -R pi:pi /home/pi /workspace

# ── Configure MCP for Pi Agent ───────────────────────────────────────
# Pi reads MCP server config from ~/.pi/agent/mcp.json
COPY docker/pi-mcp-config.json /home/pi/.pi/agent/mcp.json
# Inject AGENTS.md for Fineract banking context
COPY docker/AGENTS.md /home/pi/.pi/agent/AGENTS.md
RUN chown -R pi:pi /home/pi/.pi

USER pi
WORKDIR /workspace

# ── Healthcheck ──────────────────────────────────────────────────────
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD pi --version || exit 1

# ── Default entrypoint: Pi in print mode for non-interactive usage ───
# Override with `docker run ... pi` for interactive TUI
ENTRYPOINT ["pi"]
CMD ["--help"]
