# Mifos Pi Agent — Docker Image

Docker image that packages the [Pi Coding Agent](https://pi.dev/) (by [Earendil](https://earendil.com/)) with the Mifos X MCP Server, enabling AI-powered banking operations against an [Apache Fineract](https://fineract.apache.org/) instance.

## What's Inside

| Component | Version | Description |
|---|---|---|
| **Pi Agent** | `@earendil-works/pi-coding-agent` (1.x) | Terminal-based coding agent with MCP support |
| **Mifos MCP Server** | Python (FastMCP) | 49 banking tools exposed via Model Context Protocol |
| **Node.js** | 22 LTS (Bookworm) | Runtime for Pi Agent |
| **Python** | 3.14 | Runtime for MCP Server |

The image pre-configures Pi Agent to connect to the Mifos Fineract MCP server via stdio transport. It includes an `AGENTS.md` with banking domain context so the agent understands Fineract operations out of the box.

---

## Quick Start

### Docker Compose (Recommended)

```bash
# 1. Configure credentials
cd docker
cp .env.example .env
# Edit .env with your Fineract URL and LLM API key

# 2. Build and start
# Not needed for run-on-demand compose execution
# docker compose up -d

# 3. Run a query (print mode)
docker compose run --rm pi-agent -p "List all clients in the system"

# 4. Interactive TUI session
docker compose run --rm pi-agent
```

### Standalone Docker

```bash
# Build
docker build -t mifos/pi-agent -f docker/Dockerfile .

# Run in print mode
docker run --rm \
  -e MIFOSX_BASE_URL=https://sandbox.mifos.community/fineract-provider/api/v1 \
  -e MIFOSX_USERNAME=mifos \
  -e MIFOSX_PASSWORD=password \
  -e MIFOSX_TENANT_ID=default \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  mifos/pi-agent -p "Search for clients named John"

# Run interactive TUI
docker run -it --rm \
  -e MIFOSX_BASE_URL=https://sandbox.mifos.community/fineract-provider/api/v1 \
  -e MIFOSX_USERNAME=mifos \
  -e MIFOSX_PASSWORD=password \
  -e MIFOSX_TENANT_ID=default \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  mifos/pi-agent
```

---

## Kubernetes Deployment

```bash
# 1. Create namespace and PVCs
kubectl apply -f docker/k8s/namespace.yaml
kubectl apply -f docker/k8s/pvc.yaml

# 2. Configure secrets (edit values first!)
kubectl apply -f docker/k8s/secret.yaml

# 3. Deploy the agent
kubectl apply -f docker/k8s/deployment.yaml

# 4. Execute a query
kubectl exec -n mifos-pi-agent deploy/mifos-pi-agent -- pi -p "List all loan products"

# 5. Interactive session
kubectl exec -it -n mifos-pi-agent deploy/mifos-pi-agent -- pi
```

### K8s Manifests

| File | Description |
|---|---|
| `k8s/namespace.yaml` | Dedicated namespace `mifos-pi-agent` |
| `k8s/secret.yaml` | Fineract credentials and LLM API keys |
| `k8s/deployment.yaml` | Pi Agent pod (non-root, resource limits, PVC mounts) |
| `k8s/pvc.yaml` | Persistent volumes for workspace (5Gi) and Pi data (1Gi) |

---

## Configuration

### Environment Variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `FINERACT_BASE_URL` | Yes | `sandbox.mifos.community/...` | Fineract API URL |
| `FINERACT_USERNAME` | Yes | `mifos` | HTTP Basic Auth username |
| `FINERACT_PASSWORD` | Yes | `password` | HTTP Basic Auth password |
| `FINERACT_TENANT_ID` | No | `default` | Fineract tenant ID |
| `ANTHROPIC_API_KEY` | One of these | — | Anthropic API key |
| `OPENAI_API_KEY` | One of these | — | OpenAI API key |
| `GOOGLE_API_KEY` | One of these | — | Google AI API key |

> **Security**: Never bake API keys into the image. Pass them as environment variables at runtime or use Kubernetes Secrets.

### Pi Agent Customization

The image includes a pre-configured MCP integration at `~/.pi/mcp.json` and banking context via `~/.pi/agent/AGENTS.md`. You can override these by mounting your own files:

```bash
docker run -it --rm \
  -v ./my-agents.md:/home/pi/.pi/agent/AGENTS.md \
  -v ./my-mcp.json:/home/pi/.pi/mcp.json \
  -e ANTHROPIC_API_KEY=sk-ant-... \
  mifos/pi-agent pi
```

---

## Architecture

```
docker/
├── Dockerfile              # Multi-stage: Python MCP + Node Pi Agent
├── docker-compose.yml      # Compose deployment
├── .env.example            # Environment template
├── pi-mcp-config.json      # MCP server registration for Pi
├── AGENTS.md               # Banking domain context for Pi
├── README.md               # This file
└── k8s/
    ├── namespace.yaml      # K8s namespace
    ├── secret.yaml         # K8s secrets template
    ├── deployment.yaml     # Pi Agent deployment
    └── pvc.yaml            # Persistent volume claims
```

### How It Works

```
┌─────────────────────────────────────────────────┐
│                Docker Container                  │
│                                                  │
│  ┌────────────┐    stdio     ┌────────────────┐ │
│  │  Pi Agent   │─────────────│  Mifos MCP     │ │
│  │  (Node 22)  │             │  Server (Py3)  │ │
│  │             │◄────────────│                │ │
│  └──────┬──────┘    MCP      └───────┬────────┘ │
│         │                            │           │
│         │ LLM API                    │ REST API  │
└─────────┼────────────────────────────┼───────────┘
          │                            │
          ▼                            ▼
   ┌──────────────┐          ┌──────────────────┐
   │ Anthropic /   │          │ Apache Fineract  │
   │ OpenAI /      │          │ / Mifos X        │
   │ Google AI     │          │                  │
   └──────────────┘          └──────────────────┘
```

Pi Agent connects to the LLM provider of your choice, while the Mifos MCP Server handles all banking operations against the Fineract backend via REST. The MCP server runs as a stdio subprocess managed by Pi.

---

## Pi Agent Modes

| Mode | Command | Use Case |
|---|---|---|
| **Interactive** | `pi` | Full TUI for iterative banking operations |
| **Print** | `pi -p "query"` | Single-shot queries for scripts and CI/CD |
| **JSON** | `pi --mode json -p "query"` | Structured output for programmatic consumption |
| **RPC** | (via SDK) | Embed Pi in your own applications |

---

## Security

- Runs as non-root user (`pi`, UID 1000)
- Minimal base image (Debian Bookworm slim)
- No API keys baked into the image
- K8s deployment drops all Linux capabilities
- Read-only root filesystem compatible (K8s)

---

## License

[MPL-2.0](../LICENSE) — Copyright since 2025 Mifos Initiative
