# Mifos MCP Server — Python Implementation

The **Mifos MCP Server** is a standalone, stateless integration tier that bridges any AI assistant or agent framework to the **Apache Fineract** banking backend. It registers **76 typed tools** via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io), making it compatible with any MCP-compliant client. Registering a tool is not the same as exposing it: which tools an agent may see and call is decided by the [tool profile](#tool-profiles-enforced-by-the-server) (`MCP_AGENT_PROFILE` — see [Tool profiles](#tool-profiles-enforced-by-the-server)).

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
│    Stateless MCP Server — 76 typed tools     │
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
cd mcp-mifosx/python
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

To serve **streamable HTTP** instead, for a client in another container or on another host, set FastMCP's settings:

```bash
FASTMCP_TRANSPORT=http FASTMCP_HOST=0.0.0.0 FASTMCP_PORT=8000 python mcp_server.py
# clients connect to http://<host>:8000/mcp
```

The HTTP endpoint has no authentication of its own: anyone who can reach it acts as the configured Fineract user. Keep it on a private network, as the agentic Mifos sandbox does (one network per agent and its MCP server).

By default it exposes only the read-only tools. Set `MCP_AGENT_PROFILE` to choose which tools it exposes; see [Tool profiles](#tool-profiles-enforced-by-the-server).

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
      "args": ["/path/to/mcp-mifosx/python/mcp_server.py"],
      "env": {
        "MIFOSX_BASE_URL": "https://your-fineract-instance/api/v1",
        "MIFOSX_TENANT_ID": "default",
        "MIFOSX_USERNAME": "mifos",
        "MIFOSX_PASSWORD": "password",
        "MCP_AGENT_PROFILE": "all"
      }
    }
  }
}
```

With `MCP_AGENT_PROFILE` set to `all`, Claude will discover every tool and be able to perform banking operations via natural language. Leave it out to give Claude the read-only tools only.

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

## Available Tools (76)

All tools are registered in `mcp_server.py` with `@mcp.tool()` and are discoverable by any MCP client.

### 👤 Clients (16 tools)

| Tool                      | Description                                     |
| ------------------------- | ----------------------------------------------- |
| `search_clients`          | Find clients by name, returns clientId          |
| `get_client`              | Show key profile details for a client           |
| `get_client_accts`        | List all loan and savings accounts for a client |
| `create_new_client`       | Create a new banking client profile             |
| `activate_pending_client` | Activate a pending client                       |
| `update_mobile`           | Update a client's mobile number                 |
| `close_client_profile`    | Close a client's profile                        |
| `get_identifiers`         | List client KYC documents                       |
| `add_identifier`          | Add a KYC document to a client                  |
| `list_documents`          | List uploaded files for a client                |
| `list_client_charges`     | List client-level fees                          |
| `apply_client_fee`        | Apply a one-time charge to a client profile     |
| `list_client_txns`        | List financial transactions for a client        |
| `get_addresses`           | Show a client's registered addresses            |
| `create_lending_group`    | Create a lending group                          |
| `get_group`               | Show group details and members                  |

### 📸 Document capture & verification (3 tools)

| Tool                      | Description                                     |
| ------------------------- | ----------------------------------------------- |
| `validate_capture_quality` | Deterministic capture-quality gate on a document photo BEFORE upload: verdict `RETAKE` (unreadable — capture again) / `REVIEW` (borderline — human judge) / `PASS` (usable), with reasons and measured signals (sharpness, contrast, ink_ratio, brightness). Offline, no LLM, no CV/ML library — pure NumPy |
| `upload_client_document`   | Gate-then-upload a photo as a client document. An unreadable (RETAKE) capture is refused with its reasons unless `force=True`; REVIEW verdicts upload but stay flagged in the `capture_gate` audit object |
| `verify_client_details`    | Verify document details against the client's records: the vision model in the AI client extracts fields (fullName / nationalId / mobileNo) from the photo; this tool fetches the client profile + identity-document identifiers from Fineract and compares each field deterministically — per-field `MATCH` / `REVIEW` / `MISMATCH` / `NO_SOURCE_DATA` (names token-order-free and punctuation-folded; IDs exact; phones tolerate country codes), overall `VERIFIED` / `NEEDS_REVIEW` / `FAILED`. No LLM in the comparison |

### 👥 Groups & Centers (6 tools)

| Tool                     | Description                      |
| ------------------------ | -------------------------------- |
| `list_all_groups`        | List all lending groups          |
| `activate_pending_group` | Activate a pending lending group |
| `add_member_to_group`    | Add a client to a group          |
| `list_all_centers`       | List all centers                 |
| `get_center`             | Show details for a center        |
| `create_new_center`      | Create a new center              |

### 💳 Loans (11 tools)

| Tool                           | Description                              |
| ------------------------------ | ---------------------------------------- |
| `get_loan`                     | Get key details for a loan               |
| `get_repayment_sched`          | Get the repayment schedule               |
| `get_loan_hist`                | Get full transaction history for a loan  |
| `get_overdue_loans_for_client` | List overdue loans for a client          |
| `create_new_loan`              | Create a new individual loan application |
| `create_group_loan_app`        | Create a group loan application          |
| `approve_disburse_loan`        | Approve and disburse a pending loan      |
| `reject_loan`                  | Reject a pending loan application        |
| `make_repayment`               | Make a repayment on an active loan       |
| `apply_loan_fee`               | Apply a fee/charge to a loan             |
| `waive_loan_interest`          | Waive interest on a loan                 |

### 💰 Savings (9 tools)

| Tool                       | Description                                |
| -------------------------- | ------------------------------------------ |
| `get_savings`              | Get key details of a savings account       |
| `get_savings_txns`         | Get transactions for a savings account     |
| `create_savings`           | Create a new savings account               |
| `approve_activate_savings` | Approve and activate a savings account     |
| `close_savings`            | Close a savings account                    |
| `deposit`                  | Deposit money into a savings account       |
| `withdraw`                 | Withdraw money from a savings account      |
| `apply_savings_fee`        | Apply a charge to a savings account        |
| `calc_post_interest`       | Post accrued interest to a savings account |

### 🏢 Staff & Offices (4 tools)

| Tool               | Description                       |
| ------------------ | --------------------------------- |
| `list_all_staff`   | List bank staff members           |
| `get_staff`        | Get details for a staff member    |
| `list_all_offices` | List all bank branches/offices    |
| `get_office`       | Get details for a specific office |

### 📊 Accounting (3 tools)

| Tool                   | Description                                       |
| ---------------------- | ------------------------------------------------- |
| `list_accounts`        | List GL accounts (Chart of Accounts)              |
| `list_journal_entries` | List journal entries by account or transaction ID |
| `record_journal_entry` | Record a manual debit/credit journal entry        |

---

## Project Structure

```
python/
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

