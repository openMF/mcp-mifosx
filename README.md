# Mifos MCP - Model Context Protocol (MCP) 

This project provides Model Context Protocol (MCP) for the Mifos X Ecosystem, enabling AI agents to access financial data and operations. 

Implementations are available in:
- **Java (Quarkus)** — 38 typed tools (across Backoffice and Recommendations).
- **Python (FastMCP)** — 49 typed tools (modular domain-driven design).
- **Rust** — 66 typed tools (high-performance async I/O with exclusive bulk operations).

---

## Architecture Overview

The Mifos MCP Server acts as a standalone, stateless integration tier that bridges any AI assistant or agent framework to the **Apache Fineract** banking backend.

```text
┌──────────────────────────────────────────────┐
│            Apache Fineract / Mifos X          │
└───────────────────────┬──────────────────────┘
                        │ REST API
┌───────────────────────────────▼───────────────────────────────┐
│                 mcp-mifosx (Primary Repo)                     │
│                                                               │
│  ┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐  │
│  │ /java (Quarkus) │ │/python (FastMCP)│ │  /rust (Tokio)  │  │
│  │                 │ │                 │ │                 │  │
│  │ - 38 Tools      │ │ - 49 Tools      │ │ - 66 Tools      │  │
│  │ - Backoffice    │ │ - Modular Design│ │ - Async I/O     │  │
│  │ - Recommend.    │ │                 │ │ - Bulk Actions  │  │
│  └────────┬────────┘ └────────┬────────┘ └────────┬────────┘  │
└───────────┴───────────────────┼───────────────────┴───────────┘
                        │ MCP Protocol (stdio / SSE)
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
    Mifos X WebApp   Claude Code     n8n / Custom
    AI Assistant     (claude.ai)     Workflow Agent
    (your client)   (external)       (your client)
```

This repository is **framework-agnostic**. The client (LLM brain, UI, memory) lives in a separate repository. Any MCP-compatible system can plug in.

---

## Implementation Synchronization

While this repository hosts two different programming languages, they are kept in **functional parity** where possible to ensure a consistent experience.

### How they "Sync":
1. **Tool Specification**: All implementations aim to expose the same core banking tools. 
   - **Rust** currently leads with **66 tools**, uniquely featuring high-concurrency Bulk Operations.
   - **Python** provides **49 tools** using a modular domain design.
   - **Java** provides **38 tools** (21 for Backoffice operations and 17 for User Recommendations).
2. **API Alignment**: All implementations are built against the same **Apache Fineract REST API**. They share identical logic for field routing.
3. **Stateless Parity**: All implementations follow a strictly **stateless** design. None of the servers store user data, PII, or AI memory.
4. **Testing Protocol**: Shared "Smoke Tests" ensure that all implementations return identical, predictable JSON structures to the LLM.

---

## Project Structure

This repository is structured to support multiple implementations and client integrations.

```
.
├── README.md               # Root entry point & cross-implementation guide
├── rust/                   # Rust Implementation (Tokio/Reqwest)
│   ├── src/                # Multi-threaded typed tools & bulk execution logic
│   └── Cargo.toml          # Rust package dependencies
├── python/                 # Python Implementation (FastMCP)
│   ├── mcp_server.py       # Main entry point for the MCP server
│   ├── tools/              # Domain-specific banking tools (Loans, Clients, etc.)
│   └── core/               # API Gateway handlers
└── java/                   # Java Implementation (Quarkus)
    ├── backoffice/         # Core banking tools
    └── userrecommendation/ # Recommendation engine tools
```

---

## Getting Started

### 1. Choose Your Implementation

#### **Rust (High-Performance)**
**Prerequisites**: Rust (Cargo)

**Steps**:
1. **Configure Environment**:
   Copy `rust/.env.example` to `rust/.env` and update credentials.
2. **Build and Run**:
   ```bash
   cd rust
   cargo build --release
   ./target/release/mcp-rust-mifosx
   ```

#### **Java (Quarkus)**
**Prerequisites**: JDK 21+, Maven

**Steps**:
1. **Configure Environment Variables**:
   ```bash
   export MIFOSX_BASE_URL="https://your-fineract-instance"
   export MIFOSX_BASIC_AUTH_TOKEN="your_api_token"
   export MIFOS_TENANT_ID="default"
   ```
2. **Run via JBang**:
   ```bash
   jbang --quiet org.mifos.community.ai:mcp-server:1.0.0-SNAPSHOT:runner
   ```
