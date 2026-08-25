# Mifos Copilot Gateway

**In one line:** this is the "brain" that lets a loan officer *talk* to Mifos X — type
"approve Ravi's loan at ₹20,000" instead of clicking through six screens — while keeping
the bank fully in control.

---

## For everyone: what is this, really?

Mifos X already has all the banking features. The problem is *reaching* them — approving one
loan means clicking through several screens, and new staff need weeks of training.

The **Copilot** adds a chat panel to Mifos X so an officer can just say what they need. But a
chat panel can't talk to an AI on its own — something has to sit in the middle and do the real
work. **That "something" is this Gateway.**

Think of it as a **trusted branch assistant** who:

1. **Listens** to what the officer types.
2. **Asks the AI** ("Groq" or a private on-site AI) *which* banking task the officer means.
3. **Does the task in Mifos X** — but under the officer's *own* login, so the bank's existing
   rules about who-can-do-what still apply, and the records show who really did it.
4. **Stops and asks "Are you sure?"** before anything that touches money.

```
  Officer types                          The Gateway                      Mifos X / Fineract
  in Mifos X          ──────────▶     (this project)      ──────────▶     (the real banking system)
  "approve Ravi's                    · understands the ask                 · checks the officer's
   loan at 20,000"                   · asks the AI which task                permissions
                     ◀──────────      · shows a confirm card   ◀──────────  · records who did it
  sees a clean                       · runs it once confirmed
  confirmation
```

### The promises this design keeps

| Promise | What it means for the bank |
|---|---|
| 🔑 **The AI key never reaches anyone's browser** | The secret used to talk to the AI lives only on the bank's server. Staff never see it. |
| 👤 **Every action is done "as" the real officer** | Mifos X's existing permissions decide what's allowed, and the audit log names the actual person — not a robot account. |
| ✋ **Money never moves without a human "Confirm"** | The AI can *suggest* approving or disbursing a loan, but it pauses and waits for the officer to click Confirm. |
| 🔒 **The AI can only touch a fixed, reviewed list of tasks** | It literally cannot do anything that isn't on an approved list — "delete everything" is impossible, not just discouraged. |
| 🏠 **Works with a private, in-house AI too** | Banks that can't send data to the cloud can run the AI entirely on their own servers. |
| 🛡️ **It refuses to work against the wrong bank** | If it's ever pointed at a different Mifos server than the one on screen, it stops instead of quietly acting on the wrong data. |

---

## For developers

### Where it fits

```text
web-app (Angular Copilot panel)
   │  POST /copilot/api/v1/chat        (wire contract v1, SSE)
   │  Authorization + Fineract-Platform-TenantId  (the officer's own credential, forwarded)
   ▼
Copilot Gateway  ── Spring Boot 3.5 shell around a framework-free `copilot-core` library
   │                                        │
   │ OpenAI-compatible chat+tools           │ Fineract REST, as the officer
   ▼                                        ▼
Groq (qwen/qwen3.6-27b)  /  Ollama      Apache Fineract  (RBAC · audit · Idempotency-Key dedup)
(cloud, or fully on-prem)
```

The agent loop lives in `org.mifos.community.copilot.core`, which has **zero framework imports**
(pure JDK + Jackson + SnakeYAML, enforced by a test). That is deliberate: the mentor's Fineract
plugin can embed the same core later without dragging this Spring shell along — the web-app won't
change a line when that happens.

### Design guarantees, precisely

| Guarantee | How it's enforced |
|---|---|
| LLM key never in the browser | Key is gateway config (`COPILOT_LLM_API_KEY`); the browser only ever sends the officer's Fineract credential |
| Actions run as the real officer | The browser's `Authorization` + `Fineract-Platform-TenantId` are forwarded to every Fineract call — the gateway holds **no** service account. RBAC + audit apply natively |
| Writes never auto-execute | A write tool pauses the turn with an `action_card`; execution happens only via `POST /actions/{cardId}/decision`, from the **same user + tenant** (fingerprint-checked), single-use, expiring |
| Card == execution | Every argument shown on the confirmation card reaches the executed request; undeclared args are refused before a card is ever created |
| Exactly-once writes | The gateway mints the `Idempotency-Key` at card creation; Fineract's CommandSource dedups on it, so a retry can't double-execute |
| Honest status | "✔ Executed" only on a Fineract success; a rejected action says "✖ Not completed" — never the reverse |
| Default-deny tools | `src/main/resources/tools.yaml` is the reviewed allow-list; an unknown tool is refused whatever the model says |
| No runaway loops | ≤ 6 tool rounds per turn; cancellation honored between steps |
| Cloud **and** on-prem LLMs | Any OpenAI-compatible engine — Groq today, Ollama air-gapped tomorrow — pure configuration |
| Backend-drift guard | The web-app sends its backend origin; the gateway refuses to run if it differs from its own `FINERACT_BASE_URL` (fail-closed) |
| PII minimization | Per-tool `redactFields` mask configured fields (e.g. mobile, DOB) before results reach a cloud LLM |

