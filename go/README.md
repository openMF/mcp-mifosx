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

### 3. Initialize Server

**Headless Cloud / Microservice (SSE):**
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
