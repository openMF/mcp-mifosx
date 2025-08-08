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
| https://youtu.be/y5MR3j8EGM4?si=zXTurBNql4xF5CGY | Create Clients | Create the client using first name: OCTAVIO, last name: PAZ, email address: pazo@fintecheando.mx, mobile number: 5518098299 and external id: OCPZ99 |
| https://youtu.be/qJsC25cd-1g?si=qQzX8DeOe0_2qhfr | Activate Clients   | Activate the client OCTAVIO PAZ |
| https://youtu.be/X1g_nVDsRnM?si=K7vsAN7gOLEC2OG0 | Add Address to Clients   | Add the address to the client OCTAVIO PAZ. Fields: address type: HOME, address: PLAZA DE LORETO, neighborhood: DOCTOR ALFONZO, number: NUMBER 10, city: CDMX, country: MÉXICO, postal code: 54440, state province: CDMX. |
| https://youtu.be/xeL9_sycwA8?si=AtV6F4WhTvcDspSp | Add Personal Reference to Clients   | Add the family member to the client OCTAVIO PAZ. First name: Maria, middle name: Elena, last name: Ramírez, age: 27, relationship: Sister, gender: FEMALE, date of birth: 15 March 1998, qualification: Bachelor’s Degree, is dependent, profession: STUDENT, marital status: SINGLE. |
| https://youtu.be/IKGMeAJBAOk?si=N27rE64dn7qxmMBk | Create a Loan Product   | Create a default loan product named "SILVER" with short name "ST01", principal 10000, 5 repayments, nominal interest rate 10.0%, repayment frequency 2 MONTHS, currency USD. |
| https://youtu.be/5EdgUyLyP0w?si=L0UdYjXlyYF6faL5 | Create Loan Application   | Apply for an individual loan account for the client OCTAVIO PAZ using loan product SILVER. |
| https://youtu.be/2ioN_8z_uaY?si=ZTB5rCrgS2jTpC4- | Approve Loan Application   | Approve the loan account  |
| https://youtu.be/dDebmrn4lB0?si=0GTf4asCBHnsu27f | Disbursement of Loan   | Disburse the loan account using payment type Money Transfer. |
| https://youtu.be/N3wnyJCh_Ik?si=gSy5LrJdFF2kfzHd | Make Loan Repayment   | Make a repayment for loan account 6 using Money Transfer. Set the amount to 6687.59, the date to 06 AUGUST 2025, and use external ID RT33. Add the note “FYI” and include payment account number 100, check number 101, routing code 102, receipt number 103, and bank number 1 |
| https://youtu.be/bOuTj97hyqU?si=9bpno4Kp0II1IfPY | Create Savings Product   |  Create a default savings product with the name "WALLET", short name "TSWL", description "WALLET PRODUCT", and currency code "USD". |
| https://youtu.be/l-Z7LlE3AnM?si=yQM4lloJL8Hu6yv8 | Create a Savings Account Application   | Apply for a savings account for the client OCTAVIO PAZ using savings product WALLET and external ID STP1. |
| https://youtu.be/Q5ExlhalG8U?si=TwbsUZX30G3JeNJy | Approve Savings Application   | Approve the savings account and include the note: "MY FIRST APPROVAL". |
| https://youtu.be/DJgUiRYK-rE?si=YatfVgOgpbP4wV91 | Activate Savings Account   | Activate the savings account |
| https://youtu.be/Od7KFqktUtI?si=gPJNlLOB_7D74QdS | Make a Deposit Transaction   | Create a savings transaction for client with the account number 1. It's a DEPOSIT of 5000 using Money Transfer. The note should be "Monthly saving". |
| https://youtu.be/9OL6N5wKG7c?si=R50RjTK6GI_ODuUs | Make a Withdrawal Transaction   | Create a savings transaction for client with the account number 1. It's a WITHDRAWAL of 2000 using Money Transfer. Add the note "Emergency expense". |

---

## Contact

- Mifos Community: https://mifos.org
- Mifos MCP (Docker): https://hub.docker.com/r/openmf/mifos-mcp
- Chabot (Use the Groq provider and select the Mifos MCP): https://ai.mifos.community
---

### Key Features:
- **MCP-compliant** with STDIO/SSE transports
- **Environment-agnostic** configuration

