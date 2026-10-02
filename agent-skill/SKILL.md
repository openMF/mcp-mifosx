---
name: fineract-agent-skill
description: Interacts with the Apache Fineract REST API for microfinance banking operations. Use when creating, searching, or managing banking clients, loans, savings accounts, groups, charges, GL accounts, journal entries, staff, offices, products, or reports in a Fineract-powered system. Use when querying the Mifos sandbox. Don't use for non-Fineract banking platforms, cryptocurrency exchanges, payment gateways like Stripe or PayPal, or general HTTP/REST client operations.
---

# Apache Fineract Agent Skill

Provides 55+ tools across 9 domains for full-lifecycle banking operations against an Apache Fineract instance.

## Prerequisites

1. Verify the environment file exists at `agent-skill/.env`. If missing, copy `agent-skill/.env.example` and populate the credentials. See `references/environment-config.md` for variable descriptions.
2. Verify the Python package is installed: `pip install -e agent-skill/.[dev]`. If import errors occur, re-run this command.

## Step 1: Initialize the Client

1. Import and instantiate the Fineract client:
   ```python
   from fineract_skill import FineractClient
   client = FineractClient()
   ```
2. Verify connectivity by calling `client.ping()`. If the result contains `"status": "error"`, inspect the error message and check the `.env` configuration.

## Step 2: Discover Available Tools

1. Import the tool registry:
   ```python
   from fineract_skill import list_tools, get_tool
   ```
2. To list all tools: `list_tools()`. To filter by domain: `list_tools(domain="loans")`.
3. Supported domains: `clients`, `loans`, `savings`, `groups`, `accounting`, `staff`, `products`, `charges`, `reports`.
4. For the full tool catalog with parameter schemas, read `references/tool-catalog.md`.

## Step 3: Execute a Tool

1. Look up the tool by name: `tool = get_tool("search_clients")`.
2. Call the handler, passing the `client` as the first argument followed by the required parameters:
   ```python
   result = tool.handler(client, name_query="John")
   ```
3. All tools return a standardised envelope:
   - Success: `{"ok": True, "data": {...}}`
   - Failure: `{"ok": False, "error": "..."}`
4. If `result["ok"]` is `False`, read the `error` field. Common causes: invalid IDs, missing required fields, or authentication failures.

## Step 4: Export Schemas for Agent Frameworks

1. For OpenAI function-calling format:
   ```python
   from fineract_skill.tools import get_openai_tools
   schemas = get_openai_tools()
   ```
2. For MCP-compatible format:
   ```python
   from fineract_skill.tools import get_mcp_tools
   schemas = get_mcp_tools()
   ```
3. For the static JSON schema template, read `assets/tool-schema-template.json`.

## Step 5: Client Domain Operations

1. **Search**: `search_clients(client, name_query="Maria")` — returns matching client IDs and display names.
2. **Create**: `create_client(client, "Jane", "Smith", mobile_no="555-0199")` — creates and optionally activates a client.
3. **Read**: `get_client_details(client, client_id=42)` — returns profile, status, activation date.
4. **Update**: `update_client(client, client_id=42, lastname="Jones")` — modifies only specified fields.
5. **Lifecycle**: `activate_client`, `close_client`, `delete_client` — state transitions.
6. **Sub-resources**: `get_client_accounts`, `get_client_identifiers`, `create_client_identifier`, `get_client_documents`, `get_client_charges`, `apply_client_charge`, `get_client_transactions`, `get_client_addresses`.

## Step 6: Loan Domain Operations

1. **Create**: `create_loan(client, client_id=42, principal=5000.0, months=12)` — submit a loan application.
2. **Approve & Disburse**: `approve_and_disburse_loan(client, loan_id=1)` — two-step approval in a single call.
3. **Repay**: `make_repayment(client, loan_id=1, amount=500.0)`.
4. **Inspect**: `get_loan_details`, `get_repayment_schedule`, `get_loan_transactions`, `get_loan_template`.
5. **Modify**: `update_loan`, `reject_loan`, `undo_loan_approval`, `undo_loan_disbursal`, `delete_loan`.
6. **Reschedule**: `reschedule_loan(client, loan_id=1, reschedule_from_date="1 October 2026")`.
7. **Group loans**: `create_group_loan(client, group_id=1, principal=10000.0, months=24)`.

## Step 7: Savings Domain Operations

1. **Create**: `create_savings_account(client, client_id=42)`.
2. **Approve & Activate**: `approve_and_activate_savings(client, account_id=1)`.
3. **Transact**: `deposit(client, account_id=1, amount=1000.0)`, `withdraw(client, account_id=1, amount=200.0)`.
4. **Inspect**: `get_savings_account`, `get_savings_transactions`.
5. **Manage**: `close_savings_account`, `apply_savings_charge`, `calculate_and_post_interest`.

## Step 8: Supporting Domain Operations

1. **Groups & Centers**: `create_group`, `get_group`, `list_groups`, `activate_group`, `add_group_member`, `list_centers`, `get_center`, `create_center`.
2. **Accounting**: `list_gl_accounts(client, account_type=1)` (1=Asset…5=Expense), `get_journal_entries`, `create_journal_entry`.
3. **Staff & Offices**: `list_staff`, `get_staff_details`, `list_offices`, `get_office_details`.
4. **Products**: `list_loan_products`, `get_loan_product`, `list_savings_products`, `get_savings_product`.
5. **Charges**: `list_charges`, `get_charge`, `create_charge`, `update_charge`.
6. **Reports**: `list_reports`, `get_report`, `run_report(client, report_name="Active Loans - Summary")`, `create_report`, `update_report`.

## Step 9: Validate the Skill Metadata

1. Run the metadata validation script to ensure the skill conforms to the agentskills.io spec:
   `python3 agent-skill/scripts/validate-skill.py`
2. If the script reports errors, self-correct the metadata and re-run until successful.

## Step 10: Run Tests

1. Execute the test suite to verify all tools function correctly:
   `cd agent-skill && pytest tests/ -v`
2. Tests use `respx` to mock all HTTP requests — no live Fineract instance required.
3. If tests fail, inspect the `stderr` output for the specific assertion or import error.

## Error Handling

- **Authentication Failure** (`HTTP 401`): Verify `FINERACT_USERNAME` and `FINERACT_PASSWORD` in `.env`.
- **Not Found** (`HTTP 404`): Confirm the resource ID exists. Use a search/list tool first to discover valid IDs.
- **Validation Error** (`HTTP 400`): Read the `error` field — Fineract returns specific field-level messages. Common causes: missing required date fields, invalid `locale`, or inactive resources.
- **Connection Error**: Verify `FINERACT_BASE_URL` is reachable. For the public sandbox, use `https://sandbox.mifos.community/fineract-provider/api/v1`.
- **TLS Error**: Set `FINERACT_SKIP_TLS_VERIFY=true` in `.env` only for local development with self-signed certificates.
