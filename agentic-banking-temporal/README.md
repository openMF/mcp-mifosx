# Agentic Loan Origination – Spring Boot 4 / Temporal / Ollama / Apache Fineract

Full on-premise conversion of the [temporal-sa/agentic-loan-origination](https://github.com/temporal-sa/agentic-loan-origination) Python blueprint.

## What was converted & improved

| Original (Python)              | This project (Java)                                      |
|--------------------------------|----------------------------------------------------------|
| FastAPI + Streamlit            | Spring Boot 4 REST API                                   |
| Temporal Python SDK            | Temporal Java SDK + `temporal-spring-boot-starter`       |
| Strands + Ollama / Bedrock     | Local **Ollama only** (ollama4j) with structured JSON    |
| AWS Bedrock Nova Pro OCR       | Local document stub (plug-in Tesseract / local vision)   |
| Mockoon credit / bank APIs     | Mock + optional live HTTP; Temporal-orchestrated fallback|
| Human-in-the-loop signals      | Same (signals + queries)                                 |
| No core-banking write-back     | **Apache Fineract** client – create client + submit + approve loan |
| Cloud-centric                  | Fully on-premise (Docker Compose for Temporal + Ollama)  |

### Agentic improvements for on-premise

1. **Strict JSON agent contract** – Ollama is prompted to return only a machine-parseable decision object; parsing failures degrade gracefully to `REFER`.
2. **Temporal-orchestrated provider fallback** – CIBIL → Experian with independent retry policies.
3. **Parallel fan-out** – bank fetch, document processing and the three specialist assessments run concurrently via `Async.function` / `Promise`.
4. **Durable human review** – workflow pauses indefinitely until a signal; status and summary are queryable at any time.
5. **Fineract write-back** – on human APPROVE the workflow creates a real client and loan in Fineract (sandbox or your local instance) and approves it.
6. **Heuristic fallback** – if Ollama is down the workflow still completes with a safe `REFER` decision so the process never dies.
7. **Actuator + Prometheus** ready for on-prem observability.

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for Temporal + Ollama)
- (Optional) Local Apache Fineract or use the public sandbox

## Quick start (on-premise)

```bash
# 1. Infrastructure
cd docker
docker compose up -d

# 2. Pull an Ollama model (first time)
docker exec -it $(docker ps -qf name=ollama) ollama pull llama3.2

# 3. Build & run the application
cd ..
mvn clean spring-boot:run
```

The API listens on **http://localhost:8080**.  
Temporal UI: **http://localhost:8088**.

### Environment overrides

| Variable              | Default                                              | Purpose                          |
|-----------------------|------------------------------------------------------|----------------------------------|
| `TEMPORAL_ADDRESS`    | `localhost:7233`                                     | Temporal front-end               |
| `OLLAMA_URL`          | `http://localhost:11434`                             | Local Ollama                     |
| `OLLAMA_MODEL`        | `llama3.2:latest`                                    | Model name                       |
| `FINERACT_BASE_URL`   | `https://sandbox.mifos.community/fineract-provider/api/v1` | Fineract API            |
| `FINERACT_USER` / `PASSWORD` | `mifos` / `password`                          | Sandbox credentials              |

## API usage

```bash
# Submit a loan
curl -X POST http://localhost:8080/api/loans/submit \
  -H 'Content-Type: application/json' \
  -d '{
    "applicantId": "APP-1001",
    "fullName": "Alice Example",
    "email": "alice@example.com",
    "requestedAmount": 12000,
    "termMonths": 24,
    "purpose": "HOME_IMPROVEMENT"
  }'

WF_ID=$(curl -s -X POST http://localhost:8080/api/loans/submit \
  -H 'Content-Type: application/json' \
  -d '{"applicantId":"APP-1002","fullName":"Alice Example","email":"alice@example.com","requestedAmount":12000,"termMonths":24}' \
  | jq -r .workflowId)

echo "WF=$WF_ID"

# wait until PENDING_HUMAN_REVIEW
for i in $(seq 1 40); do
  S=$(curl -s http://localhost:8080/api/loans/$WF_ID/status | jq -r .status)
  echo "status=$S"
  [ "$S" = "PENDING_HUMAN_REVIEW" ] && break
  sleep 2
done

curl -s -X POST http://localhost:8080/api/loans/$WF_ID/review \
  -H 'Content-Type: application/json' \
  -d '{"action":"APPROVE","comments":"OK"}' | jq .

sleep 3
curl -s http://localhost:8080/api/loans/$WF_ID/final | jq .
  
# → { "workflowId": "loan-APP-1001-xxxxxxxx", "status": "STARTED", ... }

# Poll status / summary
curl http://localhost:8080/api/loans/loan-APP-1001-xxxxxxxx/status
curl http://localhost:8080/api/loans/loan-APP-1001-xxxxxxxx/summary

# Human review (approve → creates loan in Fineract)
curl -X POST http://localhost:8080/api/loans/loan-APP-1001-xxxxxxxx/review \
  -H 'Content-Type: application/json' \
  -d '{"action":"APPROVE","comments":"Looks solid"}'

# Final result (includes Fineract loanId)
curl http://localhost:8080/api/loans/loan-APP-1001-xxxxxxxx/final
```

## Architecture (high level)

```
Client ──► Spring Boot REST ──► Temporal WorkflowClient
                                      │
                                      ▼
                            SupervisorWorkflow (durable)
                                      │
          ┌─────────────┬─────────────┼─────────────┐
          ▼             ▼             ▼             ▼
   LoanActivities  OllamaAgent  FineractActivities  (parallel)
   (bank/credit/   (local LLM   (create + approve
    assessments)    decision)     loan in core)
```

## Tests

```bash
mvn test
```

`SupervisorWorkflowTest` uses Temporal’s `TestWorkflowEnvironment` with mocked activities so the full happy-path and credit-fallback scenarios run offline.

## Fineract notes

- Default product/office IDs are configurable (`loan.fineract.default-product-id`, `default-office-id`).
- Against the public sandbox the client always creates a new Fineract client then submits + approves a loan.
- For a private Fineract instance point `FINERACT_BASE_URL` at your `/fineract-provider/api/v1` and adjust credentials / tenant.

## Extending the agentic layer

- Replace the document stub in `LoanActivitiesImpl.processDocument` with a local OCR pipeline (Tesseract + an Ollama vision model).
- Add more specialist activities (fraud, employment verification) and fan them out the same way.
- Introduce tool-calling style agents by giving Ollama a list of allowed function names and parsing the chosen tool from the JSON response.

## License

Same MIT spirit as the original Temporal SA blueprint. Use at your own risk – this is a reference implementation, not production underwriting software.