| Variable           | Description                             | Default    |
| ------------------ | --------------------------------------- | ---------- |
| `MIFOSX_BASE_URL`  | Full base URL of your Fineract REST API | —          |
| `MIFOSX_TENANT_ID` | Fineract tenant identifier              | `default`  |
| `MIFOSX_USERNAME`  | Fineract API username                   | `mifos`    |
| `MIFOSX_PASSWORD`  | Fineract API password                   | `password` |
| `MCP_AGENT_PROFILE` | Tool profile to expose: a name from `profiles.toml`, or `all` | `read-only` |
| `MCP_PROFILES_FILE` | Path to the tool profiles file          | `profiles.toml` |
| `FASTMCP_TRANSPORT` | `stdio`, or `http` for streamable HTTP at `/mcp` | `stdio` |
| `FASTMCP_HOST` / `FASTMCP_PORT` | Address the HTTP transport listens on | `127.0.0.1` / `8000` |
| `MCP_IDEMPOTENCY_DIR` | Directory holding the durable idempotency ledger; the Go server reads the same path | `~/.mcp-mifosx/idempotency` |
| `MCP_IDEMPOTENCY_REQUIRED` | `true` rejects money moves that arrive without an idempotency key | `false` |
| `MCP_IDEMPOTENCY_ACTOR` | Actor identity used to scope keys when no caller identity is available | OS user |
| `MCP_IDEMPOTENCY_LOCK_WAIT_SECONDS` | How long a duplicate waits for the in-flight call before reporting `operation_in_progress` | `5` |
| `MCP_IDEMPOTENCY_LOCK_TTL_SECONDS` | Lease length written by the heartbeat. It is a liveness check, **not** an age-based stale-lock reclamation mechanism: a live owner renews the lease, and an expired lease makes this process refuse to execute. Ownership itself comes from the OS lock, which is only released when the owner exits or unlocks it. | `900` |
| `MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS` | Consecutive empty reconciliation reads required before an attempt is called absent | `2` |
| `MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS` | Minimum time between the attempt's end and each empty read, and between consecutive empty reads. It reduces (but does not eliminate) the chance of reading before a delayed write is visible. | `30` |
| `MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED` | Set to `true` only after the deployment has been independently proven to reject a duplicate money POST. A confirmed absence never authorises an automatic retry without it. | `false` |

