# Mifos X MCP Server — Go Implementation

The **Mifos MCP Server (Go)** is a high-performance integration and automation tier for the **Apache Fineract** banking suite. It exposes **102 typed tools** via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io), enabling AI agents and automated workflows to interact directly with Fineract's core modules.

This implementation focuses on low-latency execution, native cloud observability, and high-concurrency bulk operations using Go's runtime.

---

## Architectural Topology

Unlike standard scripting-based agents, this is a dedicated **MCP Protocol Server**. It sanitizes and translates Fineract REST API calls into strict, context-aware tools for any connected LLM client.

```text
┌─────────────────────────────────────────────┐
│            Apache Fineract / Mifos X         │
└──────────────────────┬──────────────────────┘
                       │ REST API (Hardened)
┌──────────────────────▼──────────────────────┐
│         mcp-go-mifosx  (This Repo)           │
│    High-Perf MCP Server — 102 Typed Tools    │
│    (Go Routines + MCP SDK + SSE Transport)   │
└──────────────────────┬──────────────────────┘
                       │ MCP Protocol (stdio/SSE)
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
    Mifos X WebApp   Claude Desktop  n8n / Custom
    AI Assistant     (claude.ai)     Micro-Agents
```

---

## High-Performance Architecture

The Go server is designed for high-throughput environments where deployment footprint and startup speed are critical:

1. **Minimal Binary Footprint**: Compiles to a standalone **~1.5 MB static binary**. This is significantly smaller than the Python implementation's ~120 MB environment overhead.
2. **Low-Latency Startup**: Achieves network readiness in **< 10ms**, making it suitable for serverless and auto-scaling environments.
3. **Native Concurrency**: Uses lightweight goroutines to handle bulk operations (e.g., parallel loan disbursals) without the high memory overhead of interpreted runtimes.
4. **Numerical Formatting**: Automatically validates and formats `transactionAmount` fields before dispatch to prevent floating-point drift and ensure compatibility with Fineract's strict decimal requirements.

---

## Cloud-Native Observability

This server is purpose-built to thrive in production container orchestration environments (Kubernetes and Docker Swarm). We provide distinct, first-class cloud features:

### Zero-Friction Dual Transport (SSE & Stdio)
- **Local Stdio Mode:** Runs seamlessly as a background daemon for secure, air-gapped development using Cursor, VSCode, or Claude Desktop.
- **Headless Cloud Mode (SSE):** By simply defining the `PORT` environment variable, the server instantly transforms into an HTTP microservice utilizing Server-Sent Events (SSE). This is the *only* way to connect pure web apps (like an Angular frontend or n8n workflow) to the MCP ecosystem without running a local terminal.

Authentication is selected at initialization:

- Trusted stdio/local mode uses `adapter.NewConfigured()` with `MIFOSX_USERNAME`, `MIFOSX_PASSWORD`, and `MIFOSX_TENANT_ID` (`adapter.New()` remains a compatibility alias).
- HTTP/SSE mode uses `adapter.NewCaller()`. Every banking request requires `Authorization` and `X-Mifos-Tenant-Id`; missing or invalid caller context fails without configured-credential fallback. The three reads (`get_client`, `search_clients`, `get_client_accounts`) remain caller scoped. `create_client` and `update_client` propose actions for human approval through the existing gateway; neither writes during a tool call.

`NewHTTPServer` accepts only `*adapter.CallerClient`. This type holds no configured identity and has no configured-auth request methods. Both modes share base URL and TLS configuration.

Approved HTTP `create_client` and `update_client` actions use the existing approval `card_id` as their correlation ID through the gateway audit journal and Fineract `X-Correlation-Id`. Execution requires a durable audit claim; Go reports only HTTP status and a safe outcome classification, with no payload or credentials. An outcome audit failure returns an error and must not trigger a write retry. Configure persistent storage using the gateway's `MCP_CREATE_CLIENT_AUDIT_PATH`; see [gateway audit configuration](../gateway/README.md#caller-scoped-go-client-creation). Stdio execution is unchanged.

