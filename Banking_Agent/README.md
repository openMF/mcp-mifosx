# 🏦 Banking Agent — Mifos Headless MCP Server

A Python FastAPI server and Go CLI that expose Apache Fineract's banking operations (clients, loans, savings) as clean REST endpoints — designed for AI agents, Go CLIs, and any HTTP client to consume.

---

## 📁 Project Structure

```
Banking_Agent/
├── core/
│   └── api_server.py          # FastAPI app — 27 REST endpoints
├── tools/
│   ├── mcp_adapter.py         # Fineract HTTP adapter (shared)
│   ├── registry.py            # AI intent router (DomainRegistry)
│   └── domains/
│       ├── clients.py         # Client & group operations
│       ├── loans.py           # Loan lifecycle operations
│       └── savings.py         # Savings account operations
├── cli/                       # Go CLI (Cobra)
│   ├── main.go
│   ├── cmd/                   # clients, groups, loans, savings, router
│   └── internal/api/          # Shared HTTP client
├── docker-compose.yml         # Fineract + MariaDB containers
├── .env.example               # Environment variable template
├── requirements.txt           # Python dependencies
└── CLI_WALKTHROUGH.md         # Proof of CLI working
```

---

## ⚡ Quick Start

### 1. Prerequisites
- Python 3.11+
- Go 1.21+
- Docker & Docker Compose

### 2. Configure environment
```bash
cp .env.example .env
# Edit .env and fill in your Fineract credentials
```

The key variables in `.env`:
```env
MIFOSX_BASE_URL=https://localhost:8443/fineract-provider/api/v1
MIFOSX_USERNAME=mifos
MIFOSX_PASSWORD=password
MIFOSX_TENANT_ID=default
```

### 3. Start Fineract with Docker
```bash
docker-compose up -d
# Wait ~60 seconds for Fineract to initialise
docker ps  # both 'fineract' and 'fineract-db' should show healthy
```

### 4. Install Python dependencies
```bash
pip install -r requirements.txt
```

### 5. Verify Fineract connection
```bash
python mcp_adapter.py
# ✅ Should print: Found N users in the Fineract Database
```

### 6. Start the API server
```bash
python -m uvicorn core.api_server:app --host 0.0.0.0 --port 8000
```

Interactive API docs: **http://localhost:8000/docs**

---

## 🖥️ Go CLI

### Build
```bash
cd cli
go mod tidy
go build -o mifos .
```

### Usage
```bash
# Search clients
./mifos clients search --name "John"

# Get client accounts
./mifos clients accounts 101

# Create a client
./mifos clients create --first John --last Doe --mobile +525551234567

# Apply for a loan
./mifos loans create --client 101 --principal 20000 --months 12

# Approve & disburse a loan
./mifos loans approve 42

# Make a repayment
./mifos loans repay 42 --amount 1500

# Open a savings account
./mifos savings create --client 101

# Deposit into savings
./mifos savings deposit 7 --amount 500

# Withdraw from savings
./mifos savings withdraw 7 --amount 100

# Ask the AI router which tools to use
./mifos route --prompt "I need to check a client's loan balance"

# Point to a different server
./mifos --server http://prod.example.com:8000 clients get 1
# Or via environment variable:
export MIFOS_SERVER=http://prod.example.com:8000
```

Run `./mifos --help` or `./mifos <command> --help` for full flag reference.

---

## 🌐 API Endpoints Summary

| Domain | Endpoints |
|--------|-----------|
| **AI Router** | `POST /api/router/intent` |
| **Clients** | `GET/POST /api/clients`, search, get, accounts, activate, update-mobile, close |
| **Groups** | `POST /api/groups`, `GET /api/groups/{id}` |
| **Loans** | `GET/POST /api/loans`, schedule, approve-disburse, reject, repayment, late-fee, waive-interest |
| **Savings** | `GET/POST /api/savings`, transactions, approve-activate, close, deposit, withdraw, charge, post-interest |

Full interactive docs at **http://localhost:8000/docs** once the server is running.

---

## 🛠️ Development Notes

- All domain functions live in `tools/domains/` and import `fineract_client` from `tools/mcp_adapter.py`
- The `tools/registry.py` `DomainRegistry` routes natural-language intent prompts to the correct tool set — designed for Phase 3 SLM-based routing
- SSL warnings for local Docker self-signed certs are suppressed automatically in the adapter
- Error responses from Fineract are parsed and surfaced as human-readable `400` responses by the API server
