# Mifos X - AI - Model Context Protocol (MCP) 

This project provides Model Context Protocol (MCP) for the Mifos X Ecosystem, enabling AI agents to access financial data and operations. Implementations is available in **Java (Quarkus)**.

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

## Examples

| Video URL | Title | Description | Prompt |
| :--- | :--- | :--- | :--- |
|  |  |  |  |

---

## Contact

- Apache Fineract Community: [https://community.apache.org/](https://community.apache.org/)
- MCP Specification: [https://modelcontextprotocol.org](https://modelcontextprotocol.org)

---

## Guides

- **Java/Quarkus**: [Quarkus MCP Guide](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)

---


### Key Features:
- **Standardized API access** via `fineract://` URIs
- **MCP-compliant** with STDIO/SSE transports
- **Environment-agnostic** configuration

