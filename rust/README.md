# Mifos MCP Server — Rust Implementation

The **Mifos MCP Server (Rust)** is a high-performance, multi-threaded integration tier that bridges any AI assistant or agent framework to the **Apache Fineract** banking backend. It exposes **61 typed tools** via the [Model Context Protocol (MCP)](https://modelcontextprotocol.io), leveraging Rust's concurrency model to handle bulk operations in parallel.

---

## What is this?

This is a **Server**, not a client or agent. It translates Fineract REST API operations into standardized MCP tools. Unlike the Python implementation, this version is built for speed and scales horizontally using asynchronous I/O and parallel execution for bulk tasks.

```text
┌─────────────────────────────────────────────┐
│            Apache Fineract / Mifos X         │
└──────────────────────┬──────────────────────┘
                       │ REST API
┌──────────────────────▼──────────────────────┐
│         mcp-rust-mifosx  (This Repo)         │
│    High-Perf MCP Server — 61 typed tools     │
│    (Built with Tokio + Reqwest + RMCP)       │
└──────────────────────┬──────────────────────┘
                       │ MCP Standard Protocol (stdio)
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
    Mifos X WebApp   Claude Code     n8n / Custom
    AI Assistant     (claude.ai)     Workflow Agent
    (your client)   (external)       (your client)
```

**This repository is framework-agnostic.** It provides the tools (the "hands"); the client provides the LLM (the "brain").

---

## Why Rust?

1. **Native Concurrency**: Bulk tools (e.g., `bulk_deposit_savings`) execute dozens of requests in parallel using `Tokio`, significantly reducing agent latency.
2. **Type Safety**: Strong compile-time checks ensure that tool schemas exactly match the Fineract API requirements, preventing runtime hallucinations.
3. **Low Footprint**: Minimal CPU and memory overhead compared to interpreted runtimes.
4. **Expanded Toolset**: Includes 12+ new **Bulk Operations** not found in the base Python implementation.

---

## Quick Start

### 1. Build from Source

Ensure you have [Rust (Cargo)](https://rustup.rs/) installed.

```bash
git clone https://github.com/openMF/mcp-mifosx.git
cd mcp-mifosx/rust
cargo build --release
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
./target/release/mcp-rust-mifosx
```

The server starts on **stdio transport**.

### 4. Run with Docker

```bash
docker build -t mcp-rust-mifosx .
docker run -i --env-file .env mcp-rust-mifosx
```

---

## Connecting a Client

### Claude Desktop / Claude Code

Add this to your `claude_desktop_config.json`:

```json
{
  "mcpServers": {
    "mifos-rust": {
      "command": "/path/to/rust/target/release/mcp-rust-mifosx",
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

## Available Tools (66)

The Rust server categorizes tools into domains for easier discovery.

### 👤 Clients (16 tools)

| Tool | Description |
|---|---|
| `search_clients_by_name` | Find clients by name, returns clientId |
| `get_client_details` | Show key profile details for a client |
| `get_client_accounts` | List all loan and savings accounts for a client |
| `create_client` | Create a new banking client profile |
| `activate_client` | Activate a pending client |
| `update_client_mobile` | Update a client's mobile number |
| `close_client` | Close a client's profile |
| `get_client_identifiers` | List client KYC documents |
| `create_client_identifier` | Add a KYC document to a client |
| `get_client_documents` | List uploaded files for a client |
| `get_client_charges` | List client-level fees |
| `apply_client_charge` | Apply a one-time charge to a client profile |
| `get_client_transactions` | List financial transactions for a client |
| `get_client_addresses` | Show a client's registered addresses |

### 👥 Groups & Centers (11 tools)

| Tool | Description |
|---|---|
| `list_groups` | List all lending groups |
| `activate_group` | Activate a pending lending group |
| `add_group_member` | Add a client to a group |
| `remove_group_member` | Remove a client from a group |
| `get_group_accounts` | List all loan and savings accounts for a group |
| `create_group_savings_account` | Create a new savings account for a group |
| `update_group` | Update a group's details (name, external ID) |
| `close_group` | Close a lending group |
| `list_centers` | List all centers |
| `get_center` | Show details for a center |
| `create_center` | Create a new center |

### 💳 Loans (11 tools)

| Tool | Description |
|---|---|
| `get_loan_details` | Get key details for a loan |
| `get_repayment_schedule` | Get the repayment schedule |
| `get_loan_history` | Get full transaction history for a loan |
| `get_overdue_loans` | List overdue loans for a client |
| `create_loan` | Create a new individual loan application |
| `create_group_loan` | Create a group loan application |
| `approve_and_disburse_loan` | Approve and disburse a pending loan |
| `reject_loan_application` | Reject a pending loan application |
| `make_loan_repayment` | Make a repayment on an active loan |
| `apply_late_fee` | Apply a fee/charge to a loan |
| `waive_interest` | Waive interest on a loan |
| `list_loan_products` | List all available loan products |

### 💰 Savings (10 tools)

| Tool | Description |
|---|---|
| `get_savings_account` | Get key details of a savings account |
| `get_savings_transactions` | Get transactions for a savings account |
| `create_savings_account` | Create a new savings account |
| `approve_and_activate_savings` | Approve and activate a savings account |
| `close_savings_account` | Close a savings account |
| `deposit_savings` | Deposit money into a savings account |
| `withdraw_savings` | Withdraw money from a savings account |
| `apply_savings_charge` | Apply a charge to a savings account |
| `calculate_and_post_interest` | Post accrued interest to a savings account |
| `list_savings_products` | List all available savings products |

### 🏢 Staff & Offices (4 tools)

| Tool | Description |
|---|---|
| `list_staff` | List bank staff members |
| `get_staff_details` | Get details for a staff member |
| `list_offices` | List all bank branches/offices |
| `get_office_details` | Get details for a specific office |

### 📊 Accounting (3 tools)

| Tool | Description |
|---|---|
| `list_gl_accounts` | List GL accounts (Chart of Accounts) |
| `get_journal_entries` | List journal entries by account or transaction ID |
| `create_journal_entry` | Record a manual debit/credit journal entry |

### 🚀 Bulk Operations (11 tools) — *Rust Exclusive*

High-performance tools that process multiple IDs in parallel.

| Tool | Description |
|---|---|
| `bulk_search_clients` | Search multiple client names in parallel |
| `bulk_get_loan_status` | Fetch statuses for a list of loan IDs |
| `bulk_disburse_loans` | Disburse multiple loans concurrently |
| `bulk_make_repayments` | Post repayments to multiple loans |
| `bulk_activate_clients` | Activate multiple clients at once |
| `bulk_get_savings_balances` | Fetch balances for a list of savings accounts |
| `bulk_apply_fees` | Apply charges to multiple entities |
| `bulk_close_accounts` | Close multiple accounts in parallel |
| `bulk_create_savings_accounts` | Create multiple savings accounts concurrently |
| `bulk_approve_and_activate_savings` | Batch activate savings accounts |
| `bulk_deposit_savings` | Parallel deposit into multiple accounts |

---

## Testing with MCP Inspector

Test your Rust server interactively in the browser:

```bash
npx @modelcontextprotocol/inspector ./target/release/mcp-rust-mifosx
```

1. Select **STDIO** transport.
2. Ensure the command points to the compiled Rust binary.
3. Click **Connect**.
4. Browse the **Tools** tab to see all 61 tools and their JSON schemas.

### Programmatic Smoke Test

A Python-based test script is provided in the `rust/` folder to verify the server's binary compatibility:

```bash
python test_rust_tools.py
```

---

## License

Copyright since 2025 Mifos Initiative.  
Licensed under the [Mozilla Public License 2.0](LICENSE).
