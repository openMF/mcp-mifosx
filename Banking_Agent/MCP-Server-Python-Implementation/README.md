# MCP Server - Python Implementation

The **Python MCP Server** connects an AI agent (like Claude or Llama) to your **Apache Fineract** banking backend. It wraps the Fineract REST API into 26 easy-to-use tools the AI can call directly — things like creating clients, opening loans, and managing savings accounts.

---

## How It Works

```
You (natural language) → AI Agent → MCP Server → Apache Fineract
```

You type a command like `"Create a $20,000 loan for Bruce Wayne over 12 months"`.  
The AI picks the right tool, fills in the parameters, and executes it against your live Fineract database.

---

## Quick Start

**1. Install requirements**
```bash
pip install -r requirements.txt
```

**2. Configure your Fineract connection**

Copy `.env.example` to `.env` and fill in your details:
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

> Requires [Ollama](https://ollama.com) with `llama3.1` installed: `ollama pull llama3.1`

---

## Available Tools (26)

| Category | What you can do |
|----------|-----------------|
| **Clients** | Search, create, activate, update phone, close |
| **Loans** | Create, approve & disburse, repay, apply fee, waive interest, reject |
| **Savings** | Create, approve, deposit, withdraw, apply charge, post interest |
| **Groups** | Create and view lending groups |

---

## Verified Workflow Example

Run these prompts **one at a time** in the agent terminal:

```
1. "Find the client ID for Bruce Wayne."
2. "Create a new 20,000 loan for Client ID 7 over 12 months."
3. "Approve and disburse Loan ID 8 immediately."
4. "Apply a late fee of 500 to Loan ID 8."
```

All 4 steps execute real API calls against Fineract and return live confirmation.

---

## Docker

```bash
# Build
docker build -t mifos-mcp-server .

# Run (MCP over StdIO — for Claude Desktop, Cursor, etc.)
docker run -i --env-file .env mifos-mcp-server
```

**Claude Desktop config** (`claude_desktop_config.json`):
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
