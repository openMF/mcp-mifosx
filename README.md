# Mifos X - AI - Model Context Protocol (MCP) for Apache Fineract®

This project provides Model Context Protocol (MCP) servers for interacting with the Apache Fineract API, enabling AI agents to access financial data and operations. Implementations are available in **Python**, **Java (Quarkus)**, and **Node.js**.

---

## MCP Developer Tools

Use the **MCP Inspector** to test and debug your server:

```bash
npx @modelcontextprotocol/inspector
```

This starts a local web UI to connect to your MCP server via STDIO or SSE.

---

## Getting Started

### 1. Choose Your Implementation

#### **Python (Flask)**
**Prerequisites**: Python 3.8+, `flask`, `mcp.server.fastmcp`

**Steps**:
1. Install dependencies:
   ```bash
   pip install mcp[cli] uv flask
   ```

   > **Note for zsh users**: If you're using zsh, be sure to quote extras to avoid shell expansion errors:
   >
   > ```bash
   > pip install 'mcp[cli]' uv flask
   > ```
2. Run the server:
   ```bash
   mcp dev app.py
   ```

#### **Java (Quarkus)**
**Prerequisites**: JDK 17+, Maven

**Steps**:
1. Configure environment variables in your shell or IDE:
   ```bash
   export MIFOSX_BASE_URL="https://your-fineract-instance"
   export MIFOSX_BASIC_AUTH_TOKEN="your_api_token"
   export MIFOS_TENANT_ID="default"
   ```
2. Run via JBang (for quick execution):
   ```bash
   jbang --quiet org.mifos.community.ai:mcp-server:1.0.0-SNAPSHOT:runner
   ```
3. (Optional) Build a native executable:
   ```bash
   ./mvnw package -Dnative
   ./target/mcp-server-1.0.0-SNAPSHOT-runner
   ```

#### **Node.js**
**Prerequisites**: Node.js 16+, npm

**Steps**:
1. Install dependencies:
   ```bash
   cd nodejs && npm install
   ```
2. Configure environment variables in `.env`:
   ```bash
   cp .env.example .env
   ```
3. Run the server:
   ```bash
   npm run dev
   ```
4. Test with the built-in `inspect` script:
   ```bash
   npm run inspect
   ```

---

## Configuration

All implementations require the following environment variables:

| Variable               | Description                          |
|------------------------|--------------------------------------|
| `FINERACT_BASE_URL`    | Base URL of your Fineract instance   |
| `FINERACT_BASIC_AUTH_TOKEN` | API authentication token |
| `FINERACT_TENANT_ID`   | Tenant identifier (default: `default`) |

**Note**: Java uses `MIFOSX_` prefixed variables (e.g., `MIFOSX_BASE_URL`).

---

## Available Resources

The MCP server exposes these resources:

### Core Resources
- `fineract://clients`  
  List all clients
- `fineract://clients/{clientId}`  
  Get details for a specific client
- `fineract://loans`  
  List all loans
- `fineract://loans/{loanId}`  
  Get details for a specific loan

### Tools
- `search_clients`  
  Search clients by name/attributes
- `create_client`  
  Create a new client (Node.js/Python only)
- `update_loan_status`  
  Update loan status (Java/Python only)

---

## Building Native Executables (Java Only)

For Java (Quarkus), create a native executable:
```bash
./mvnw package -Dnative -Dquarkus.native.container-build=true
./target/mcp-server-1.0.0-SNAPSHOT-runner
```

---

## Testing with MCP Inspector

1. Start your MCP server (Python/Java/Node.js).
2. Run the inspector:
   ```bash
   npx @modelcontextprotocol/inspector
   ```
3. Connect to the server using the `STDIO` transport.

---

## Contributing

- **Python**: Modify `python/app.py` and `server.js` for new resources.
- **Java**: Extend `src/main/java/org/mifos/community/ai/...` for new endpoints.
- **Node.js**: Update `nodejs/src/server.js` and add Zod schemas for validation.

---

## Contact

- Apache Fineract Community: [https://community.apache.org/](https://community.apache.org/)
- MCP Specification: [https://modelcontextprotocol.org](https://modelcontextprotocol.org)

---

## Guides

- **Java/Quarkus**: [Quarkus MCP Guide](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
- **Node.js**: Use `npm run inspect` for live reloading
- **Python**: Run with `python app.py` and configure `.env`

---


### Key Features:
- **Standardized API access** via `fineract://` URIs
- **MCP-compliant** with STDIO/SSE transports
- **Environment-agnostic** configuration

