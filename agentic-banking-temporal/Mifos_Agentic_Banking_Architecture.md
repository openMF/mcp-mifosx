# Mifos Agentic Banking Architecture

This architecture integrates the existing Mifos/Fineract stack with agentic AI capabilities, drawing from:

- **Apache Fineract** - core banking engine (CQRS, multi-tenant, REST API)
- **openMF/web-app** - Angular SPA back-office UI
- **openMF/mcp-mifosx** - Model Context Protocol servers (Go/Java/Python/Rust) that expose Fineract as typed tools
- **agentic-banking-temporal** - durable loan-origination workflows (Spring Boot + Temporal + Ollama + Fineract write-back)
- **Prime Agent architecture patterns** - supervisor/worker separation, session runtime, tool calling, durable state

**References**
- https://github.com/openMF/web-app
- https://github.com/apache/fineract
- https://github.com/openMF/mcp-mifosx
- https://github.com/openMF/mcp-mifosx/tree/main/agentic-banking-temporal
- https://github.com/PrimeIntellect-ai/prime-agent/blob/main/packages/coding-agent/docs/architecture.md

---

## 1. High-Level Logical Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                         EXPERIENCE & CHANNEL LAYER                          │
│  Mifos Web App (Angular)  │  Self-Service Apps  │  Chat / WhatsApp / API    │
│  + Mifos Copilot UI       │  + Mobile           │  + External Agents        │
└───────────────┬───────────────────────┬─────────────────────┬───────────────┘
                │                       │                     │
                ▼                       ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    AGENT ORCHESTRATION & CONTROL PLANE                      │
│  Temporal / Flowable Workflows   │  Agent Supervisor / Runtime              │
│  (durable multi-step processes)  │  (session, memory, tool routing)         │
│  Human-in-the-loop signals       │  Guardrails + Policy Registry            │
└───────────────┬───────────────────────┬─────────────────────┬───────────────┘
                │                       │                     │
                ▼                       ▼                     ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         TOOL & INTEGRATION LAYER                            │
│              MCP Servers (mcp-mifosx)  –  Go / Java / Python / Rust         │
│  Clients · Loans · Savings · Groups · Accounting · Bulk · Documents         │
│  + External tools (Credit Bureaus, OCR, KYC, Notifications, RAG stores)     │
└───────────────┬─────────────────────────────────────────────────────────────┘
                │  REST (Fineract API) + MCP Protocol (stdio / SSE)
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                    CORE BANKING PLATFORM (Apache Fineract)                  │
│  CQRS Command/Query  │  Multi-tenant  │  Loan / Savings / Accounting        │
│  Portfolio  │  COB  │  Reports  │  Security (OAuth2 / Basic / 2FA)          │
└───────────────┬─────────────────────────────────────────────────────────────┘
                │
                ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│                         DATA & INFRASTRUCTURE                               │
│  MariaDB / PostgreSQL  │  Vector Store (RAG)  │  Object Storage (docs)      │
│  Temporal Persistence  │  Observability (Prometheus / OpenTelemetry)        │
│  LLM Runtime (Ollama / cloud providers)                                     │
└─────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Component Breakdown

### A. Experience Layer

| Component | Role | Source |
|-----------|------|--------|
| **Mifos X Web App** | Back-office SPA for staff (clients, loans, savings, reports) | openMF/web-app (Angular) |
| **Mifos Copilot** | In-app AI assistant that talks to MCP tools | Built into web-app |
| **Self-Service / Channels** | Customer-facing chat, WhatsApp, mobile, or third-party agents | MCP self-service + custom clients |
| **External AI Clients** | Claude, Cursor, n8n, custom agents that speak MCP | Any MCP-compatible client |

### B. Agent Orchestration & Control Plane

| Component | Role | Technology |
|-----------|------|------------|
| **Durable Workflow Engine** | Multi-step, long-running, recoverable processes (loan origination, collections, onboarding) | Temporal (primary) or Flowable |
| **Supervisor / Session Runtime** | Owns agent lifecycle, tool routing, memory, compaction, child agents | Inspired by Prime Agent (supervisor + worker + AgentSessionRuntime) |
| **Human-in-the-Loop** | Signals & queries for approval, review, escalation | Temporal signals + Web App UI |
| **Guardrails / Policy Registry** | Hard limits (amounts, systems, data), allow-lists, circuit breakers | Config + policy-as-code |
| **LLM Runtime** | Local (Ollama) or cloud model serving with structured JSON contracts | Ollama / provider APIs |

### C. Tool Layer (MCP)

The **mcp-mifosx** servers act as a stateless, typed bridge:

```
Any Agent / LLM Client
        │  MCP (stdio or SSE)
        ▼
┌─────────────────────────────────┐
│  mcp-mifosx (Go / Java /        │
│  Python / Rust implementations) │
│  38–102 typed tools             │
└──────────────┬──────────────────┘
               │  Fineract REST API
               ▼
        Apache Fineract
```

**Key capabilities exposed as tools:**

- Clients (create, activate, search, update, close)
- Loans (apply, approve, disburse, repay, products)
- Savings, groups, centers, charges, accounting
- Bulk operations (Rust/Go strength)
- Documents & reports

**Implementations available:**

| Language | Tools | Strengths |
|----------|-------|-----------|
| **Go** | ~102 | High-performance, cloud-native, SSE/Stdio |
| **Rust** | ~89 | Async I/O, exclusive bulk operations |
| **Python (FastMCP)** | ~49 | Modular domain-driven design |
| **Java (Quarkus)** | ~38 | Backoffice + Recommendations |