---

## Idempotent Money Operations

Every money-moving tool (loan repayments, disbursals, waivers and charges, savings
deposits, withdrawals and charges, savings interest posting, and manual journal
entries) accepts an optional `idempotency_key` (`idempotencyKey` in the Go server).
Send the same key to retry the same logical request safely:

* the first call executes; duplicates replay the stored result without touching
  Fineract;
* reusing a key with a different payload is rejected with
  `idempotency_key_reused`;
* calls that arrive while the first is still running return
  `operation_in_progress` instead of executing twice;
* a call whose outcome was lost (timeout, dropped connection, 5xx) is
  reconciled against Fineract before anything is retried. If the effect cannot
  be confirmed, the operation stays `UNKNOWN` and reports
  `reconciliation_required` — it is never silently repeated.

Keys are scoped to tenant plus actor, and a deterministic hashed
`Idempotency-Key` header is sent with money POSTs. The header is only useful if
the deployment in front of Fineract actually validates and deduplicates it;
stock Fineract does not persist it, and this server does not assume that it
does.

### What reconciliation can and cannot prove

A reconciliation read looks for a record that did not exist before the attempt
and *carries this attempt's unique marker* in a supported backend field. The
marker is stored only in the local ledger; it is never injected into a request
body, because no money-operation field in the current Fineract API is proven to
persist and return it.

Consequences:

* on a stock Fineract deployment, marker-based `CONFIRMED` is unreachable. A
  new record with the same type and amount may have come from another channel,
  so it is not attributed to this attempt;
* current Fineract money operations (loan repayment/disbursal, savings
  deposit/withdrawal, charges, journal entries) have **no verified marker
  propagation**. Supporting it needs a backend change that stores a
  caller-supplied unique reference on the transaction and returns it from the
  corresponding read endpoint;
* a disbursal is the one exception where a unique effect is observable without
  a marker: a recognised pre-disbursement loan status that becomes `Active`
  confirms the attempt. A missing, null, non-string or unrecognised status is
  `UNKNOWN`, never absence.

An attempt may only be called `ABSENT` after
`MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS` empty reads that are each separated by
`MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS` and occur after the attempt
ended. A safety window reduces the chance of reading too early; **it does not
prove that the backend has finished writing**. A delayed transaction can still
appear later.

Even a fully confirmed absence does not authorise an automatic retry unless
`MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED=true` and the deployment genuinely
deduplicates the POST. Without that proof the outcome is downgraded to
`UNKNOWN`/`reconciliation_required` for manual reconciliation. Every ambiguous
outcome that cannot be attributed stays `UNKNOWN`; verify it in Fineract before
retrying with a new key.

Other limitations worth stating plainly:

* manual journal entries have no queryable read in this codebase's Fineract
  surface, so an ambiguous journal POST stays `UNKNOWN` for an operator to check;
* the ledger is a directory on the local filesystem. Several server instances
  need a shared volume or a transactional store, otherwise each keeps its own
  ledger;