Caller-scoped `update_client` requires exact `UPDATE_CLIENT` (or `ALL_FUNCTIONS`) permission at discovery, invocation, and execution. It accepts a positive integer `clientId` and at least one of `firstname` / `lastname`, matching the existing stdio fields. The action card shows the target ID and supplied new values; no extra client read is performed. The immutable approval payload binds the resource and fields. Human approval executes one `PUT clients/{clientId}` with only those fields under the originating caller and tenant. Rejection, expiry, replay, or lost ownership cannot write. Deploy the Go server and gateway together; claims now include the stored tool name and fail closed if it is absent or mismatched.

Client constructors return a client and an error. The optional `.env` file is loaded before mode selection and client construction. A missing file is allowed; unreadable or malformed files stop initialization with a diagnostic that omits file contents.

### Telemetry & Monitoring (Prometheus)
The server exposes structured metrics via a native **Prometheus** `/metrics` endpoint. 
- The server exposes a dedicated `/metrics` endpoint built natively with the **Prometheus Golang Client**. 
- It actively tracks system loads, active requests, and custom business-logic counters like `mcp_tool_calls_total`, allowing platform engineers to plug the agent directly into existing **Grafana** dashboards.

### Kubernetes (K8s) & Docker Native
- **Production Probes:** Exposes explicit, low-latency JSON `/health` endpoints designed specifically to bind to K8s Liveness and Readiness probes, guaranteeing self-healing infrastructure.
- **Distroless Containers:** Achieved via an aggressive Docker multi-stage build, compiling the entire intelligence gateway into a scratch container of **less than 20MB** with literally zero underlying operating system vulnerabilities.

---

## Available Tools (102)

All tools are registered in the `tools/` package and are discoverable by any MCP client.

### 👤 Clients (10 tools)

| Tool | Description |
|---|---|
| `list_clients` | List all clients with basic details |
| `get_client` | Get full details of a client by ID |
| `get_client_accounts` | Get loan and savings accounts for a client |
| `search_clients` | Search for clients by name |
| `create_client` | Create a new client |
| `activate_client` | Activate a pending client |
| `get_client_charges` | Get charges for a client |
| `update_client` | Update client details (name, etc.) |
| `close_client` | Close a client account |
| `delete_client` | Delete a client (only if no transactions/accounts) |

### 🪪 Identities (6 tools)

| Tool | Description |
|---|---|
| `get_client_identifiers` | List all identifiers (IDs, Passports) for a client |
| `get_client_identifier` | Get details of a specific identifier |
| `get_client_identifiers_template` | Get allowed document types for identifiers |
| `create_client_identifier` | Create a new identifier (Passport, ID, etc.) — Go-native |
| `update_client_identifier` | Update an existing identifier (read-modify-write) — Go-native |
| `delete_client_identifier` | Delete an identifier from a client |

### 📄 Documents (18 tools)

| Tool | Description |
|---|---|
| `list_client_documents` | List all documents attached to a client |
| `retrieve_client_document` | Retrieve metadata of a specific client document |
| `delete_client_document` | Delete a document attached to a client |
| `get_client_document_attachment` | Download the binary file for a client document |
| `create_client_document` | Upload a new document for a client — Go-native multipart |
| `update_client_document` | Update an existing client document — Go-native multipart |
| `list_loan_documents` | List all documents attached to a loan |
| `retrieve_loan_document` | Retrieve metadata of a specific loan document |
| `delete_loan_document` | Delete a document attached to a loan |
| `get_loan_document_attachment` | Download the binary file for a loan document |
| `create_loan_document` | Upload a new document for a loan — Go-native multipart |
| `update_loan_document` | Update an existing loan document — Go-native multipart |
| `list_savings_documents` | List all documents attached to a savings account |
| `retrieve_savings_document` | Retrieve metadata of a specific savings document |
| `delete_savings_document` | Delete a document attached to a savings account |
| `get_savings_document_attachment` | Download the binary file for a savings document |
| `create_savings_document` | Upload a new document for a savings account — Go-native multipart |
| `update_savings_document` | Update an existing savings document — Go-native multipart |