3. **Build Native Executable** (Optional):
   ```bash
   cd java/backoffice
   ./mvnw package -Dnative
   ./target/mcp-server-1.0.0-SNAPSHOT-runner
   ```

#### **Python (FastMCP)**
**Prerequisites**: Python 3.10+, pip

**Steps**:
1. **Navigate to the Python directory**:
   ```bash
   cd python
   ```
2. **Install dependencies**:
   ```bash
   pip install -r requirements.txt
   ```
3. **Configure Environment**:
   Copy `.env.example` to `.env` and fill in your details.
4. **Run the Server**:
   ```bash
   python mcp_server.py
   ```

---

## Available Tools Summary

The exact number and categorization of tools depend on the core server implementation deployed:

### Rust (66 Tools)
*Built for asynchronous scale and bulk processing.*
- **Clients & Groups**: 27 Tools
- **Loans & Savings**: 21 Tools
- **Staff & Accounting**: 7 Tools
- **Bulk Operations**: 11 Tools *(Exclusive to Rust)*

### Python (49 Tools)
*Domain-driven design bridging AI directly to Fineract.*
- **Clients & Groups**: 16 Tools
- **Loans & Savings**: 20 Tools
- **Staff & Accounting**: 13 Tools

### Java (38 Tools)
*Enterprise suite categorized between Backoffice and recommendation engines.*
- **Backoffice Operations**: 21 Tools *(Covers Clients, Loans, Savings)*
- **User Recommendations**: 17 Tools *(Exclusive to Java)*

---

## MCP Tool Flow (For Contributors)

This section explains how MCP tools in this repository are used end-to-end, and how to add new ones safely.

### What MCP tools are in this project

MCP tools are typed functions that expose core Fineract operations to an LLM client.

- **Clients & Groups**: search clients, fetch accounts, create/activate client, manage groups/centers
- **Loans & Savings**: loan details, repayment schedule, create loan, approve/disburse, deposit/withdraw
- **Staff & Accounting**: list staff/offices, GL accounts, journal entries
- **Bulk (Rust only)**: high-throughput batch operations for concurrent processing

Each implementation (Java, Python, Rust) follows the same architecture goal: **translate MCP tool calls into Fineract REST calls and return predictable JSON to the client**.

### End-to-end flow

```text
User Query -> LLM -> MCP Tool -> Apache Fineract -> MCP Response -> LLM Answer
```

1. User asks a natural-language question in an MCP-capable client.
2. LLM selects the most relevant MCP tool and prepares typed parameters.
3. MCP server executes the tool.
4. Tool calls Apache Fineract REST API.
5. Fineract returns API payload (or error).
6. MCP tool normalizes output and sends JSON response back to LLM.
7. LLM generates final user-facing answer.

### Example: natural language -> tool -> API -> output

**Natural language input**:

```text
"Show me details for loan 27"
```

**Selected MCP tool**:

```text
get_loan_details(loan_id=27)
```

**Underlying Fineract API call**:

```http
GET /loans/27
```

**MCP tool output (structured JSON)**:

```json
{
   "loanId": 27,
   "accountNo": "000000027",
   "productName": "SILVER",
   "status": "Active",
   "loanType": "Individual",
   "principal_USD": 10000.0,
   "outstandingBalance_USD": 6342.17,
   "interestRate_pct": 10.0,
   "submittedDate": "14 March 2026",
   "approvedDate": "15 March 2026",
   "disbursedDate": "16 March 2026"
}
```

### Sample JSON request/response (MCP tool call)

**Request**:

```json
{
   "tool": "get_loan_details",
   "arguments": {
      "loan_id": 27
   }
}
```

**Success response**:

```json
{
   "loanId": 27,
   "status": "Active",
   "outstandingBalance_USD": 6342.17
}
```

**Error response (invalid input)**:

```json
{
   "error": "Invalid loan_id. It must be a positive integer.",
   "httpStatusCode": 400,
   "loan_id": 0
}
```

### How to add a new MCP tool

Use this sequence to keep parity with existing coding patterns:

1. **Add domain function**:
    - Implement Fineract call and error normalization in the domain module.
    - Example path (Python): `python/tools/domains/<domain>.py`
2. **Expose as MCP tool**:
    - Register a wrapper with `@mcp.tool()` in server entrypoint.
    - Example path (Python): `python/mcp_server.py`
3. **Register in router (if used)**:
    - Add to domain router for intent-based tool filtering.
    - Example path (Python): `python/tools/registry.py`
