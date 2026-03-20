# Mifos MCP Server — Python Implementation

The **Mifos MCP Server** is a standalone, stateless integration tier that bridges any AI assistant or agent framework to the **Apache Fineract** banking backend. It exposes **49 typed tools** via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io), making it compatible with any MCP-compliant client.

---

## What is this?

This is the **Server**, not a client or agent. It does one thing well: translate Fineract REST API operations into standardized MCP tools that any AI client can call safely.

```text
┌─────────────────────────────────────────────┐
│            Apache Fineract / Mifos X         │
└──────────────────────┬──────────────────────┘
                       │ REST API
┌──────────────────────▼──────────────────────┐
│         mcp_server.py  (This Repo)           │
│    Stateless MCP Server — 49 typed tools     │
└──────────────────────┬──────────────────────┘
                       │ MCP Standard Protocol (stdio / SSE)
         ┌─────────────┼──────────────┐
         ▼             ▼              ▼
   Mifos X WebApp   Claude Code     n8n / Custom
   AI Assistant     (claude.ai)     Workflow Agent
   (your client)   (external)       (your client)
```

**This repository is framework-agnostic.** The client (LLM brain, UI, memory) lives in a separate repository. Any MCP-compatible system can plug in.

---

## Quick Start

### 1. Clone and install dependencies

```bash
git clone https://github.com/openMF/mcp-mifosx.git
cd mcp-mifosx/Python/MCP-Server-Python-Implementation
pip install -r requirements.txt
```

### 2. Configure your Fineract connection

Copy `.env.example` to `.env` and fill in your details:

```ini
MIFOSX_BASE_URL=https://localhost:8443/fineract-provider/api/v1
MIFOSX_TENANT_ID=default
MIFOSX_USERNAME=mifos
MIFOSX_PASSWORD=password
```

### 3. Run the MCP Server

```bash
python mcp_server.py
```

The server starts on **stdio transport** — MCP clients connect to it using standard input/output.

### 4. (Optional) Run with Docker

```bash
docker build -t mifos-mcp-server .
docker run -i --env-file .env mifos-mcp-server
```

> **Fineract Backend:** This repository contains only the MCP Server. To run the Apache Fineract banking engine, use the official platform:
> 👉 **[openMF/mifosx-platform](https://github.com/openMF/mifosx-platform)**

---

## Connecting a Client

This server speaks the **MCP standard protocol**. Any MCP-compatible client can connect to it. Below are examples of how to integrate with common clients.

### Claude Desktop / Claude Code

Add the following to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mifos": {
      "command": "python",
      "args": ["/path/to/MCP-Server-Python-Implementation/mcp_server.py"],
      "env": {
        "MIFOSX_BASE_URL": "https://your-fineract-instance/api/v1",
        "MIFOSX_TENANT_ID": "default",
        "MIFOSX_USERNAME": "mifos",
        "MIFOSX_PASSWORD": "password"
      }
    }
  }
}
```

Claude will automatically discover all 49 tools and be able to perform banking operations via natural language.

### Custom Python Agent (Local / On-Premise)

For a local, privacy-first setup using **Ollama** (e.g., Qwen 2.5 7B):

```python
from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client
from langchain_mcp_adapters.tools import load_mcp_tools

server_params = StdioServerParameters(
    command="python",
    args=["mcp_server.py"],
)

async with stdio_client(server_params) as (read, write):
    async with ClientSession(read, write) as session:
        await session.initialize()
        tools = await load_mcp_tools(session)
        # Pass tools to your LLM agent