### 📊 Reports (8 tools)

| Tool | Description |
|---|---|
| `get_reports` | List all available reports in Fineract |
| `get_report` | Retrieve a specific report definition by ID |
| `get_report_template` | Get the template for creating a report |
| `create_report` | Create a new non-core report definition |
| `update_report` | Update an existing non-core report |
| `delete_report` | Delete a non-core report by ID |
| `run_report` | Run a Fineract report by name with parameters (R_officeId, R_startDate, etc.) |
| `search_report_by_name` | Search for a report by name — Go-native server-side filter |

### 💳 Loans (13 tools)

| Tool | Description |
|---|---|
| `list_loan_products` | List active loan products |
| `get_loan_details` | Get details and stats for a loan account |
| `get_loan_summary_lean` | Get lean loan summary (IDs, balance, status only) |
| `get_repayment_schedule` | Get the repayment schedule for a loan |
| `get_loan_history` | Get full transaction history for a loan |
| `get_overdue_loans` | Get overdue loans for a client |
| `create_loan` | Create a new individual loan application |
| `create_group_loan` | Create a group loan application |
| `approve_and_disburse_loan` | Approve a pending loan |
| `reject_loan_application` | Reject a pending loan application |
| `make_loan_repayment` | Make a repayment on an active loan |
| `apply_late_fee` | Apply a penalty charge to a loan |
| `waive_interest` | Waive interest on a loan |

### 💰 Savings (10 tools)

| Tool | Description |
|---|---|
| `list_savings_products` | List active savings products |
| `get_savings_account` | Get details for a savings account |
| `get_savings_transactions` | Get transactions for a savings account |
| `create_savings_account` | Create a new savings application |
| `approve_savings` | Approve a savings application |
| `activate_savings` | Activate a savings account |
| `deposit_savings` | Deposit money into a savings account |
| `withdraw_savings` | Withdraw money from a savings account |
| `apply_savings_charge` | Apply a penalty/charge to a savings account |
| `calculate_and_post_interest` | Post accrued interest to a savings account |

### 👥 Groups & Centers (13 tools)

| Tool | Description |
|---|---|
| `list_groups` | List all lending groups |
| `get_group` | Get group details |
| `create_group` | Create a new lending group |
| `activate_group` | Activate a pending group |
| `add_group_member` | Add a client to a group |
| `remove_group_member` | Remove a client from a group |
| `get_group_accounts` | Get accounts linked to a group |
| `create_group_savings_account` | Create a savings account for a group |
| `update_group` | Update group settings |
| `close_group` | Close a group |
| `list_centers` | List all centers |
| `get_center` | Get center details |
| `create_center` | Create a new center |

### 🏢 Staff & Offices (4 tools)

| Tool | Description |
|---|---|
| `list_staff` | List all staff members |
| `get_staff_details` | Get details for a staff member |
| `list_offices` | List all branches/offices |
| `get_office_details` | Get details for a specific office |

### 📒 Accounting (3 tools)

| Tool | Description |
|---|---|
| `list_gl_accounts` | List General Ledger accounts (Chart of Accounts) |
| `get_journal_entries` | Get journal entries |
| `create_journal_entry` | Create a manual debit/credit GL journal entry |

### ⚡ Bulk Parallel Operations (16 tools)

Go-native concurrent routines that execute batch operations in parallel using goroutines.

