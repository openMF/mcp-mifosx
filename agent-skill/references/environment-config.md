# Environment Configuration Reference

All variables are read from a `.env` file at the root of `agent-skill/`.

| Variable | Required | Default | Description |
|---|---|---|---|
| `FINERACT_BASE_URL` | **Yes** | *(none)* | Full URL to the Fineract v1 API endpoint. For the public sandbox: `https://sandbox.mifos.community/fineract-provider/api/v1` |
| `FINERACT_USERNAME` | No | `mifos` | HTTP Basic-Auth username. |
| `FINERACT_PASSWORD` | No | `password` | HTTP Basic-Auth password. |
| `FINERACT_TENANT_ID` | No | `default` | Multi-tenant identifier sent via the `Fineract-Platform-TenantId` header. |
| `FINERACT_TIMEOUT` | No | `30` | Request timeout in seconds. Increase for slow sandbox environments. |
| `FINERACT_SKIP_TLS_VERIFY` | No | `false` | Set to `true` only for local development with self-signed TLS certificates. Never enable in production. |

## Authentication Model

The client uses HTTP Basic Authentication. Credentials are sent in every request via the `Authorization: Basic <base64>` header. The `FineractClient` class handles encoding automatically.

## Tenant Header

Every request includes the `Fineract-Platform-TenantId` header. The default tenant on the public sandbox is `default`. Multi-tenant deployments require the correct tenant ID to be configured.
