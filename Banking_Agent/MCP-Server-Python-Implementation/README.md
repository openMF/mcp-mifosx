# MCP Server - Python Implementation

The **Python MCP Server** connects an AI agent (Qwen 2.5 7B via Ollama) to your **Apache Fineract** banking backend. It wraps the Fineract REST API into 29 tools the AI can call directly using natural language.

---

## Architecture

This project implements a **Decoupled, API-First Architecture**. It establishes a deterministic, hallucination-free integration tier between any AI Agent and Apache Fineract.

```text
[Apache Fineract / Mifos X] 
             ↕️
[mcp_server.py] (Universal, Stateless MCP Server exposing 29 safe tools)
             ↕️
========================= (The MCP Standard Protocol Boundary) =========================
             ↕️
[Local AI Agent (agent.py)]
(Powered by Qwen 2.5 7B via Ollama)
```

### Why this Architecture?
1. **Universal Reusability:** The MCP Server (`mcp_server.py`) is completely standalone. It does not contain any LLM logic. It can be paired with any local inference engine or agent framework.
2. **Anti-Hallucination Guardrails:** The server safely slims down verbose Fineract responses and pre-validates all IDs and statuses before executing dangerous mutations.
3. **Data Privacy & Sovereignty:** By connecting local Open-Source models (like Qwen 2.5 7B) directly to the MCP Server via Ollama, all sensitive banking data, PII, and financial records stay strictly within your local infrastructure. No data is sent to third-party AI providers or external APIs.
4. **Deterministic execution:** All banking actions are mapped to typed tools, ensuring the AI performs operations reliably without free-form hallucination of API calls.

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

| Category | Tools |
|----------|-------|
| **Clients** | Search, create, activate, update phone, close |
| **Groups** | Create lending group (with members), view group details |
| **Loans** | Create (individual & group), approve & disburse, repay, reject, apply fee, waive interest |
| **Loan History** | Full transaction history (disbursements, accruals, charges, outstanding balance) |
| **Overdue Loans** | List all in-arrears loans for a client |
| **Savings** | Create, approve, deposit, withdraw, apply charge, post interest |

---

## Historical Context Memory

The agent implements **Persistent Historical Context Memory** backed by SQLite (`~/.mifos/agent_memory.db`). This allows the agent to remember all previous interactions, client context, and multi-step workflows across different sessions. 

Tellers can resume past conversations or start fresh threads instantly:

| Command | Action |
|---------|--------|
| Press Enter at startup | Start a new session |
| Paste a session ID | Resume a previous session |
| `new` (during chat) | Start a fresh thread |
| `id` (during chat) | Print current session ID |
| `quit` | Exit and save session |

---

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