### Run it

```bash
# Mock AI — no key needed; real Fineract tools still run under your login:
./mvnw spring-boot:run

# Groq (cloud):
COPILOT_LLM_PROVIDER=groq COPILOT_LLM_API_KEY=gsk_... ./mvnw spring-boot:run

# Ollama (fully on-prem, no data leaves the building):
COPILOT_LLM_PROVIDER=ollama COPILOT_LLM_MODEL=qwen3:8b ./mvnw spring-boot:run
```

Point the web-app at it: `copilotMcpBaseUrl: 'http://localhost:8090'`.

| Env var | Default | Meaning |
|---|---|---|
| `COPILOT_PORT` | `8090` | Listen port |
| `COPILOT_LLM_PROVIDER` | `mock` | `mock` \| `groq` \| `openai` \| `ollama`. Anything else OpenAI-compatible works too, by supplying `COPILOT_LLM_BASE_URL` |
| `COPILOT_LLM_API_KEY` | — | Provider key — **server-side only**. Not needed for `mock` |
| `COPILOT_LLM_MODEL` | `qwen/qwen3.6-27b` | Model id |
| `COPILOT_LLM_BASE_URL` | — | Only for an OpenAI-compatible engine the gateway does not know by name: Azure OpenAI, vLLM, OpenRouter, a private gateway. Leave unset for `groq`, `openai` and `ollama`, which resolve their own. Set, it overrides the provider's default |
| `FINERACT_BASE_URL` | `https://sandbox.mifos.community` | The Fineract the tools call. **Must be the same Fineract the web-app is configured against**, or the drift guard refuses to run |
| `FINERACT_API_PATH` | `/fineract-provider/api/v1` | Where that Fineract publishes its API, under the base URL. See below |
| `COPILOT_ALLOWED_ORIGINS` | `http://localhost:4200` | CORS allow-list (comma-separated) |
| `COPILOT_DATA_RESIDENCY` | `cloud` | Operator's explicit acknowledgement of where tool results flow |
| `COPILOT_APPROVAL_TTL_SECONDS` | `300` | How long a pending confirmation card stays approvable before it expires |

#### Pointing at a Fineract behind an API manager

`FINERACT_BASE_URL` and `FINERACT_API_PATH` are separate because they answer different
questions: *which* Fineract, and *where it publishes its API*. A bare Fineract answers on the
default path, so most deployments never set the second one. Behind an API manager it does not,
and the same server is republished somewhere else entirely.

The community sandbox is the example to hand. Both of these reach the same Fineract:

```bash
# a bare Fineract
FINERACT_BASE_URL=https://sandbox.mifos.community
FINERACT_API_PATH=/fineract-provider/api/v1      # the default, so it can be omitted

# the same server behind the community API manager
FINERACT_BASE_URL=https://apis.mifos.community
FINERACT_API_PATH=/1.0/core/api/v1
```

Getting this wrong looks like the gateway being broken rather than misconfigured: every tool
call returns 404 and the assistant reports that a client who exists cannot be found. If tools
fail while `/health` is happy, check this pair first.

Whatever the web-app is configured against has to match `FINERACT_BASE_URL`. The web-app sends
its own backend origin on every turn and the gateway refuses to run when the two differ, so a
Copilot can never write to a different bank than the one on the officer's screen.

### Endpoints (wire contract v1)

| Endpoint | Purpose |
|---|---|
| `POST /copilot/api/v1/chat` | A chat turn. SSE events: `token` · `tool_call` · `action_card` · `suggest` · `done` · `error` |
| `POST /copilot/api/v1/actions/{cardId}/decision` | `{decision: approve\|reject}` → SSE continuation of the paused turn |
| `GET /copilot/api/v1/health` · `/meta` | Feature-flag / diagnostics |