4. **Add tests**:
    - Cover success path, invalid input, and API failure.
    - Example path (Python): `python/tests/test_<domain>.py`
5. **Update docs**:
    - Add tool name and behavior to implementation README for discoverability.

Following this workflow helps maintain tool consistency across implementations and keeps outputs reliable for AI agents.

---

## Testing with MCP Inspector

Use the **MCP Inspector** to test and debug your server interactively:

```bash
npx @modelcontextprotocol/inspector <command_to_run_yours_server>
```

**For Python**:
```bash
npx @modelcontextprotocol/inspector python python/mcp_server.py
```

---

## Examples - Backoffice Agent

| Video URL | Title | Prompt | Implementation |
| :--- | :--- | :--- | :--- |
| https://youtu.be/MDQKRoz5GKw?si=69X77C58nFhy6Ioh | Join and Try the Mifos MCP | Go to https://ai.mifos.community | **Java / Python / Rust** |
| https://youtu.be/y5MR3j8EGM4?si=zXTurBNql4xF5CGY | Create Client | Create client using name: OCTAVIO PAZ, email: octaviopaz@mifos.org, etc. | **Java / Python / Rust** |
| https://youtu.be/qJsC25cd-1g?si=qQzX8DeOe0_2qhfr | Activate Client | Activate the client OCTAVIO PAZ | **Java / Python / Rust** |
| https://youtu.be/X1g_nVDsRnM?si=K7vsAN7gOLEC2OG0 | Add Address to Client | Add the address to the client OCTAVIO PAZ (Plaza de Loreto) | **Java** |
| https://youtu.be/xeL9_sycwA8?si=AtV6F4WhTvcDspSp | Add Personal Reference | Add Maria Elena Ramírez as sister to OCTAVIO PAZ | **Java** |
| https://youtu.be/IKGMeAJBAOk?si=N27rE64dn7qxmMBk | Create a Loan Product | Create default loan product named "SILVER" (10% interest) | **Java** |
| https://youtu.be/5EdgUyLyP0w?si=L0UdYjXlyYF6faL5 | Create Loan Application | Apply for individual loan for OCTAVIO PAZ using SILVER | **Java / Python / Rust** |
| https://youtu.be/2ioN_8z_uaY?si=ZTB5rCrgS2jTpC4- | Approve Loan | Approve the loan account | **Java / Python / Rust** |
| https://youtu.be/dDebmrn4lB0?si=0GTf4asCBHnsu27f | Disbursement of Loan | Disburse loan account using Money Transfer | **Java / Python / Rust** |
| https://youtu.be/N3wnyJCh_Ik?si=gSy5LrJdFF2kfzHd | Make Loan Repayment | Make a repayment for account 6 (Amount: 6687.59) | **Java / Python / Rust** |
| https://youtu.be/bOuTj97hyqU?si=9bpno4Kp0II1IfPY | Create Savings Product | Create default savings product named "WALLET" | **Java** |
| https://youtu.be/l-Z7LlE3AnM?si=yQM4lloJL8Hu6yv8 | Create Savings App | Apply for savings account for OCTAVIO PAZ using WALLET | **Java / Python / Rust** |
| https://youtu.be/Q5ExlhalG8U?si=TwbsUZX30G3JeNJy | Approve Savings App | Approve the savings account with note "MY FIRST APPROVAL" | **Java / Python / Rust** |
| https://youtu.be/DJgUiRYK-rE?si=YatfVgOgpbP4wV91 | Activate Savings | Activate the savings account | **Java / Python / Rust** |
| https://youtu.be/Od7KFqktUtI?si=gPJNlLOB_7D74QdS | Make a Deposit | Create DEPOSIT of 5000 for account 1 | **Java / Python / Rust** |
| https://youtu.be/9OL6N5wKG7c?si=R50RjTK6GI_ODuUs | Make a Withdrawal | Create WITHDRAWAL of 2000 for account 1 | **Java / Python / Rust** |

---

## Security & Guardrails
- **Universal Compatibility** — Works with Claude, GPT-4, Qwen, or any MCP client.
- **Data Sovereignty** — The server makes no external calls. 
- **RBAC Enforced** — Every action is validated against Fineract's native permissions.

---

## Contact & Community

- Mifos Community: https://mifos.org
- Mifos MCP (Docker): https://hub.docker.com/r/openmf/mifos-mcp
- Chatbot Demo: https://ai.mifos.community
