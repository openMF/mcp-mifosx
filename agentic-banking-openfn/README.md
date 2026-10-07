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

- **Decision:** a local **Ollama** model aggregates the specialist assessments into a
  recommendation. If Ollama is unreachable or returns invalid JSON, a rule-based decision
  is used. A guardrail keeps the LLM from being more lenient than the assessments
  (e.g. it cannot APPROVE when an assessment is REFER).
- **Fineract write-back:** on APPROVE the review workflow creates a client, submits a loan
  with `externalId = workflowId` and approves it, then attaches the underwriting decision
  (model, recommendation, reasoning, assessments) as a **loan note**. Retries reuse the
  client/loan already created.

- **CURP documents:** the gateway renders the first page of each PDF to PNG; a local Ollama
  **vision** model extracts the CURP fields, which are validated (clave format, name match,
  "CURP Certificada: verificada con el Registro Civil", issue date within 30 days). A valid
  CURP clave becomes the Fineract client's `externalId`, and the analysis is attached as a
  second loan note.

> **Current state:** the bank/credit data step is still simulated.

## Prerequisites

- Java 21 and Maven 3.9+
- Docker with Docker Compose (on Windows: Docker Desktop with **WSL integration**
  enabled for your distro – *Settings → Resources → WSL Integration*)
- Node.js (for `npx @openfn/cli`)
- Python 3 (only for the helper one-liners below)
- About 20 GB of free disk space (Lightning and Fineract images, Ollama models)

The steps below run everything locally: Apache Fineract, Ollama, OpenFn Lightning and the
gateway. Follow them in order – later steps use values from earlier ones.

| Service | URL | Login |
|---|---|---|
| Apache Fineract | `https://localhost:8443/fineract-provider` | `mifos` / `password`, tenant `default` |
| Ollama | `http://localhost:11434` | – |
| OpenFn Lightning | `http://localhost:4000` | `super@openfn.org` / `welcome12345` |
| Agentic Gateway | `http://localhost:8080` | – |

## 1. Run Apache Fineract

