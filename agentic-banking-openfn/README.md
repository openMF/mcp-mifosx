# Agentic Loan Origination – Spring Boot 4 / OpenFn Lightning / Apache Fineract

The OpenFn variant of the agentic loan origination flow. The multi-step underwriting
that `agentic-banking-temporal` runs as a Temporal workflow is executed here as
**OpenFn Lightning** workflows, while a Spring Boot **Agentic Gateway** owns the REST
API, the case state and the human-in-the-loop (HITL) review.

## Architecture

```
Client ──► Agentic Gateway (Spring Boot, :8080)
             │  POST webhook                         ▲  POST /api/openfn/callback/*
             ▼                                       │
           OpenFn Lightning (:4000) ── worker runs the jobs ──┘
```

Two workflows are defined in [`openfn/project.yaml`](openfn/project.yaml):

| Workflow | Triggered by | Jobs |
|---|---|---|
| **Loan Submit – Auto Underwriting** | `POST /api/loans/submit` | normalize → notify `GATHERING_DATA` → bank + credit → documents → specialist assessments → decision → callback `PENDING_HUMAN_REVIEW` |
| **Loan Review – Fineract Write-back** | `POST /api/loans/{id}/review` with `APPROVE` | prepare → create loan in Fineract → callback `COMPLETED` |

Each job reports back to the gateway over HTTP. The gateway tells the jobs where to
call back by sending `gatewayBaseUrl` in the webhook payload.

> **Current state:** the bank/credit data, document OCR and the decision step are
> simulated or rule-based (the Ollama call is commented out), and the
> *Create + Approve Loan in Fineract* job returns a simulated `loanId` – no request is
> sent to Fineract yet. The flow is complete end to end; these jobs are the extension
> points.

## Prerequisites

- Java 21
- Maven 3.9+
- Docker with Docker Compose (on Windows: Docker Desktop with **WSL integration**
  enabled for your distro – *Settings → Resources → WSL Integration*)
- Node.js (for `npx @openfn/cli`)

## 1. Deploy OpenFn Lightning

