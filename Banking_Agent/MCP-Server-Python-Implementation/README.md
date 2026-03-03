# MCP Server - Python Implementation

The **Python MCP Server** connects an AI agent (Llama 3.1 via Ollama) to your **Apache Fineract** banking backend. It wraps the Fineract REST API into 31 tools the AI can call directly using natural language.

---

## How It Works

```
You (natural language) → AI Agent → MCP Server → Apache Fineract
```

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

> Requires [Ollama](https://ollama.com) with `llama3.1`: `ollama pull llama3.1`

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

## Conversational Memory

The agent uses **SQLite-backed persistent memory** (`~/.mifos/agent_memory.db`). Each session gets a unique ID so tellers can resume past conversations.

| Command | Action |
|---------|--------|
| Press Enter at startup | Start a new session |
| Paste a session ID | Resume a previous session |
| `new` (during chat) | Start a fresh thread |
| `id` (during chat) | Print current session ID |
| `quit` | Exit and save session |

---

## Verified Workflows

### Individual Loan
```
1. "Find Bruce Wayne."
2. "Get his loan accounts."
3. "Show me the history for Loan ID 8."
4. "Apply a late fee of 500 to Loan ID 8."
```

### Group Loan (A7X)
```
1. "Create a lending group called A7X. The members are Matt Shadows (client ID 8) and Syn Gates (client ID 9)."
2. "Create a group loan for Group ID 2 for $25,000 over 12 months."
3. "Approve and disburse Loan ID 10."
4. "Get the details for Group ID 2."
```

### Overdue Check
```
"Show me all overdue loans for client ID 7."
```

---

## Docker

```bash
docker build -t mifos-mcp-server .
docker run -i --env-file .env mifos-mcp-server
```

**Claude Desktop config:**
```json
{
  "mcpServers": {
    "mifos-banking": {
      "command": "docker",
      "args": ["run", "-i", "--rm", "--env-file", "/path/to/.env", "mifos-mcp-server"]
    }
  }
}
```
