# Agentic Loan Origination – Spring Boot 4 / Flowable OSS 8.0 / Ollama / Fineract

## Flowable version

Uses **Flowable Open Source 8.0.0** (latest OSS release, February 2026).

| Layer | Version / image |
|-------|-----------------|
| Maven | `org.flowable:flowable-spring-boot-starter-process:8.0.0` |
| Docker REST | `flowable/flowable-rest:8.0.0` |
| Database | Postgres 16 |

> **Note on [documentation.flowable.com …/docker-local](https://documentation.flowable.com/latest/admin/installs/docker-local)**  
> That page describes **Flowable Enterprise** images (`repo.flowable.com/docker/flowable/flowable-work`, Design, Control) which require Artifactory login and a license.  
> This project uses the **public OSS** stack from [Docker Hub](https://hub.docker.com/r/flowable/flowable-rest) and [flowable-engine/docker](https://github.com/flowable/flowable-engine/tree/main/docker), which needs no commercial credentials.

## Architecture

```
docker compose                    Spring Boot (:8080)
┌─────────────────────┐           ┌──────────────────────────┐
│ postgres :5432      │◀──────────│ Flowable 8.0 engine      │
│ flowable-rest :8081 │  shared   │ BPMN + JavaDelegates     │
│ ollama :11434       │   DB      │ REST API + Ollama/Fineract│
└─────────────────────┘           └──────────────────────────┘
```

## 1. Start infrastructure

```bash
cd docker
docker compose up -d
docker compose ps
```

Wait until `database` is healthy and `flowable-rest` is up.

| Service | URL | Credentials |
|---------|-----|-------------|
| Postgres | `localhost:5432` | flowable / flowable |
| Flowable REST | http://localhost:8081/flowable-rest/ | rest-admin / test |
| Flowable Swagger | http://localhost:8081/flowable-rest/docs/ | rest-admin / test |
| Ollama | http://localhost:11434 | — |

```bash
# First time: pull an LLM model
docker exec -it $(docker ps -qf name=ollama) ollama pull llama3.2
```

## 2. Start the application

```bash
mvn clean spring-boot:run
```

The app connects to the same Postgres and deploys `loan-origination.bpmn20.xml` automatically.

## 3. Curl test

```bash
WF=$(curl -s -X POST http://localhost:8080/api/loans/submit \
  -H 'Content-Type: application/json' \
  -d '{"applicantId":"APP-1001","fullName":"Alice Example","email":"alice@example.com","requestedAmount":12000,"termMonths":24}' \
  | jq -r .workflowId)
echo "processInstanceId=$WF"

for i in $(seq 1 30); do
  S=$(curl -s http://localhost:8080/api/loans/$WF/status | jq -r .status)
  echo "status=$S"
  [ "$S" = "PENDING_HUMAN_REVIEW" ] && break
  sleep 1
done

curl -s -X POST http://localhost:8080/api/loans/$WF/review \
  -H 'Content-Type: application/json' \
  -d '{"action":"APPROVE","comments":"OK"}' | jq .

curl -s http://localhost:8080/api/loans/$WF/final | jq .
```

## Optional: Enterprise stack

If you have Flowable Enterprise credentials, you can replace `docker/docker-compose.yml` with the compose file from the [official docker-local guide](https://documentation.flowable.com/latest/admin/installs/docker-local) (`flowable-work`, Design, Control + Elasticsearch) after:

```bash
docker login repo.flowable.com
```

Keep the same Postgres credentials (`flowable`/`flowable`) so the Spring Boot app can still share the database, or point `SPRING_DATASOURCE_*` at that instance.

## License

Reference implementation – not production underwriting software.