Lightning is self-hosted from its repository with Docker Compose
([OpenFn/lightning](https://github.com/OpenFn/lightning)). Clone it **outside** this repo:

```bash
git clone https://github.com/OpenFn/lightning.git
cd lightning

docker compose build                                   # first build takes a while
docker compose run --rm web mix ecto.migrate
docker compose run --rm web mix run priv/repo/demo.exs # demo data + login
docker compose up -d
```

`demo.exs` resets the database and creates a superuser:
**`super@openfn.org` / `welcome12345`**. Check it is up:

```bash
docker compose ps                    # web should be "healthy"
curl localhost:4000/health_check
```

Open **http://localhost:4000** and log in.

Notes:

- In dev mode Lightning starts its own worker inside the `web` container. If the
  separate `worker` service keeps restarting (e.g. `ERR_PNPM_CMD_SHIM_CHMOD`), stop it –
  runs still execute: `docker compose stop worker`.
- If the UI loads without styling, the asset build is still running or failed; build it
  manually: `docker compose exec web mix tailwind default && docker compose exec web mix esbuild default`.

## 2. Deploy the OpenFn project

Create an API token in Lightning at **http://localhost:4000/profile/tokens**, then:

```bash
cd agentic-banking-openfn/openfn

export OPENFN_ENDPOINT=http://localhost:4000
export OPENFN_API_KEY=<your-token>

npx @openfn/cli deploy -p project.yaml
```

Confirm the diff with `y`. The CLI writes `.state.json` next to `project.yaml` with the
ids of this Lightning instance (it is gitignored). Re-running the same command updates
the existing project instead of creating a new one.

Get the two webhook URLs (or copy them from the trigger node of each workflow in the UI):

```bash
python3 -c "import json;s=json.load(open('.state.json'));[print(w['name'],'->','http://localhost:4000/i/'+t['id']) for w in s['workflows'].values() for t in w['triggers'].values()]"
```

## 3. Build and run the gateway

```bash
cd agentic-banking-openfn
mvn clean package

export OPENFN_WEBHOOK_LOAN_SUBMIT=http://localhost:4000/i/<loan-submit-trigger-id>
export OPENFN_WEBHOOK_LOAN_REVIEW=http://localhost:4000/i/<loan-review-trigger-id>

java -jar target/agentic-banking-openfn-1.0.0-SNAPSHOT.jar
```

The API listens on **http://localhost:8080**.

### Making callbacks reach the gateway

OpenFn jobs run inside the Lightning container, so `OPENFN_GATEWAY_BASE_URL` must be an
address **that container** can reach. The default `http://host.docker.internal:8080`
works when the gateway runs directly on a macOS/Windows host.

On **WSL2 + Docker Desktop**, `host.docker.internal` points at the Windows host, not
your WSL distro, and callbacks fail with `ECONNREFUSED 192.168.65.254:8080`. Use the
WSL IP instead:

```bash
export OPENFN_GATEWAY_BASE_URL=http://$(hostname -I | awk '{print $1}'):8080
```

The WSL IP changes after a reboot, so set it again each session.

### Configuration

| Variable | Default | Purpose |
|---|---|---|
| `OPENFN_WEBHOOK_LOAN_SUBMIT` | `http://localhost:4000/i/loan-submit` | Loan Submit trigger URL |
| `OPENFN_WEBHOOK_LOAN_REVIEW` | `http://localhost:4000/i/loan-review` | Loan Review trigger URL |
| `OPENFN_GATEWAY_BASE_URL` | `http://host.docker.internal:8080` | Where OpenFn jobs call back to the gateway |
| `OPENFN_CALLBACK_SECRET` | `change-me-in-prod` | Shared secret sent by jobs as `X-OpenFn-Secret` |
| `OPENFN_BASE_URL` | `http://localhost:4000` | Lightning base URL (run status lookups) |
| `OPENFN_API_TOKEN` | – | Lightning API token (run status lookups) |
| `LOAN_LOCAL_FALLBACK` | `true` | Run underwriting in the gateway if OpenFn is unreachable |
| `OLLAMA_URL` / `OLLAMA_MODEL` | `http://localhost:11434` / `llama3.2:latest` | Local Ollama |
| `FINERACT_BASE_URL`, `FINERACT_USER`, `FINERACT_PASSWORD`, `FINERACT_TENANT` | sandbox | Reserved for the Fineract write-back job (not used yet) |

- While `OPENFN_CALLBACK_SECRET` is left at `change-me-in-prod`, the gateway accepts
  callbacks **without checking the secret**. Set a real value outside local development.
- With `LOAN_LOCAL_FALLBACK=true`, a broken OpenFn setup still returns a normal-looking
  result from the gateway's own underwriting. Set it to `false` when testing the OpenFn
  integration so failures surface.

## 4. Test the flow end to end

```bash
# Submit – note the workflowId
curl -s -X POST localhost:8080/api/loans/submit -H 'Content-Type: application/json' \
  -d '{"applicantId":"APP-1001","fullName":"Alice Example","requestedAmount":20000,"termMonths":12}'

WF=<workflowId>

# Poll until PENDING_HUMAN_REVIEW, then read the AI recommendation
curl -s localhost:8080/api/loans/$WF/status
curl -s localhost:8080/api/loans/$WF/summary

# Human review – APPROVE triggers the Fineract write-back workflow
curl -s -X POST localhost:8080/api/loans/$WF/review \
  -H 'Content-Type: application/json' -d '{"action":"APPROVE"}'

# Poll until COMPLETED, then read the final result
curl -s localhost:8080/api/loans/$WF/final
```

Expected status sequence: `OPENFN_TRIGGERED → GATHERING_DATA → PENDING_HUMAN_REVIEW →
WRITING_TO_FINERACT → COMPLETED`. Each step's run and logs are visible in Lightning under
the project's **History**.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Status stays `OPENFN_TRIGGERED`, job log shows `ECONNREFUSED` | Lightning cannot reach the gateway – set `OPENFN_GATEWAY_BASE_URL` (see above). |
| Status stays `OPENFN_TRIGGERED`, job log shows `returned 401` | `OPENFN_CALLBACK_SECRET` differs between the gateway that submitted the loan and the one receiving callbacks. |
| Submit returns `500`, gateway log shows `OpenFn webhook trigger failed` | Wrong or stale webhook URL – re-read it from `.state.json` after each deploy to a new instance. |
| `openfn deploy` fails with `source_job_id or source_trigger_id must be present` | Outdated `project.yaml` – pull the latest version of this folder. |
| Runs never start | No worker connected – check `docker compose logs web` for the built-in worker. |

## Tests

```bash
mvn test
```

## License

See the repository [LICENSE](../LICENSE).
