# Apache Fineract Agent Skill

A **reusable, framework-agnostic** Agent Skill that enables **Pi Agent**, **Hermes Agent**, and **Prime-Agent** to interact with the [Apache Fineract](https://fineract.apache.org/) REST API.

## Features

| Domain | Tools | Operations |
|---|---|---|
| **Clients** | 15 | Search, create, update, activate, close, delete, identifiers, documents, charges, transactions, addresses |
| **Loans** | 15 | Create (individual + group), approve, disburse, repay, waive interest, reschedule, undo approval/disbursal, update, delete, get details/schedule/history |
| **Savings** | 9 | Create, approve/activate, deposit, withdraw, close, charges, interest posting, transactions |
| **Groups & Centers** | 8 | Create, list, activate, add members, centers CRUD |
| **Accounting** | 3 | GL accounts, journal entries (read + create) |
| **Staff & Offices** | 4 | List/get staff, list/get offices |
| **Products** | 4 | List/get loan products, list/get savings products |
| **Charges** | 4 | List, get, create, update fee/penalty definitions |
| **Reports** | 5 | List, get, run, create, update report definitions |

**67 tools** total, all with:
- Full parameter documentation
- Standardised response envelopes (`{"ok": true, "data": ...}`)
- Built-in error handling
- MCP annotations (`readOnly`, `destructive`) and opt-in gating for sensitive tools
- Export to OpenAI function-calling and MCP schemas

---

## Quick Start

### 1. Install

```bash
cd agent-skill/mifos-agent
pip install -e ".[dev]"
```

### 2. Configure

```bash
cp .env.example .env
# Edit .env with your Fineract credentials
```

| Variable | Default | Description |
|---|---|---|
| `FINERACT_BASE_URL` | *(required)* | Fineract API base URL |
| `FINERACT_USERNAME` | `mifos` | HTTP Basic Auth username |
| `FINERACT_PASSWORD` | `password` | HTTP Basic Auth password |
| `FINERACT_TENANT_ID` | `default` | Fineract tenant identifier |
| `FINERACT_TIMEOUT` | `30` | Request timeout (seconds) |
| `FINERACT_SKIP_TLS_VERIFY` | `false` | Skip TLS verification (dev only) |

For the public sandbox:
```env
FINERACT_BASE_URL=https://sandbox.mifos.community/fineract-provider/api/v1
FINERACT_USERNAME=mifos
FINERACT_PASSWORD=password
```

### 3. Use

```python
from fineract_skill import FineractClient, list_tools, get_tool

# Initialize the client
client = FineractClient()

# Verify connectivity
print(client.ping())
# {'status': 'ok', 'user_count': 5}

# Discover all available tools
for tool in list_tools():
    print(f"[{tool.domain}] {tool.name}: {tool.description}")

# Discover tools by domain
loan_tools = list_tools(domain="loans")

# Execute a tool
tool = get_tool("search_clients")
result = tool.handler(client, name_query="John")
print(result)
# {'ok': True, 'data': {'clients': [{'clientId': 1, 'displayName': 'John Doe', ...}]}}
```

---

## Agent Integration

### Pi Agent / Hermes Agent / Prime-Agent

Each tool in the registry exports metadata in two standard formats:

#### OpenAI Function-Calling Format

```python
from fineract_skill.tools import get_openai_tools

tools_for_llm = get_openai_tools()
# Returns list of {"type": "function", "function": {"name": ..., "description": ..., "parameters": ...}}
```

#### MCP Format

```python
from fineract_skill.tools import get_mcp_tools

# Default: excludes tools that require explicit opt-in (e.g. create_report, update_report)
tools_for_mcp = get_mcp_tools()

# Include opt-in tools (report SQL creation/update)
all_tools = get_mcp_tools(allow_opt_in=True)
# Returns list of {"name": ..., "description": ..., "inputSchema": ..., "annotations": ...}
```

#### Executing a Tool by Name

```python
from fineract_skill import FineractClient, get_tool

client = FineractClient()

# Agent decides to call "search_clients" with args {"name_query": "Maria"}
tool = get_tool("search_clients")
result = tool.handler(client, name_query="Maria")

if result["ok"]:
    clients = result["data"]["clients"]
else:
    error_msg = result["error"]
```

### Response Format

All tools return a standardised envelope:

```json
// Success
{"ok": true, "data": {"clientId": 42, "displayName": "John Doe", ...}}

// Error
{"ok": false, "error": "HTTP 404: Client not found"}
```

---

## Tool Annotations

Each `ToolSpec` carries safety metadata that agent frameworks can use to enforce guardrails:

| Annotation | Type | Default | Description |
|---|---|---|---|
| `read_only` | `bool` | `False` | Tool performs no mutations — safe to auto-approve |
| `destructive` | `bool` | `False` | Tool deletes data or withdraws funds — requires confirmation |
| `require_opt_in` | `bool` | `False` | Tool is excluded from `get_mcp_tools()` by default |

**Destructive tools** (flagged `destructive=True`, require explicit confirmation):

| Tool | Domain | Action |
|---|---|---|
| `delete_client` | clients | Permanently deletes a pending/closed client profile |
| `delete_loan` | loans | Permanently deletes a draft/submitted loan application |
| `withdraw` | savings | Withdraws funds from an active savings account |

**Opt-in tools** (flagged `require_opt_in=True`, excluded from default `get_mcp_tools()`):

| Tool | Domain | Risk |
|---|---|---|
| `create_report` | reports | Registers a new report definition with custom SQL |
| `update_report` | reports | Modifies an existing report's SQL query |

The `to_mcp_schema()` method exports `readOnly` and `destructive` as MCP-standard `annotations`:

```json
{
  "name": "delete_client",
  "description": "Delete a pending/closed client profile.",
  "inputSchema": { "..." },
  "annotations": { "destructive": true }
}
```

---

## Tool Catalog

### Clients — 15 Tools

| Tool | Description | Required Params | Optional Params | Flags |
|---|---|---|---|---|
| `search_clients` | Search clients by name | `name_query: str` | — | — |
| `get_client_details` | Get client profile | `client_id: int` | — | — |
| `get_client_accounts` | List all loans and savings | `client_id: int` | — | — |
| `create_client` | Create a new client | `firstname: str`, `lastname: str` | `mobile_no`, `office_id=1`, `active=True` | — |
| `activate_client` | Activate a pending client | `client_id: int` | — | — |
| `update_client` | Update client details | `client_id: int` | `firstname`, `lastname`, `mobile_no`, `external_id` | — |
| `close_client` | Close a client profile | `client_id: int` | `closure_reason_id: int` | — |
| `delete_client` | Delete a client profile | `client_id: int` | — | 🔴 destructive |
| `get_client_identifiers` | List identity documents | `client_id: int` | — | — |
| `create_client_identifier` | Add an identity document | `client_id: int`, `document_type_id: int`, `document_key: str` | — | — |
| `get_client_documents` | List uploaded files | `client_id: int` | — | — |
| `get_client_charges` | List client-level fees | `client_id: int` | — | — |
| `apply_client_charge` | Apply a one-time charge | `client_id: int`, `charge_id: int`, `amount: float` | — | — |
| `get_client_transactions` | List financial transactions | `client_id: int` | — | — |
| `get_client_addresses` | Get registered addresses | `client_id: int` | — | — |

### Loans — 15 Tools

| Tool | Description | Required Params | Optional Params | Flags |
|---|---|---|---|---|
| `get_loan_details` | Get loan status and balance | `loan_id: int` | — | — |
| `get_repayment_schedule` | Get repayment schedule | `loan_id: int` | — | — |
| `get_loan_transactions` | Get transaction history | `loan_id: int` | — | — |
| `get_loan_template` | Get pre-filled loan template | `client_id: int` | `product_id: int` | — |
| `create_loan` | Create individual loan | `client_id: int`, `principal: float`, `months: int` | `product_id=1` | — |
| `create_group_loan` | Create group loan | `group_id: int`, `principal: float`, `months: int` | `product_id=1` | — |
| `approve_and_disburse_loan` | Approve and disburse | `loan_id: int` | `amount: float` | — |
| `reject_loan` | Reject a pending loan | `loan_id: int` | `note: str` | — |
| `make_repayment` | Make a loan repayment | `loan_id: int`, `amount: float` | — | — |
| `waive_interest` | Waive interest | `loan_id: int`, `amount: float` | `note: str` | — |
| `undo_loan_approval` | Revert to pending | `loan_id: int` | — | — |
| `undo_loan_disbursal` | Revert to approved | `loan_id: int` | — | — |
| `update_loan` | Update a draft loan | `loan_id: int` | `principal`, `months`, `product_id` | — |
| `delete_loan` | Delete a draft loan | `loan_id: int` | — | 🔴 destructive |
| `reschedule_loan` | Submit reschedule request | `loan_id: int`, `reschedule_from_date: str`, `reschedule_reason_id: int` | `adjusted_due_date`, `new_interest_rate`, `grace_on_principal`, `extra_terms`, `reason` | — |

### Savings — 9 Tools

| Tool | Description | Required Params | Optional Params | Flags |
|---|---|---|---|---|
| `get_savings_account` | Get savings details | `account_id: int` | — | — |
| `get_savings_transactions` | Get transaction history | `account_id: int` | — | — |
| `create_savings_account` | Create savings account | `client_id: int` | `product_id=1` | — |
| `approve_and_activate_savings` | Approve and activate | `account_id: int` | — | — |
| `close_savings_account` | Close savings account | `account_id: int` | — | — |
| `deposit` | Deposit into savings | `account_id: int`, `amount: float` | — | — |
| `withdraw` | Withdraw from savings | `account_id: int`, `amount: float` | — | 🔴 destructive |
| `apply_savings_charge` | Apply a charge/fee | `account_id: int`, `amount: float` | `charge_id=1` | — |
| `calculate_and_post_interest` | Post accrued interest | `account_id: int` | — | — |

### Groups & Centers — 8 Tools

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `create_group` | Create a lending group | `name: str` | `office_id=1`, `external_id` |
| `get_group` | Get group details and members | `group_id: int` | — |
| `list_groups` | List all groups | — | `office_id: int` |
| `activate_group` | Activate a pending group | `group_id: int` | — |
| `add_group_member` | Add a client to a group | `group_id: int`, `client_id: int` | — |
| `list_centers` | List all centers | — | `office_id: int` |
| `get_center` | Get center details | `center_id: int` | — |
| `create_center` | Create a new center | `name: str`, `office_id: int` | `external_id` |

### Accounting — 3 Tools

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_gl_accounts` | List GL accounts (1=Asset … 5=Expense) | — | `account_type: int` |
| `get_journal_entries` | List journal entries | — | `gl_account_id: int`, `transaction_id: str` |
| `create_journal_entry` | Record a manual journal entry | `office_id: int`, `date: str`, `credits: list`, `debits: list` | `comment: str` |

### Staff & Offices — 4 Tools

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_staff` | List staff members | — | `office_id: int`, `status: str` (all/active/inactive) |
| `get_staff_details` | Get staff member details | `staff_id: int` | — |
| `list_offices` | List all offices/branches | — | — |
| `get_office_details` | Get office details | `office_id: int` | — |

### Products — 4 Tools

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_loan_products` | List all loan products | — | — |
| `get_loan_product` | Get loan product details | `product_id: int` | — |
| `list_savings_products` | List all savings products | — | — |
| `get_savings_product` | Get savings product details | `product_id: int` | — |

### Charges — 4 Tools

| Tool | Description | Required Params | Optional Params |
|---|---|---|---|
| `list_charges` | List charge definitions | — | — |
| `get_charge` | Get charge details | `charge_id: int` | — |
| `create_charge` | Create a charge definition | `name: str`, `amount: float`, `charge_time_type: int` | `currency_code`, `charge_applies_to`, `charge_calculation_type`, `is_penalty`, `is_active` |
| `update_charge` | Update a charge definition | `charge_id: int` | `name`, `amount`, `is_active` |

### Reports — 5 Tools

| Tool | Description | Required Params | Optional Params | Flags |
|---|---|---|---|---|
| `list_reports` | List report definitions | — | `report_type: str` | — |
| `get_report` | Get report definition | `report_id: int` | — | — |
| `run_report` | Run a report by name | `report_name: str` | `params: dict` | — |
| `create_report` | Register a report definition | `report_name: str`, `report_type: str`, `report_sql: str` | `description` | 🔒 opt-in |
| `update_report` | Update a report definition | `report_id: int` | `report_name`, `report_type`, `report_sql`, `description` | 🔒 opt-in |

---

## Architecture

```
agent-skill/mifos-agent/
├── pyproject.toml              # Package metadata & dependencies
├── .env.example                # Configuration template
├── README.md                   # This file
├── SKILL.md                    # agentskills.io spec (agent instructions)
├── fineract_skill/
│   ├── __init__.py             # Public API exports
│   ├── client.py               # HTTP client (auth, headers, error handling)
│   ├── helpers.py              # Date formatting, result wrapping
│   ├── tools.py                # Tool registry (ToolSpec, discovery, schema export)
│   └── domains/
│       ├── __init__.py
│       ├── clients.py          # Client management (15 tools)
│       ├── loans.py            # Loan lifecycle (15 tools)
│       ├── savings.py          # Savings accounts (9 tools)
│       ├── groups.py           # Groups & centers (8 tools)
│       ├── accounting.py       # GL & journal entries (3 tools)
│       ├── staff.py            # Staff & offices (4 tools)
│       ├── products.py         # Loan & savings products (4 tools)
│       ├── charges.py          # Fee/penalty definitions (4 tools)
│       └── reports.py          # Report management (5 tools)
├── references/
│   ├── checklist.md            # Validation checklist
│   ├── environment-config.md   # Environment variable reference
│   └── tool-catalog.md         # Static tool catalog
├── scripts/
│   └── validate-skill.py       # Metadata validation script
└── tests/
    └── test_skill.py           # Test suite (29 tests, mocked HTTP)
```

### Design Principles

1. **Framework-Agnostic**: No dependency on LangChain, LlamaIndex, or any specific agent framework. The skill exports plain Python callables and JSON schemas.
2. **Dependency Injection**: All domain functions accept a `FineractClient` as their first argument — no hidden globals.
3. **Standardised Responses**: Every tool returns `{"ok": bool, "data": ... | "error": ...}`.
4. **Discoverable**: The `TOOL_REGISTRY` enables programmatic tool discovery, filtering by domain, and schema export.
5. **Safe by Default**: Destructive tools are annotated, and report SQL tools require explicit opt-in.
6. **Testable**: All HTTP calls go through `httpx`, easily mocked with `respx`.

---

## Testing

```bash
cd agent-skill/mifos-agent
pip install -e ".[dev]"
pytest tests/ -v
```

Tests use `respx` to mock all HTTP requests — no live Fineract instance required.

---

## Covered Fineract Endpoints

| Endpoint | Methods | Skill Domain |
|---|---|---|
| `/clients` | GET, POST, PUT, DELETE | `clients` |
| `/clients/{id}/accounts` | GET | `clients` |
| `/clients/{id}/identifiers` | GET, POST | `clients` |
| `/clients/{id}/documents` | GET | `clients` |
| `/clients/{id}/charges` | GET, POST | `clients` |
| `/clients/{id}/transactions` | GET | `clients` |
| `/clients/{id}/addresses` | GET | `clients` |
| `/search` | GET | `clients` |
| `/loans` | GET, POST, PUT, DELETE | `loans` |
| `/loans/{id}/transactions` | POST | `loans` |
| `/loans/template` | GET | `loans` |
| `/rescheduleloans` | POST | `loans` |
| `/savingsaccounts` | GET, POST | `savings` |
| `/savingsaccounts/{id}/transactions` | POST | `savings` |
| `/savingsaccounts/{id}/charges` | POST | `savings` |
| `/groups` | GET, POST | `groups` |
| `/centers` | GET, POST | `groups` |
| `/glaccounts` | GET | `accounting` |
| `/journalentries` | GET, POST | `accounting` |
| `/staff` | GET | `staff` |
| `/offices` | GET | `staff` |
| `/loanproducts` | GET | `products` |
| `/savingsproducts` | GET | `products` |
| `/charges` | GET, POST, PUT | `charges` |
| `/reports` | GET, POST, PUT | `reports` |
| `/runreports/{name}` | GET | `reports` |

---

## License

[MPL-2.0](../../LICENSE) — Copyright since 2025 Mifos Initiative
