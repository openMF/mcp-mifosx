# MCP Server - Python Implementation

The **Python MCP Server** connects an AI agent (Qwen 2.5 7B via Ollama) to your **Apache Fineract** banking backend. It wraps the Fineract REST API into 29 tools the AI can call directly using natural language.

---

## Architecture

This project implements a **Decoupled, API-First Architecture**. It establishes a deterministic, hallucination-free integration tier between any AI Agent and Apache Fineract.

```text
Apache Fineract / Mifos X
                 ↕
mcp_server.py (FastMCP) — Stateless MCP Server exposing 29 typed tools
                 ↕
========================= (MCP Standard Protocol Boundary) =========================
                 ↕
Local AI Agent (agent.py) — Runs locally; uses Ollama or another LLM and calls tools over stdio
```

The diagram above shows the minimal, decoupled flow: the AI agent never talks directly to Fineract — it calls a small set of typed tools exposed by `mcp_server.py`. This separation reduces hallucinations, enforces validation, and centralizes access control and logging.

## Folder Architecture

This folder contains the Python MCP Server implementation and supporting tools. Key files and directories:

- `.env` / `.env.example` — Environment variables for Fineract and Ollama configuration.
- `Dockerfile` — Image build for the MCP server.
- `docker-compose.yml.example` — Example compose file for local development.
- `agent.py` — Local AI agent frontend that talks to the MCP server over stdio (uses Ollama via LangChain).
- `mcp_server.py` — The FastMCP server that registers and exposes the 29 MCP tools.
- `mcp_adapter.py` — Thin HTTP adapter for communicating with Apache Fineract (GET/POST/PUT/DELETE helpers).
- `requirements.txt` — Python dependencies for the MCP server and agent.
- `tools/` — Domain tool implementations grouped by domain and helper registry:
    - `tools/mcp_adapter.py` — alternative adapter used by the tools (initialises `fineract_client`).
    - `tools/registry.py` — Domain router that selects subsets of tools for the LLM.
    - `tools/domains/clients.py` — Client-related read/write tools (search, create, activate, update, close, groups).
    - `tools/domains/loans.py` — Loan-related tools (create, approve, disburse, repay, history, fees).
    - `tools/domains/savings.py` — Savings account tools (create, approve, deposit, withdraw, interest posting).
- `core/` — Core server components and helpers used by the MCP runtime.
- `LICENSE` / `README.md` — Project license and documentation.

### Why this Architecture?
1. **Universal Reusability:** The MCP Server (`mcp_server.py`) is completely standalone. It does not contain any LLM logic. It can be paired with any local inference engine or agent framework.
2. **Anti-Hallucination Guardrails:** The server safely slims down verbose Fineract responses and pre-validates all IDs and statuses before executing dangerous mutations.
3. **Data Privacy & Sovereignty:** By connecting local Open-Source models (like Qwen 2.5 7B) directly to the MCP Server via Ollama, all sensitive banking data, PII, and financial records stay strictly within your local infrastructure. No data is sent to third-party AI providers or external APIs.
4. **Deterministic execution:** All banking actions are mapped to typed tools, ensuring the AI performs operations reliably without free-form hallucination of API calls.
5. **Conversational Memory:** The agent preserves short-term conversational context during an active session so the AI can follow multi-step prompts and refer to recent exchanges without re-querying tools repeatedly.

6. **Historical Context Memory:** The agent implements persistent historical memory backed by SQLite (default path: `~/.mifos/agent_memory.db`). This allows the agent to remember previous interactions, retain validated client and loan IDs, and resume multi-step workflows across sessions — improving efficiency and auditability.

Tellers can resume past conversations or start fresh threads instantly using the interactive agent frontend:

| Command | Action |
|---------|--------|
| Press Enter at startup | Start a new session |
| Paste a session ID | Resume a previous session |
| `new` (during chat) | Start a fresh thread |
| `id` (during chat) | Print current session ID |
| `quit` | Exit and save session |

---

## Quick Start

**1. Install requirements**
```bash
pip install -r requirements.txt
```

**2. Configure your Fineract connection** — copy `.env.example` to `.env`:
```ini
MIFOSX_BASE_URL=https://localhost:8443/fineract-provider/api/v1
MIFOSX_TENANT_ID=default
MIFOSX_USERNAME=mifos
MIFOSX_PASSWORD=password
```

**3. Run the AI Agent**
```bash
python agent.py
```

