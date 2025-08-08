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

## Examples - Backoffice Agent

| Video URL | Title | Prompt |
| :--- | :--- | :--- |
| https://youtu.be/MDQKRoz5GKw?si=69X77C58nFhy6Ioh | Join and Try the Mifos MCP | N/A |
| https://youtu.be/y5MR3j8EGM4?si=zXTurBNql4xF5CGY | Create Client   | |
| https://youtu.be/qJsC25cd-1g?si=qQzX8DeOe0_2qhfr | Activate Client   | |
| https://youtu.be/X1g_nVDsRnM?si=K7vsAN7gOLEC2OG0 | Add Address to Clients   | |
| https://youtu.be/xeL9_sycwA8?si=AtV6F4WhTvcDspSp | Add Personal Reference to Clients   | |
| https://youtu.be/2ioN_8z_uaY?si=ZTB5rCrgS2jTpC4- | Approve Loan Application   | |
| https://youtu.be/5EdgUyLyP0w?si=L0UdYjXlyYF6faL5 | Create Loan Application   | |
| https://youtu.be/dDebmrn4lB0?si=0GTf4asCBHnsu27f | Disbursement of Loan   | |
| https://youtu.be/N3wnyJCh_Ik?si=gSy5LrJdFF2kfzHd | Make Loan Repayment   | |
| https://youtu.be/IKGMeAJBAOk?si=N27rE64dn7qxmMBk | Create Loan Product   | |
| https://youtu.be/bOuTj97hyqU?si=9bpno4Kp0II1IfPY | Create Savings Product   | |
| https://youtu.be/l-Z7LlE3AnM?si=yQM4lloJL8Hu6yv8 | Create Savings Account Application   | |
| https://youtu.be/DJgUiRYK-rE?si=YatfVgOgpbP4wV91 | Activate Savings Account   | |
| https://youtu.be/iQduVpiURK0?si=RlT7QPPEz_e37oBv | Activate Savings Account   | |
| https://youtu.be/Q5ExlhalG8U?si=TwbsUZX30G3JeNJy | Approve Savings Application   | |
| https://youtu.be/Od7KFqktUtI?si=gPJNlLOB_7D74QdS | Make Deposit Transaction   | |
| https://youtu.be/9OL6N5wKG7c?si=R50RjTK6GI_ODuUs | Make Withdrawal Transaction   | |
---

## Contact

- Mifos Community: [https://mifos.org/](https://mifos.org/)
---

### Key Features:
- **Standardized API access** via `fineract://` URIs
- **MCP-compliant** with STDIO/SSE transports
- **Environment-agnostic** configuration