* lock files are never deleted or renamed by this implementation, and external
  deletion or replacement of a lock file is unsupported and outside the threat
  model. Ownership is proven through the open descriptor and the claim token,
  not by re-opening the path.

---

## Testing with MCP Inspector

The [MCP Inspector](https://github.com/modelcontextprotocol/inspector) is the official tool for exploring and testing MCP servers interactively in a browser UI.

### Step 1 — Launch the Inspector

Run this command from inside the `python/` directory:

```bash
cd python
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

### Step 3 — Browse All 76 Tools

After connecting, click the **"Tools"** tab in the left sidebar. You will see all 76 tools listed with their names, descriptions, and input schemas — or, when the server is running its default profile, the tools that profile exposes. You can:

- **Click any tool** to expand its input form
- **Fill in parameters** and click **"Call Tool"** to invoke it live against Fineract
- **Inspect the raw JSON response** returned by the tool

> ℹ️ The Inspector lists only the tools of the **active profile**. The default is the `read-only` profile; to browse and call all 76, launch the server with `MCP_AGENT_PROFILE=all` (see [Tool profiles](#tool-profiles-enforced-by-the-server)).

### Programmatic Tests

To run the unit test suite without the browser:

```bash
cd python
pytest tests/ -v
```

Expected output:

```
  ✅ PASS — All 76 tools registered with unique names
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

You do not need to use all the tools. The server itself decides which tools a client can see and call (tool profiles); within that set, your MCP client can narrow down further.

### Tool profiles (enforced by the server)

`profiles.toml` lists, per profile, the tools the server exposes. Choose one with `MCP_AGENT_PROFILE`:

```bash
MCP_AGENT_PROFILE=decision python mcp_server.py
```

| `MCP_AGENT_PROFILE` | Tools exposed |
| ------------------- | ------------- |
| not set | `read-only`: the tools that only read from Fineract |
| a profile in `profiles.toml` | exactly the tools that profile lists |
| `all` | every tool, including those that change or delete data |

Tools outside the profile are neither listed nor callable: calling one by name fails with `Unknown tool`. A tool added to `mcp_server.py` later stays hidden until a profile lists it. The server refuses to start when the profile does not exist, when `profiles.toml` names a tool the server does not have, or when the file cannot be read.

To give an agent its own set of tools, add a `[profiles.<name>]` table with a `tools` list to `profiles.toml` (or point `MCP_PROFILES_FILE` at your own file, which must also define `read-only`).

Within the profile, clients can narrow the set further in three ways. Only options 2 and 3 restrict which tools can be called; option 1 only guides the model:

### Option 1 — System Prompt Filtering (Simplest)

When configuring your MCP client, instruct the LLM in the system prompt to only use specific tools:

```
You only have access to the following tools: get_savings, deposit, withdraw.
Do not use any other tools.
```

This is guidance, not access control: the model can still call every tool the server's profile exposes. To restrict tools, use a tool profile.

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

## Suggestion Engine (Experimental)

This repository includes an experimental `suggestion_engine.py` module that demonstrates how context-aware next-step suggestions can be generated based on MCP tool outputs.

⚠️ Important:

- The suggestion engine is NOT integrated into MCP tool responses.
- MCP server remains a pure execution layer (no decision-making logic).
- Suggestions are intended to be generated at the client/LLM layer.

### Why?

Keeping suggestions outside the MCP layer ensures:

- Clean separation of concerns
- Reduced response size
- Better flexibility for AI agents to decide next actions

### Future Scope

Suggestion logic can be integrated into:

- AI Assistants (LLM layer)
- Chat-based banking copilots
- Workflow automation systems

### Fineract HTTP timeouts

Every Fineract request has a 10-second connection timeout and a 60-second
read timeout, preventing an unresponsive backend from blocking a tool indefinitely.
Set `MIFOSX_CONNECT_TIMEOUT_SECONDS` and `MIFOSX_READ_TIMEOUT_SECONDS` to override
these limits. Both values must be finite positive numbers; fractions are supported.
The read timeout limits how long to wait between bytes, rather than the total
request duration. Requests are not automatically retried, so a timeout during a
write requires checking the operation's state before submitting it again.