```

### n8n Workflow Automation

Connect n8n's **MCP Client node** to this server to build no-code banking automation workflows. Configure the node with the `stdio` transport and the `python mcp_server.py` command.

### Any MCP-Compatible Client

This server is **framework-agnostic**. Any application that speaks the MCP protocol can connect to it — whether it's a custom web app, a mobile backend, a desktop tool, or an automation workflow. The client provides the LLM and the UI; this server provides the Fineract tools.

---

## Available Tools (49)

All tools are registered in `mcp_server.py` with `@mcp.tool()` and are discoverable by any MCP client.

### 👤 Clients (16 tools)

| Tool | Description |
|---|---|
| `search_clients` | Find clients by name, returns clientId |
| `get_client` | Show key profile details for a client |
| `get_client_accts` | List all loan and savings accounts for a client |
| `create_new_client` | Create a new banking client profile |
| `activate_pending_client` | Activate a pending client |
| `update_mobile` | Update a client's mobile number |
| `close_client_profile` | Close a client's profile |
| `get_identifiers` | List client KYC documents |
| `add_identifier` | Add a KYC document to a client |
| `list_documents` | List uploaded files for a client |
| `list_client_charges` | List client-level fees |
| `apply_client_fee` | Apply a one-time charge to a client profile |
| `list_client_txns` | List financial transactions for a client |
| `get_addresses` | Show a client's registered addresses |
| `create_lending_group` | Create a lending group |
| `get_group` | Show group details and members |

### 👥 Groups & Centers (6 tools)

| Tool | Description |
|---|---|
| `list_all_groups` | List all lending groups |
| `activate_pending_group` | Activate a pending lending group |
| `add_member_to_group` | Add a client to a group |
| `list_all_centers` | List all centers |
| `get_center` | Show details for a center |
| `create_new_center` | Create a new center |

### 💳 Loans (11 tools)

| Tool | Description |
|---|---|
| `get_loan` | Get key details for a loan |
| `get_repayment_sched` | Get the repayment schedule |
| `get_loan_hist` | Get full transaction history for a loan |
| `get_overdue_loans_for_client` | List overdue loans for a client |
| `create_new_loan` | Create a new individual loan application |
| `create_group_loan_app` | Create a group loan application |
| `approve_disburse_loan` | Approve and disburse a pending loan |
| `reject_loan` | Reject a pending loan application |
| `make_repayment` | Make a repayment on an active loan |
| `apply_loan_fee` | Apply a fee/charge to a loan |
| `waive_loan_interest` | Waive interest on a loan |

### 💰 Savings (9 tools)

| Tool | Description |
|---|---|
| `get_savings` | Get key details of a savings account |
| `get_savings_txns` | Get transactions for a savings account |
| `create_savings` | Create a new savings account |
| `approve_activate_savings` | Approve and activate a savings account |
| `close_savings` | Close a savings account |
| `deposit` | Deposit money into a savings account |
| `withdraw` | Withdraw money from a savings account |
| `apply_savings_fee` | Apply a charge to a savings account |
| `calc_post_interest` | Post accrued interest to a savings account |

### 🏢 Staff & Offices (4 tools)

| Tool | Description |
|---|---|
| `list_all_staff` | List bank staff members |
| `get_staff` | Get details for a staff member |
| `list_all_offices` | List all bank branches/offices |
| `get_office` | Get details for a specific office |

### 📊 Accounting (3 tools)

| Tool | Description |
|---|---|
| `list_accounts` | List GL accounts (Chart of Accounts) |
| `list_journal_entries` | List journal entries by account or transaction ID |
| `record_journal_entry` | Record a manual debit/credit journal entry |

---

## Project Structure

```
MCP-Server-Python-Implementation/
├── mcp_server.py          # Main server - registers all MCP tools
├── requirements.txt       # Minimal dependencies (no LLM frameworks)
├── Dockerfile             # Container image for the MCP server
├── .env.example           # Environment variable template
├── tools/
│   ├── mcp_adapter.py     # Tool-level Fineract HTTP client
│   ├── registry.py        # Domain router
│   └── domains/
│       ├── clients.py     # Client & KYC tools
│       ├── groups.py      # Group & Center tools
│       ├── loans.py       # Loan lifecycle tools
│       ├── savings.py     # Savings account tools
│       ├── staff.py       # Staff & Office tools
│       └── accounting.py  # GL Accounts & Journal Entry tools
└── core/
    └── api_server.py      # Optional HTTP/SSE transport layer
