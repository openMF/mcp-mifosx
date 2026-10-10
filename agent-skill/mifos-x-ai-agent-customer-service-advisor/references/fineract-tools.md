# Fineract Tools (optional, read-only)

The advisor works without any tools. When read-only Mifos X / Apache Fineract tools are available, use them only for Step 5 of the workflow: detecting whether the customer already exists and whether they have an active process.

## Allowed capabilities

| Capability | Mifos X MCP (Python server, as exposed in Pi) | `agent-skill` Python library | Go MCP server | Copilot gateway |
|---|---|---|---|---|
| Find a client by name | `mcp__mifosx__search_clients` (`nameQuery`) | `search_clients` | `search_clients` | `mifos_client_search` |
| Read a client profile | `mcp__mifosx__get_client` (`clientId`) | `get_client_details` | `get_client` | `mifos_client_details` |
| List the client's loans and savings | `mcp__mifosx__get_client_accts` (`clientId`) | `get_client_accounts` | `get_client_accounts` | `mifos_client_accounts` |

Use no other tool. Never call a tool that creates, updates, approves, disburses, deposits, withdraws, closes or deletes anything.

## How to use the results

1. Search only with a full name the customer already volunteered. Never ask for a name, ID number or account number to perform a lookup, and never tell the customer that their record will be checked.
2. If exactly one active client matches and the customer confirmed they are an existing customer, set `customer.existing_customer` to `YES` and `customer.fineract_client_id` to the client id.
3. If the client has an active loan, an open loan application, or an active deposit/savings account related to the stated need, set `active_process` to `true` and apply Rule 10.
4. If there are zero or several matches, set `customer.existing_customer` from what the customer said (or `UNKNOWN`) and leave `fineract_client_id` as `null`.
5. Never tell the customer what was found: no names, accounts, balances, statuses or dates. The data is for the handoff only.

## Restricting tools in Pi

Pi applies the restriction at the tool level. In `~/.pi/agent/mcp.json`, hide every tool by default and expose only read-only ones:

```json
{
  "mcpServers": {
    "mifosx": {
      "command": "python",
      "args": ["mcp_server.py"],
      "cwd": "<path to mcp-mifosx>/python",
      "env": {
        "MIFOSX_BASE_URL": "https://sandbox.mifos.community/fineract-provider/api/v1",
        "MIFOSX_USERNAME": "mifos",
        "MIFOSX_PASSWORD": "password",
        "MIFOSX_TENANT_ID": "default"
      },
      "exposure": "hidden",
      "toolExposure": {
        "search_clients": "direct",
        "get_client": "direct",
        "get_client_accts": "direct"
      }
    }
  }
}
```

Run `pi mcp list` to confirm that only these three tools show an exposure tag. Fineract still enforces the permissions of the configured user; in production the gateway forwards the calling officer's credentials.