| Tool | Description |
|---|---|
| `bulk_search_clients` | Search multiple client names concurrently |
| `bulk_get_loan_status` | Get loan details for multiple loans concurrently |
| `bulk_disburse_loans` | Disburse multiple loans concurrently |
| `bulk_make_repayments` | Make repayments for multiple loans concurrently |
| `bulk_activate_clients` | Activate multiple clients concurrently |
| `bulk_get_savings_balances` | Get savings details for multiple accounts concurrently |
| `bulk_apply_fees` | Apply fees to multiple loans simultaneously |
| `bulk_close_accounts` | Close multiple savings accounts concurrently |
| `bulk_create_savings_accounts` | Create multiple savings accounts concurrently |
| `bulk_approve_and_activate_savings` | Approve and activate multiple savings concurrently |
| `bulk_deposit_savings` | Deposit into multiple savings accounts concurrently |
| `bulk_get_client_profiles` | Fetch full profiles for multiple clients in parallel — Go-native |
| `bulk_get_loan_summaries` | Fetch status for multiple loans in parallel — Go-native |
| `bulk_search_clients_parallel` | Search for multiple name fragments concurrently — Go-native |
| `bulk_apply_charges_to_batch` | Apply a charge to multiple clients concurrently — Go-native |
| `bulk_make_repayments_native` | Submit multiple loan repayments in parallel — Go-native |

### 🔗 Composite & Diagnostics (5 tools)

Intelligent endpoints that aggregate multi-domain data in a single call.

| Tool | Description |
|---|---|
| `get_client_holistic_view` | Fetch client details, accounts, charges, and identifiers in one parallel call |
| `search_all_domains` | Search concurrently across Clients, Groups, and Staff |
| `get_system_overview` | Fetch a snapshot of Offices, Staff, Loan Products, and Savings Products |
| `server_runtime_stats` | Returns real-time Go runtime statistics (Memory, CPU, Goroutines) |
| `check_fineract_latency` | Measures the millisecond ping/latency to the Fineract backend |

---

## Quick Start & Deployment

### 1. Compile from Source