> Requires [Ollama](https://ollama.com) with `qwen2.5:7b`: `ollama pull qwen2.5:7b`
> 
> To switch models, set `OLLAMA_MODEL=<model-name>` in your `.env` file (e.g. `llama3.1` or `qwen2.5:7b`).

---

## Available Tools (29)

The MCP server exposes 29 tools over the MCP boundary. They are grouped by domain below with the exact MCP tool names (these are the functions registered with `@mcp.tool()` in `mcp_server.py`).

- **Clients & Groups**
    - `search_clients` — Find client IDs by name.
    - `get_client` — Show key details for a specific client.
    - `get_client_accts` — Show all loans and savings accounts for a client.
    - `create_new_client` — Create a new client profile.
    - `activate_pending_client` — Activate a pending client.
    - `update_mobile` — Update a client's phone number.
    - `close_client_profile` — Close a client's profile.
    - `create_lending_group` — Create a lending group with members.
    - `get_group` — Show lending group details and members.

- **Loans**
    - `get_loan` — Get key details for a specific loan.
    - `get_repayment_sched` — Get the repayment schedule for a loan.
    - `get_loan_hist` — Get the full transaction history for a loan.
    - `create_new_loan` — Create a new loan application (individual).
    - `approve_disburse_loan` — Approve and disburse a pending loan.
    - `reject_loan` — Reject a pending loan application.
    - `make_repayment` — Make a repayment on an active loan.
    - `apply_fee` — Apply a fee to a loan.
    - `waive_loan_interest` — Waive interest on a loan.
    - `get_overdue_loans_for_client` — List overdue/in-arrears loans for a client.
    - `create_group_loan_app` — Create a group loan application.

- **Savings**
    - `get_savings` — Get key details of a savings account.
    - `get_savings_txns` — Get transactions for a savings account.
    - `create_savings` — Create a new savings account.
    - `approve_activate_savings` — Approve and activate a savings account.
    - `close_savings` — Close a savings account.
    - `deposit` — Deposit money into a savings account.
    - `withdraw` — Withdraw money from a savings account.
    - `apply_savings_fee` — Apply a charge to a savings account.
    - `calc_post_interest` — Calculate and post interest to a savings account.

If you want these tool names exposed differently (shorter names or aliases), tell me which ones to change and I'll update `mcp_server.py` and the README accordingly.

---


## Development & Docker

### Building the Image
```bash
docker build -t mifos-mcp-server .
```

### Running with .env
```bash
docker run -i --env-file .env mifos-mcp-server
```

> **Privacy Note:** When running via Docker, the container still communicates with your local Ollama instance or Fineract server, ensuring the internal data loop remains intact.

### Persisting data with Docker Compose

The provided `docker-compose.yml.example` already persists the MariaDB database using a named volume (`mariadb-data`). To ensure important state survives container recreation, you can either keep the named volume or bind it to a host directory.

Note: this repository keeps a working `docker-compose.yml` out of source control (it's listed in `.gitignore`). The recommended workflow is to copy the example to a local `docker-compose.yml`, customize mounts or services, and keep that local file private:

```bash
cp docker-compose.yml.example docker-compose.yml
# edit docker-compose.yml to add host volume mounts or additional services
```

- Keep named volume (default): data is stored by Docker and will persist until removed with `docker volume rm`.
- Bind to host directory (explicit, easier to backup): change the `volumes` entry for the `db` service to mount a host folder, for example:

```yaml
services:
    db:
        volumes:
            - ./mysql-init:/docker-entrypoint-initdb.d:ro
            - ./data/mysql:/var/lib/mysql    # bind DB files to ./data/mysql on host
```

If you run the agent or other services in containers and want to persist the agent's SQLite conversational memory (path: `~/.mifos/agent_memory.db` by default), add a volume mapping for that folder so the SQLite file is stored on the host:

```yaml
services:
    agent:
        image: python:3.11
        working_dir: /app
        volumes:
            - ./:/app
            - ./data/agent_memory:/root/.mifos   # persist SQLite memory to host
        command: python agent.py
```

Notes:
- Adjust the container path (`/root/.mifos`) to match the user inside your agent image (use `/home/<user>/.mifos` if appropriate).
- For Ollama (if you run Ollama in Docker), mount Ollama's model storage directory to a host path so pulled models persist across container restarts.
- Back up `./data` regularly or commit it to a secure backup system to preserve production data.

Bring the stack up with:

```bash
docker compose -f docker-compose.yml up -d
```

And stop it with:

```bash
docker compose -f docker-compose.yml down
```

When using `down`, include `--volumes` carefully if you want to remove persistent volumes; otherwise, data remains intact.
