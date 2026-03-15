# Mifos MCP - Model Context Protocol (MCP) 

This project provides Model Context Protocol (MCP) for the Mifos X Ecosystem, enabling AI agents to access financial data and operations. 

Implementations are available in:
- **Java (Quarkus)** — 38 typed tools (across Backoffice and Recommendations).
- **Python (FastMCP)** — 49 typed tools (modular domain-driven design).

---

## 🏗️ Architecture Overview

The Mifos MCP Server acts as a standalone, stateless integration tier that bridges any AI assistant or agent framework to the **Apache Fineract** banking backend.

```text
┌──────────────────────────────────────────────┐
│            Apache Fineract / Mifos X          │
└───────────────────────┬──────────────────────┘
                        │ REST API
┌───────────────────────▼──────────────────────┐
│        mcp-mifosx  (Primary Repo)            │
│  ┌────────────────────┴───────────────────┐  │
│  │   /java (Quarkus)   │   /python (FastMCP) │  │
│  │    - Backoffice     │    - 49 Tools       │  │
│  │    - Recommendations│    - Modular Design │  │
│  └────────────────────┬───────────────────┘  │
└───────────────────────┼──────────────────────┘
                        │ MCP Protocol (stdio / SSE)
          ┌─────────────┼──────────────┐
          ▼             ▼              ▼
    Mifos X WebApp   Claude Code     n8n / Custom
    AI Assistant     (claude.ai)     Workflow Agent
    (your client)   (external)       (your client)
```

This repository is **framework-agnostic**. The client (LLM brain, UI, memory) lives in a separate repository. Any MCP-compatible system can plug in.

---

## 🔄 Implementation Synchronization

While this repository hosts two different programming languages, they are kept in **functional parity** where possible to ensure a consistent experience.

### How they "Sync":
1. **Tool Specification**: Both implementations aim to expose the same core banking tools. 
   - **Python** currently leads with **49 tools**.
   - **Java** provides **38 tools** (21 for Backoffice operations and 17 for User Recommendations).
2. **API Alignment**: Both implementations are built against the same **Apache Fineract REST API**. They share identical business logic for field validation and error handling.
3. **Stateless Parity**: Both implementations follow a strictly **stateless** design. Neither implementation stores user data or AI memory.
4. **Testing Protocol**: We use a shared set of "Smoke Tests" to verify that both implementations return identical JSON structures to the LLM.

---

## 📂 Project Structure

This repository is structured to support multiple implementations and client integrations.

```
.
├── README.md               # Root entry point & cross-implementation guide
├── python/                 # Python Implementation (FastMCP)
│   ├── mcp_server.py       # Main entry point for the MCP server
│   ├── tools/              # Domain-specific banking tools (Loans, Clients, etc.)
│   └── core/               # API Server & WebSocket gateway
└── java/                   # Java Implementation (Quarkus)
    ├── backoffice/         # Core banking tools (21 tools)
    └── userrecommendation/ # Recommendation engine tools (17 tools)
```

---

## 🚀 Getting Started

### 1. Choose Your Implementation

#### ☕ **Java (Quarkus)**
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

#### 🐍 **Python (FastMCP)**
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

## 🛠️ Available Tools Summary

The server exposes tools across multiple banking domains.

| Domain | Python Tools | Java Tools |
| :--- | :---: | :---: |
| **Clients & Groups** | 16 | Included |
| **Loans & Savings** | 20 | Included |
| **Staff & Accounting**| 13 | Included |
| **Recommendations** | - | 17 |
| **Total** | **49** | **38** |

---

## 🔍 Testing with MCP Inspector

Use the **MCP Inspector** to test and debug your server interactively:

```bash
npx @modelcontextprotocol/inspector <command_to_run_yours_server>
```

**For Python**:
```bash
npx @modelcontextprotocol/inspector python python/mcp_server.py
```

---

## 📺 Examples - Backoffice Agent

| Video URL | Title | Prompt |
| :--- | :--- | :--- |
| https://youtu.be/MDQKRoz5GKw | Join and Try the Mifos MCP | N/A |
| https://youtu.be/y5MR3j8EGM4 | Create Client | "Create client OCTAVIO PAZ" |
| https://youtu.be/2ioN_8z_uaY | Approve Loan Application | "Approve the loan account" |
| https://youtu.be/Od7KFqktUtI | Make a Deposit Transaction | "DEPOSIT of 5000 into account 1" |

---

## 🔒 Security & Guardrails
- **Universal Compatibility** — Works with Claude, GPT-4, Qwen, or any MCP client.
- **Data Sovereignty** — The server makes no external calls. 
- **RBAC Enforced** — Every action is validated against Fineract's native permissions.

---

## 🔗 Contact & Community

- Mifos Community: https://mifos.org
- Mifos MCP (Docker): https://hub.docker.com/r/openmf/mifos-mcp
- Chatbot Demo: https://ai.mifos.community