### D. Core Banking - Apache Fineract

- Multi-tenant, CQRS-based financial services engine
- Modules: Loan, Savings, Accounting, Portfolio, COB, Reports, Security
- Single source of truth for balances, schedules, and audit trail
- All agent actions that change state must ultimately call Fineract (via MCP or direct activities)

### E. Reference Agentic Flow (Loan Origination)

From `agentic-banking-temporal`:

```
Client → Spring Boot REST → Temporal WorkflowClient
                                  │
                                  ▼
                        SupervisorWorkflow (durable)
                                  │
          ┌─────────────┬─────────┼─────────────┐
          ▼             ▼         ▼             ▼
   LoanActivities  OllamaAgent  FineractActivities  (parallel)
   (bank/credit/   (local LLM   (create client +
    document       decision)     submit + approve
    assessments)                  loan)
```

**Features:**

1. **Strict JSON agent contract** - Ollama is prompted to return only a machine-parseable decision object; parsing failures degrade gracefully to REFER.
2. **Temporal-orchestrated provider fallback** - Credit bureau providers with independent retry policies.
3. **Parallel fan-out** - Bank fetch, document processing and specialist assessments run concurrently.
4. **Durable human review** - Workflow pauses indefinitely until a signal; status and summary are queryable at any time.
5. **Fineract write-back** - On human APPROVE the workflow creates a real client and loan in Fineract and approves it.
6. **Heuristic fallback** - If Ollama is down the workflow still completes with a safe REFER decision.
7. **Observability** - Actuator + Prometheus ready for on-prem monitoring.

---

## 3. Cross-Cutting Concerns

| Concern | Approach |
|---------|----------|
| **Identity & Auth** | Fineract OAuth2 / Basic / 2FA; agent identity mapped to service accounts with least privilege |
| **Audit & Observability** | Temporal history + Fineract command audit + MCP call logs + Prometheus/OpenTelemetry |
| **Data Privacy** | Stateless MCP servers (no PII storage); vector store with tenant isolation; LFPDPPP / local regulation alignment |
| **Safety** | Tool allow-lists, amount thresholds, human-in-the-loop for high-impact decisions, circuit breakers |
| **Deployment** | On-prem (Docker Compose: Temporal + Ollama + Fineract) or hybrid cloud; MCP servers as sidecars or separate services |
| **Extensibility** | New specialist activities/agents (fraud, KYC, collections) registered as Temporal activities or MCP tools |

---

## 4. Recommended Layered Target State

Aligned with agentic banking reference architectures (five horizontal layers + vertical concerns):

1. **Infrastructure** - Compute, storage, networking, identity, LLM serving (Ollama or managed)
2. **Common Services** - Agent control plane, guardrails, prompt/policy registry, authz, logging, model routing
3. **Data** - Fineract ledger (golden source) + vector store for RAG + document store
4. **Business Logic in Agents** - Loan origination, collections, CX, PLD monitoring as durable agentic workflows
5. **Customer / Staff Agents** - Conversational interfaces that call MCP tools and Temporal workflows

This mirrors the agentic banking reference architecture (horizontal layers + vertical development/observability agents) while remaining grounded in the concrete open-source components already available in the Mifos ecosystem.

---

## 5. Implementation Roadmap (Practical)

| Phase | Focus | Key Deliverables |
|-------|-------|------------------|
| **1 – Foundation** | MCP + Fineract connectivity | Deploy mcp-mifosx (Go or Java), connect Web App Copilot |
| **2 – First Durable Process** | Loan origination | Run `agentic-banking-temporal`, human review, Fineract write-back |
| **3 – Control Plane** | Guardrails & observability | Policy registry, audit dashboards, circuit breakers |
| **4 – Expand Domains** | Collections, CX, onboarding, PLD | Additional Temporal workflows + specialist agents |
| **5 – Multi-Agent** | Supervisor + child agents | Goal-oriented agents that spawn sub-agents (Prime-style runtime) |

---

## 6. Design Principles

1. **Fineract as the immutable core ledger** - All state-changing operations must ultimately be recorded in Apache Fineract.
2. **MCP as the standardized tool interface** - Agents never talk to Fineract REST directly when possible; they use typed MCP tools for consistency, safety, and discoverability.
3. **Temporal for durable agentic workflows** - Long-running, recoverable, human-in-the-loop processes (loan origination, collections, compliance).
4. **Prime Agent-style separation of concerns** - Client (UI) / Supervisor (routing, health) / Worker (session runtime) / AgentSession (tools, memory, providers).
5. **Stateless tool tier** - MCP servers hold no PII or session state; they are pure integration bridges.
6. **Human-in-the-loop by design** - High-impact decisions (credit approval, large disbursements, compliance escalations) pause for human signal.
7. **On-prem first, hybrid ready** - Local Ollama + Temporal + Fineract for sovereignty; cloud LLM providers as optional upgrade path.

---

## 7. Example Use Cases Enabled

| Domain | Agentic Capability |
|--------|--------------------|
| **Enrollment / Onboarding** | OCR + KYC + Fineract client creation via MCP tools |
| **Origination** | Parallel credit assessment → LLM decision → human review → Fineract loan create/approve |
| **AML/CTF** | Continuous monitoring agents + alert generation + case summarization |
| **Collections** | Segmented outreach agents + negotiation within policy + escalation |
| **CX / Support** | 24/7 conversational agents with MCP tools for balance, transactions, product info |
| **Analytics** | Narrative report generation, regulatory report assistance, scenario simulation |

---