```

---

## Why a Pure Connector?

**This repository deliberately contains no LLM logic, no agent memory, and no system prompts.** That code belongs in your client repository.

By keeping this server "pure," you gain:

1. **Universal Compatibility** — Works with Claude, GPT-4, Qwen, LLaMA, Mistral, or any MCP client.
2. **Data Sovereignty** — The server makes no external calls. Pair it with a local Ollama instance to keep all data on-premise.
3. **Guardrails** — The server pre-validates IDs and statuses before executing any mutation, preventing hallucinated actions.
4. **Team Scalability** — One team owns the Connector (this repo). Another team owns the AI Assistant (their repo).

---

## Environment Variables

| Variable | Description | Default |
|---|---|---|
| `MIFOSX_BASE_URL` | Full base URL of your Fineract REST API | — |
| `MIFOSX_TENANT_ID` | Fineract tenant identifier | `default` |
| `MIFOSX_USERNAME` | Fineract API username | `mifos` |
| `MIFOSX_PASSWORD` | Fineract API password | `password` |

---

## Testing with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the official tool for exploring and testing MCP servers interactively in a browser UI.

### Step 1 — Launch the Inspector

Run this command from inside the `MCP-Server-Python-Implementation/` directory:

```bash
cd Python/MCP-Server-Python-Implementation
DANGEROUSLY_OMIT_AUTH=true npx @modelcontextprotocol/inspector python mcp_server.py
```

This will:
- Start the MCP Inspector proxy on `localhost:6277`
- Open the Inspector UI at `http://localhost:6274`
- Automatically spawn `mcp_server.py` as the stdio server

### Step 2 — Connect in the Browser

Once the browser opens:

1. **Transport:** `STDIO` (already pre-selected)
2. **Command:** `python` (already pre-filled)
3. **Arguments:** `mcp_server.py` (already pre-filled)
4. Click **"Connect"**

> ⚠️ Do **not** try to connect to a separately running `mcp_server.py`. The Inspector **spawns** the server itself via stdio.

### Step 3 — Browse All 49 Tools

After connecting, click the **"Tools"** tab in the left sidebar. You will see all 49 tools listed with their names, descriptions, and input schemas. You can:

- **Click any tool** to expand its input form
- **Fill in parameters** and click **"Call Tool"** to invoke it live against Fineract
- **Inspect the raw JSON response** returned by the tool

### Programmatic Smoke Test

To quickly verify registration and API connectivity without the browser:

```bash
cd Python/MCP-Server-Python-Implementation
python test_tools.py
```

Expected output:
```
  ✅ PASS — All 49 tools registered with unique names
  ✅ list_offices          : OK
  ✅ list_staff            : OK
  ✅ search_clients (Bruce): OK
  ✅ get_overdue_loans (1) : OK
```

- `✅` = Fineract responded successfully
- `⚠️` = Fineract running but resource not found (expected for missing data)
- `❌` = Fineract not reachable (check your `.env` and backend)

---

## Using a Subset of Tools

You do not need to use all 49 tools. The server exposes the full set, but your MCP client can choose to work with a subset. There are three approaches:

### Option 1 — System Prompt Filtering (Simplest)

When configuring your MCP client, instruct the LLM in the system prompt to only use specific tools:

```
You only have access to the following tools: get_savings, deposit, withdraw.
Do not use any other tools.
```

The LLM will respect this even though the full 49 tools are technically available.

### Option 2 — Domain Router via `registry.py` (Recommended)

Import the built-in domain router to load only the tools relevant to a user's query:

```python
from tools.registry import router

# Load only tools needed for a specific query
tools = router.route_intent("Show me the repayment schedule for loan 5")
# → Returns only the Loans domain tools (10 tools)

# Or load a specific domain directly
savings_tools = router.get_domain("savings")   # 9 tools
staff_tools   = router.get_domain("staff")     # 4 tools

# Or get everything when you need it all
all_tools = router.get_all_tools()             # All 48+ tools
```

Available domains: `clients`, `groups`, `loans`, `savings`, `staff`, `accounting`

### Option 3 — Fork and Remove Tools (Advanced)

For strict institutional deployments, fork this repository and remove the `@mcp.tool()` decorator from any tool in `mcp_server.py` that your institution does not use. The server will not expose those tools to any client.

---

## Contributing

Contributions to expand Fineract API coverage are welcome. To add a new tool:

1. Add the domain function in the relevant `tools/domains/*.py` file.
2. Register it in `mcp_server.py` with `@mcp.tool()`.
3. Update this README with the new tool entry.

---

## License

This project is licensed under the [Mozilla Public License 2.0](LICENSE).