Any Fineract works (a local one, or a remote one through its API gateway); the workflow
only needs its URL, a user and a loan product. For a local one, from a checkout of
[apache/fineract](https://github.com/apache/fineract), outside this repo:

```bash
git clone https://github.com/apache/fineract.git
cd fineract
./gradlew :fineract-provider:jibDockerBuild -x test   # builds the fineract:latest image
docker compose up -d                                   # Fineract + PostgreSQL
curl -k https://localhost:8443/fineract-provider/actuator/health   # wait for {"status":"UP"}
```

On Windows, clone with `--config core.autocrlf=input` and run `gradlew` instead of
`./gradlew`. See the [Fineract README](https://github.com/apache/fineract#how-to-run-using-docker-or-podman)
for other ways to run it (e.g. the pre-built `apache/fineract` image).

A new tenant has **no loan products**, and the workflow cannot submit a loan without one.
Create one and note its id (`resourceId`, used as `productId` in step 4):

```bash
curl -sk -u mifos:password -H 'Fineract-Platform-TenantId: default' -H 'Content-Type: application/json' \
  -X POST https://localhost:8443/fineract-provider/api/v1/loanproducts -d '{
  "name": "Agentic Personal Loan", "shortName": "AGPL", "currencyCode": "USD",
  "digitsAfterDecimal": 2, "inMultiplesOf": 0,
  "principal": 20000, "minPrincipal": 1000, "maxPrincipal": 100000,
  "numberOfRepayments": 12, "minNumberOfRepayments": 1, "maxNumberOfRepayments": 60,
  "repaymentEvery": 1, "repaymentFrequencyType": 2,
  "interestRatePerPeriod": 1, "interestRateFrequencyType": 2,
  "amortizationType": 1, "interestType": 1, "interestCalculationPeriodType": 1,
  "transactionProcessingStrategyCode": "mifos-standard-strategy", "loanScheduleType": "CUMULATIVE",
  "daysInYearType": 1, "daysInMonthType": 1, "isInterestRecalculationEnabled": false,
  "accountingRule": 1, "locale": "en"}'
```

`USD` must be an enabled currency of the tenant (`GET /currencies`; it is in a default setup).

## 2. Run Ollama

Install [Ollama](https://ollama.com/download) and pull the two models – a text model for
the underwriting decision and a vision model for CURP documents:

```bash
ollama pull llama3.2:3b
ollama pull qwen2.5vl:3b
```

3B models fit a 4 GB GPU; a decision takes roughly 10–20 s and a CURP page roughly 1–2
minutes. Larger models (e.g. those used by `agentic-banking-temporal`) only need different
`OLLAMA_MODEL` / `OLLAMA_VISION_MODEL` values in step 6.

On **Windows**, install Ollama natively (not in WSL): it uses the GPU directly and stays
reachable from Docker containers at the Docker host address (step 6).

## 3. Deploy OpenFn Lightning

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

`demo.exs` resets the database and creates the superuser
**`super@openfn.org` / `welcome12345`**. Check it is up:

```bash
docker compose ps                    # web should be "healthy"
curl localhost:4000/health_check
```

Open **http://localhost:4000** and log in.

- In dev mode Lightning starts its own worker inside the `web` container. If the separate
  `worker` service keeps restarting (e.g. `ERR_PNPM_CMD_SHIM_CHMOD`), stop it – runs still
  execute: `docker compose stop worker`.
- If the UI loads without styling, the asset build is still running or failed; build it
  manually: `docker compose exec web mix tailwind default && docker compose exec web mix esbuild default`.

## 4. Create the Fineract credential in Lightning

The *Create + Approve Loan in Fineract* job reads its connection details from a Lightning
**credential**, so no secrets live in `project.yaml`. The deploy in step 5 links it to the
job by name, so it must exist **before** deploying.

In Lightning: **Credentials → New credential → Raw JSON**, name it **`fineract`**:

```json
{
  "baseUrl": "https://host.docker.internal:8443/fineract-provider/api/v1",
  "username": "mifos",
  "password": "password",
  "tenantId": "default",
  "productId": 1,
  "officeId": 1,
  "tls": { "rejectUnauthorized": false }
}
```

- `baseUrl` must be reachable **from the Lightning container**; `host.docker.internal:8443`
  reaches a Fineract published on the Docker host. For a remote Fineract, use its URL and
  drop `tls`.
- `productId`: the loan product from step 1. `officeId`: the office for new clients.
- `tls.rejectUnauthorized: false` is only for a local Fineract with a self-signed certificate.
- `openfn/project.yaml` references the credential as owned by `super@openfn.org`
  (`credentials:` block). If you created it as another user, change `owner`, the key and
  the job's `credential:` value accordingly.
- Create it in the UI: Lightning's `POST /api/credentials` ignores a top-level `body` (it
  expects `credential_bodies: [{"name": "main", "body": {...}}]`), which leaves the
  credential empty.

## 5. Deploy the OpenFn project

Create an API token in Lightning at **http://localhost:4000/profile/tokens**, then from
this folder:

```bash
cd agentic-banking-openfn/openfn

export OPENFN_ENDPOINT=http://localhost:4000
export OPENFN_API_KEY=<your-token>

npx @openfn/cli deploy -p project.yaml
```

Confirm the diff with `y`. The CLI writes `.state.json` next to `project.yaml` with the ids
of this Lightning instance (gitignored). **Keep this file:** re-running the same command
with it updates the existing project; without it, a second project with the same name is
created, with different webhook URLs.

Print the two webhook URLs (or copy them from the trigger node of each workflow in the UI):

```bash
python3 -c "import json;s=json.load(open('.state.json'));[print(w['name'],'->','http://localhost:4000/i/'+t['id']) for w in s['workflows'].values() for t in w['triggers'].values()]"
```

## 6. Build and run the gateway

```bash
cd agentic-banking-openfn
mvn clean package

export OPENFN_WEBHOOK_LOAN_SUBMIT=http://localhost:4000/i/<loan-submit-trigger-id>   # step 5
export OPENFN_WEBHOOK_LOAN_REVIEW=http://localhost:4000/i/<loan-review-trigger-id>   # step 5
export OPENFN_GATEWAY_BASE_URL=http://host.docker.internal:8080   # see below for WSL2
export OPENFN_OLLAMA_URL=http://host.docker.internal:11434        # see below for Windows
export OLLAMA_MODEL=llama3.2:3b
export OLLAMA_VISION_MODEL=qwen2.5vl:3b
export LOAN_LOCAL_FALLBACK=false   # surface OpenFn errors instead of underwriting locally

java -jar target/agentic-banking-openfn-1.0.0-SNAPSHOT.jar
```

The API listens on **http://localhost:8080**. OpenFn jobs run inside the Lightning
container, so `OPENFN_GATEWAY_BASE_URL` and `OPENFN_OLLAMA_URL` must be addresses **that
container** can reach – `host.docker.internal` works when the gateway and Ollama run
directly on a macOS/Windows host.

**WSL2 + Docker Desktop:** `host.docker.internal` points at the Windows host, not your WSL
distro, so callbacks to a gateway running in WSL fail with
`ECONNREFUSED 192.168.65.254:8080`. Use the WSL IP for the gateway, and the Docker host
address for an Ollama installed on Windows:

```bash
export OPENFN_GATEWAY_BASE_URL=http://$(hostname -I | awk '{print $1}'):8080
export OPENFN_OLLAMA_URL=http://192.168.65.254:11434
```

The WSL IP changes after a reboot, so set it again each session. To check that Lightning
can reach both, from the Lightning folder:

```bash
docker compose exec -T web curl -s http://192.168.65.254:11434/api/tags        # lists the models
docker compose exec -T web curl -s http://<wsl-ip>:8080/actuator/health       # {"status":"UP"}
```

If Ollama is not reachable, set the Windows environment variable `OLLAMA_HOST=0.0.0.0` and
restart Ollama.

## 7. Test the flow end to end

**Submit** an application. To exercise the CURP vision step, add a CURP constancia
(RENAPO PDF, PNG or JPEG; the file name must contain `curp`, the path is as seen by the
gateway) and use the name printed on it as `fullName`:

```bash
curl -s -X POST localhost:8080/api/loans/submit -H 'Content-Type: application/json' -d '{
  "applicantId": "APP-1001",
  "fullName": "Alice Example",
  "email": "alice@example.com",
  "requestedAmount": 20000,
  "termMonths": 12,
  "documentPaths": ["/path/to/curp_XXXX.pdf"]
}'
```

The response contains the `workflowId` (plus OpenFn's `openfnWorkOrderId` /
`openfnRunId`). **Track** it until `PENDING_HUMAN_REVIEW`, then read the AI summary –
the Ollama recommendation, the assessments and, under `context.documents`, the CURP
fields and validation messages:

```bash
WF=<workflowId>
curl -s localhost:8080/api/loans/$WF/status
curl -s localhost:8080/api/loans/$WF/summary
```

**Approve** (human in the loop) – this triggers the Fineract write-back – and read the
final result once the status is `COMPLETED`:

```bash
curl -s -X POST localhost:8080/api/loans/$WF/review \
  -H 'Content-Type: application/json' -d '{"action":"APPROVE","comments":"Checked by loan officer"}'
curl -s localhost:8080/api/loans/$WF/final
```

Expected status sequence: `OPENFN_TRIGGERED → GATHERING_DATA → PENDING_HUMAN_REVIEW →
WRITING_TO_FINERACT → COMPLETED`. Every job's run and logs are visible in Lightning under
the project's **History**.

**Verify in Fineract** – the loan's `externalId` is the workflow ID, the client's
`externalId` is the CURP clave (when the CURP was valid, otherwise `<workflowId>-client`),
and the loan has two notes: the underwriting decision and the vision analysis:

```bash
F='-sk -u mifos:password -H Fineract-Platform-TenantId:default'
FINERACT=https://localhost:8443/fineract-provider/api/v1
curl $F $FINERACT/loans/external-id/$WF                 # loan, status Approved
curl $F $FINERACT/clients/external-id/<curp-clave>      # client
curl $F $FINERACT/loans/<loanId>/notes                  # decision + vision notes
```

The same loan, client and notes are visible in the Mifos X web app (loan → *Notes*).

## Configuration reference

| Variable | Default | Purpose |
|---|---|---|
| `OPENFN_WEBHOOK_LOAN_SUBMIT` | `http://localhost:4000/i/loan-submit` | Loan Submit trigger URL |
| `OPENFN_WEBHOOK_LOAN_REVIEW` | `http://localhost:4000/i/loan-review` | Loan Review trigger URL |
| `OPENFN_GATEWAY_BASE_URL` | `http://host.docker.internal:8080` | Where OpenFn jobs call back to the gateway |
| `OPENFN_CALLBACK_SECRET` | `change-me-in-prod` | Shared secret sent by jobs as `X-OpenFn-Secret` |
| `OPENFN_BASE_URL` | `http://localhost:4000` | Lightning base URL (run status lookups) |
| `OPENFN_API_TOKEN` | – | Lightning API token (run status lookups) |
| `LOAN_LOCAL_FALLBACK` | `true` | Run underwriting in the gateway if OpenFn is unreachable |
| `OPENFN_OLLAMA_URL` | `http://host.docker.internal:11434` | Ollama used by the OpenFn decision job (as seen from Lightning) |
| `OLLAMA_MODEL` | `llama3.2:latest` | Ollama model for the decision job |
| `OLLAMA_VISION_MODEL` | `qwen2.5vl:3b` | Ollama vision model for CURP documents |
| `CURP_MAX_ISSUE_AGE_DAYS` | `30` | Maximum age of a CURP constancia's issue date |
| `DOCUMENT_RENDER_DPI` | `120` | DPI used to render PDF pages before vision analysis |

- While `OPENFN_CALLBACK_SECRET` is left at `change-me-in-prod`, the gateway accepts
  callbacks **without checking the secret**. Set a real value outside local development.
- With `LOAN_LOCAL_FALLBACK=true`, a broken OpenFn setup still returns a normal-looking
  result from the gateway's own underwriting. Set it to `false` when testing the OpenFn
  integration so failures surface.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Status stays `OPENFN_TRIGGERED`, job log shows `ECONNREFUSED` | Lightning cannot reach the gateway – set `OPENFN_GATEWAY_BASE_URL` (step 6). |
| Status stays `OPENFN_TRIGGERED`, job log shows `returned 401` | `OPENFN_CALLBACK_SECRET` differs between the gateway that submitted the loan and the one receiving callbacks. |
| Submit returns `500`, gateway log shows `OpenFn webhook trigger failed` | Wrong or stale webhook URL – re-read it from `.state.json` after each deploy to a new instance. |
| `openfn deploy` fails with `source_job_id or source_trigger_id must be present` | Outdated `project.yaml` – pull the latest version of this folder. |
| Runs never start | No worker connected – check `docker compose logs web` for the built-in worker. |
| Deploy fails with an error about the `fineract` credential | Create the credential (step 4) before deploying, owned by the user named in `project.yaml`. |
| Write-back run fails with `Credential environment mismatch ... 'unknown'` | The credential has no body for the `main` environment – recreate it in the UI (step 4). |
| `final` shows `FINERACT_ERROR` / status `FAILED` | Fineract rejected a request; the error body is in `fineractLoan.details`. Fix the cause (e.g. missing loan product) and `POST /review` again – the job reuses the client/loan already created. |
| Deploy created a second project with the same name | `.state.json` was missing or stale. Delete the extra project in Lightning (*Settings*) and redeploy with the original `.state.json`, or use the new project's webhook URLs. |
| Decision note says `rules-fallback` / CURP says `Vision model unavailable` | Lightning cannot reach Ollama or the model is not pulled – check `OPENFN_OLLAMA_URL` (step 6) and `ollama list`. |
| CURP `valid: false` with `Issue date FAIL` | The constancia is older than `CURP_MAX_ISSUE_AGE_DAYS` (30) – use a recent one or raise the limit. |
| Docker containers stop unexpectedly on Windows | Check free space on `C:` – Docker Desktop and WSL disks live there and stop when it is full. |

## Tests

```bash
mvn test
```

## License

See the repository [LICENSE](../LICENSE).