### Tools today (12)

Reads: client search · client details · client accounts · loan details · loan schedule · savings
details · loan-products list. Writes (each pauses for confirmation): create client · create loan
application · approve loan · disburse loan · record repayment.

Add more by editing `tools.yaml`. A straightforward REST-backed tool needs no code change.

### Writing a tool that an officer can actually read

The manifest is also where the confirmation card gets its wording, so a write tool needs a
little more than a REST mapping. Without it the officer is shown the model's own function
call, which is to say `loanId 12` and `28000`, and that is not something anyone can check.

```yaml
- name: mifos_loan_approve
  description: Approve a loan application that is awaiting approval.
  summary: "Approve {productName} for {clientName}"
  write: true
  params:
    - { name: loanId, type: integer, required: true, label: Loan account, show: false }
    - { name: approvedOnDate, type: string, required: true, label: Approval date, format: date }
    - { name: approvedLoanAmount, type: number, label: Approved amount, format: money }
  enrich:
    - path: /fineract-provider/api/v1/loans/{loanId}
      currency: currency.code
      fields:
        Client: clientName
        Loan account: accountNo
        Product: loanProductName
        Applied for: "#money:principal"
  rest:
    method: POST
    path: /fineract-provider/api/v1/loans/{loanId}?command=approve
    body: '{"approvedOnDate":"${approvedOnDate}", ...}'
```

| Field | What it does |
|---|---|
| `label` | What the officer reads on the card instead of the parameter name |
| `format` | `money` or `date`, so `28000` is shown as `USD 28,000.00` and `today` as `21 August 2026` |
| `show: false` | Hides an identifier. An account number and a product name mean something to a person; a database id does not |
| `enrich` | Reads performed with the officer's own credential before the card is shown, so it can name the account, the product and the client. A list, because approving a new loan means naming both the client and the product and those live behind different endpoints |
| `enrich[].currency` | Dotted path to the currency the record is held in, which is where the `USD` prefix comes from |
| `fields` | Card row label to a dotted path in the response. Prefix a path with `#money:` to format it as an amount |
| `summary` | The card's one-line title. `{clientName}` and `{productName}` are filled from the enrichment |

Enrichment is presentation only. A lookup that fails leaves the card thinner and never fails
the turn, and a summary whose names all went missing falls back to the tool's description.

The card also carries the raw arguments, unchanged, as the record of exactly what will
execute. The rows are for the human; the arguments are what runs.

Row labels reach the web app in English and are translated there through
`copilot.cardLabels.*`, so a label already in that list needs no further work. A label with no
entry is displayed as written here.

### Test

```bash
./mvnw test
```

Covers the invariants, not the plumbing: write-pause + single-use approval + fingerprint checks,
card/execution body fidelity, honest success/failure status, default-deny, round cap,
server-minted idempotency keys, backend-drift guard, and the framework-free-core rule.

### Known limitations (phase 1)

- **OAuth token rotation changes the approval fingerprint** — a card created under one Bearer token
  can't be decided after a refresh (fully works on Basic-auth deployments). Stable-identity
  fingerprinting is planned for the plugin phase.
- **In-memory, single-instance state** — pending approvals and conversation memory don't survive a
  restart and don't replicate; multi-replica needs an external store.
- **Redaction surface is minimal** — only client details mask `mobileNo`/`dateOfBirth` by default;
  review `redactFields` per deployment before `data-residency=cloud`.
- **Card expiry is server-enforced only** — the UI shows no countdown; an expired approval answers
  "no longer valid" and needs a fresh ask.
- **`"today"` resolves in the gateway server's timezone** — may differ from the officer's business
  date near midnight.
- **A stalled LLM stream can hold a worker thread** until TCP timeout (the 180s request deadline
  covers headers, not mid-stream stalls); an idle-watchdog is planned.
- **`provider=mock` is the default** — production must set a real provider; `/health` reports
  `configured:false` and startup logs a WARN when running on the scripted mock.

---

*Part of [mcp-mifosx](https://github.com/openMF/mcp-mifosx). The tool servers there expose Fineract
operations; this gateway is the client "brain" that drives them for the Mifos X web-app Copilot.
Architecture rationale: ADR-001.*