Requires [Go](https://go.dev/) (1.21+).

```bash
git clone https://github.com/openMF/mcp-mifosx.git
cd mcp-mifosx/go
go mod tidy
go build -o mcp-server .
```

### 2. Configure the Environment

Establish the secure context by injecting standard connection variables. Copy `.env.example` to `.env`:

```ini
MIFOSX_BASE_URL=https://localhost:8443/fineract-provider/api/v1
MIFOSX_TENANT_ID=default
MIFOSX_USERNAME=mifos
MIFOSX_PASSWORD=password
# To activate SSE Cloud Transport, define the PORT. Leave blank for standard Stdio.
# PORT=8080
```

### Idempotent money moves

Money-moving tools (`make_loan_repayment`, `apply_late_fee`, `waive_interest`,
`deposit_savings`, `withdraw_savings`, `apply_savings_charge`,
`calculate_and_post_interest`, `create_journal_entry`, and the `bulk_*`
equivalents) accept an optional `idempotencyKey`. The key is scoped to tenant
and actor, and the operation is recorded in the durable ledger under
`MCP_IDEMPOTENCY_DIR` (default `~/.mcp-mifosx/idempotency`). The Python server
uses the same directory and record schema, so a key claimed by one server is
recognised by the other.

| Variable | Description | Default |
| --- | --- | --- |
| `MCP_IDEMPOTENCY_DIR` | Ledger directory shared by the Python and Go servers | `~/.mcp-mifosx/idempotency` |
| `MCP_IDEMPOTENCY_REQUIRED` | `true` rejects money moves without a key | `false` |
| `MCP_IDEMPOTENCY_ACTOR` | Actor used to scope keys when no caller identity exists | OS user |
| `MCP_IDEMPOTENCY_LOCK_WAIT_SECONDS` | Wait for an in-flight duplicate before `operation_in_progress` | `5` |
| `MCP_IDEMPOTENCY_LOCK_TTL_SECONDS` | Lease length written by the heartbeat. It is a liveness check, **not** an age-based stale-lock reclamation mechanism: a live owner renews the lease, and an expired lease makes this process refuse to execute. Ownership itself comes from the OS lock, which is only released when the owner exits or unlocks it. | `900` |
| `MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS` | Consecutive empty reconciliation reads before an attempt is called absent | `2` |
| `MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS` | Minimum time between the attempt's end and each empty read, and between consecutive empty reads. It reduces (but does not eliminate) the chance of reading before a delayed write is visible. | `30` |
| `MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED` | Set to `true` only after the deployment has been independently proven to reject a duplicate money POST. A confirmed absence never authorises an automatic retry without it. | `false` |

Duplicates replay the stored result, and a reused key with a different payload
is rejected.

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

Further limitations:

* manual journal entries have no queryable read, so their ambiguous outcomes
  always stay `UNKNOWN`;
* the ledger is a local directory: multiple server instances need a shared
  volume or a transactional store;
* lock files are never deleted or renamed by this implementation, and external
  deletion or replacement of a lock file is unsupported and outside the threat
  model. Ownership is proven through the open descriptor and the claim token,
  not by re-opening the path.

### 3. Initialize Server

**Headless Cloud / Microservice (SSE):**

HTTP is currently a caller-scoped, read-only pilot exposing three tools:

- `get_client`: `GET /clients/{id}`
- `search_clients`: `GET /clients?displayName={name}`
- `get_client_accounts`: `GET /clients/{id}/accounts`

Send both `Authorization` and `X-Mifos-Tenant-Id` on
every `/sse` connection and `/message` POST. The optional `/api/get_client`,
`/api/search_clients`, and `/api/get_client_accounts` wrappers
require the same headers. Identity is never read from tool arguments,
query parameters, or the MCP session. Missing, blank, or duplicate identity
headers fail before any Fineract request; there is no HTTP tenant default.

The adapter forwards the caller Authorization unchanged and maps
`X-Mifos-Tenant-Id` to Fineract's `fineract-platform-tenantid`. Fineract validates
the credentials and permissions. HTTP uses `MIFOSX_BASE_URL` and the configured
transport settings, but never falls back to `MIFOSX_USERNAME`,
`MIFOSX_PASSWORD`, or `MIFOSX_TENANT_ID`. Other banking tools are unavailable
over HTTP in this pilot. Stdio retains the full tool catalog and configured
credentials; the two execution paths do not fall back to each other.

Caller requests refuse redirects and shared cookie jars. Downstream failures
return only a sanitized error and HTTP status; successful responses containing
recognizable caller credential material are refused. Basic usernames that also
appear in client data can therefore cause a response to be refused.

Use HTTPS at the deployment boundary to protect caller headers in transit.
HTTP header presence is not a local authentication check. SSE sessions are bound
to the exact Authorization/tenant pair that created them.

HTTP/SSE `tools/list` advertises only tools explicitly allowed by the caller's
Fineract-derived capabilities. Each `tools/call` and REST wrapper independently
resolves the same capabilities before execution; denied, unknown, or unverifiable
capabilities fail closed. Capabilities are resolved once per list or call, using
the current caller and tenant, with no permission cache. The three pilot tools
currently share Fineract's CLIENT read permission, so they are granted or denied
together. Filtered MCP tools return the SDK's safe `tool not found` error;
REST wrappers return HTTP 403. Stdio discovery and execution remain unchanged.

```bash
docker build -t mcp-go-mifosx .
docker run -p 8080:8080 --env-file .env mcp-go-mifosx
```

**Local Developer Mode (Stdio):**
```bash
./mcp-server
```

---

## Connecting Claude Desktop

Connect the server locally to your enterprise Claude environment by merging the following JSON block into your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mifos-go": {
      "command": "/absolute/path/to/mcp-mifosx/go/mcp-server",
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

---

## License

Copyright since 2025 Mifos Initiative.  
Licensed under the [Mozilla Public License 2.0](LICENSE).
