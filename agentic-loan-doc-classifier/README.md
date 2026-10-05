# Loan Document Classifier

Spring Boot **4.1** application that uses Cloudflare’s **[clef-flash](https://ollama.com/library/clef-flash)** decision model (via Ollama’s System One API) to decide whether a document submitted in a loan application is a **bank account statement**.

Clef-Flash is a 9B multimodal *decision model* — not a chat model. You send a `state` plus typed questions; it returns structured answers with probabilities in a single forward pass. Images (PNG, JPEG, WebP) can be attached and are scored jointly with the text.

---

## Features

| Feature | Description |
|---------|-------------|
| Text evaluation | JSON API for OCR-extracted or plain text |
| Binary image upload | Multipart upload of PNG / JPEG / WebP scans |
| Multi-page support | Several images in one request (`images` array) |
| Magic-byte detection | Correctly identifies images even when content-type is wrong |
| Typed decisions | Noul (yes/no probability) + Choice (document type) |
| Configurable threshold | Tune when a document counts as a bank statement |
| Unit & controller tests | Run without a live Ollama instance |

---

## Prerequisites

1. **Java 21+**
2. **Maven 3.9+**
3. **Ollama ≥ 0.35.1**

```bash
# Install Ollama from https://ollama.com , then:
ollama pull clef-flash    # ~11–12 GB – recommended (fast)
# ollama pull clef        # ~18 GB – higher accuracy alternative
```

---

## Quick Start

```bash
# 1. Start Ollama (if not already running)
ollama serve

# 2. Build & run
cd loan-doc-classifier
mvn spring-boot:run
```

The service listens on **http://localhost:8080**.

---

## API

### 1. Evaluate text (JSON)

```http
POST /api/v1/documents/evaluate
Content-Type: application/json
```

```bash
curl -s -X POST http://localhost:8080/api/v1/documents/evaluate \
  -H "Content-Type: application/json" \
  -d '{
    "documentText": "FIRST NATIONAL BANK\nAccount Statement\nAccount Holder: Jane Doe\nAccount Number: ****1234\nStatement Period: 01 Jan 2026 – 31 Jan 2026\nOpening Balance: $2,450.00\nClosing Balance: $3,120.75",
    "filename": "jan_2026_statement.txt",
    "applicantId": "APP-98765"
  }' | jq
```

**Request body**

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `documentText` | string | Yes | Full text of the document (or OCR output) |
| `filename` | string | No | Original filename (included in decision state) |
| `applicantId` | string | No | Loan / applicant identifier |

---

### 2. Evaluate binary file(s) (multipart)

```http
POST /api/v1/documents/evaluate-file
Content-Type: multipart/form-data
```

```bash
# Single image
curl -s -X POST http://localhost:8080/api/v1/documents/evaluate-file \
  -F "file=@statement.webp" \
  -F "applicantId=APP-98765" | jq

# Multiple pages (all sent in one System One call)
curl -s -X POST http://localhost:8080/api/v1/documents/evaluate-file \
  -F "file=@page1.png" \
  -F "file=@page2.jpg" \
  -F "applicantId=APP-98765" | jq

# Plain text file
curl -s -X POST http://localhost:8080/api/v1/documents/evaluate-file \
  -F "file=@statement.txt"
```

**Accepted file types** (per [clef-flash](https://ollama.com/library/clef-flash) / [System One API](https://docs.ollama.com/api/systemone))

| Type | Behaviour |
|------|-----------|
| `image/png`, `image/jpeg`, `image/webp` | Base64-encoded → `images` array (vision) |
| `text/*`, `.txt`, `.csv`, `.md` | UTF-8 decoded → text path |
| PDF, GIF, Office, other | **Rejected** — convert pages to PNG/JPEG/WebP or extract text first |

Images are validated with **magic bytes**, so a correctly encoded PNG uploaded as `application/octet-stream` still works.

---

### Response

```json
{
  "isBankAccountStatement": true,
  "probability": 0.9412,
  "decisionLabel": "bank_account_statement",
  "model": "clef-flash",
  "explanation": "Model 'clef-flash' assigned probability 0.9412 that the document is a bank account statement (threshold=0.75). Secondary classification: 'bank_account_statement' (confidence=0.9123).",
  "sourceFilename": "statement_scan.png",
  "sourceContentType": "image/png"
}
```

| Field | Description |
|-------|-------------|
| `isBankAccountStatement` | `true` when `probability >= decision-threshold` |
| `probability` | Noul score from the model (0–1) |
| `decisionLabel` | Choice label (`bank_account_statement`, `payslip`, `identity_document`, `utility_bill`, `tax_document`, `other`) |
| `model` | Model name that answered |
| `explanation` | Human-readable summary |
| `sourceFilename` | Original upload name(s); `null` for pure JSON text calls |
| `sourceContentType` | Detected/declared content type of the upload |

---

## Configuration

`src/main/resources/application.yml`:

```yaml
spring:
  ai:
    typesafe:
      base-url: http://localhost:11434
      api-key: ollama              # any non-empty value; Ollama ignores it
      model: clef-flash            # or "clef" for higher accuracy
      timeout: 90s
  servlet:
    multipart:
      max-file-size: 15MB
      max-request-size: 32MB       # System One image body limit

app:
  classifier:
    decision-threshold: 0.75       # noul >= this → isBankAccountStatement=true
```

| Property | Default | Description |
|----------|---------|-------------|
| `spring.ai.typesafe.model` | `clef-flash` | Decision model name |
| `spring.ai.typesafe.base-url` | `http://localhost:11434` | Ollama host |
| `app.classifier.decision-threshold` | `0.75` | Positive decision cutoff |
| `spring.servlet.multipart.max-file-size` | `15MB` | Per-file limit |
| `spring.servlet.multipart.max-request-size` | `32MB` | Total request limit |

---

## Project Structure

```
loan-doc-classifier/
├── pom.xml
├── README.md
└── src
    ├── main
    │   ├── java/org/community/mifos/ai/decision
    │   │   ├── LoanDocClassifierApplication.java
    │   │   ├── client/
    │   │   │   └── OllamaSystemOneClient.java      # RestClient + images[]
    │   │   ├── controller/
    │   │   │   └── DocumentClassifierController.java
    │   │   ├── dto/
    │   │   │   ├── DocumentEvaluationRequest.java
    │   │   │   └── DocumentEvaluationResponse.java
    │   │   ├── service/
    │   │   │   └── BankStatementClassifierService.java
    │   │   └── util/
    │   │       └── ImageFormatDetector.java        # PNG/JPEG/WebP magic bytes
    │   └── resources/
    │       └── application.yml
    └── test
        ├── java/org/community/mifos/ai/decision
        │   ├── controller/DocumentClassifierControllerTest.java
        │   ├── service/BankStatementClassifierServiceTest.java
        │   └── integration/BankStatementClassifierLiveIT.java
        └── resources/
            └── application-test.yml
```

---

## How the Decision Works

1. **State** – Document text (and optional metadata) is sent as structured JSON `state`.
2. **Questions** (single System One call):
   - **Noul** `is_bank_account_statement` – probability the document is a bank statement.
   - **Choice** `document_type` – multi-class label among bank statement, payslip, ID, utility bill, tax document, other.
3. **Images** (optional) – Base64 PNG/JPEG/WebP in the `images` array; scored jointly with the state by clef-flash’s vision encoder.
4. **Decision** – `isBankAccountStatement = (noul >= app.classifier.decision-threshold)`.

**Text path** uses the Spring AI TypeSafe client.  
**Image path** uses `OllamaSystemOneClient` (RestClient) because the TypeSafe SDK 0.4.0 does not yet expose the `images` field.

---

## Running the Tests

```bash
# Unit + controller tests (mocked – no Ollama required)
mvn test

# Live integration tests against a running Ollama + clef-flash
export RUN_LIVE_OLLAMA=true
mvn test -Dtest=BankStatementClassifierLiveIT
```

---

## Stack

| Component | Version / note |
|-----------|----------------|
| Spring Boot | 4.1.1 |
| Java | 21 |
| Spring AI | 2.0.1 |
| Spring AI TypeSafe | 0.4.0 (System One / Jev-compatible client) |
| Decision model | [clef-flash](https://ollama.com/library/clef-flash) (default) or [clef](https://ollama.com/library/clef) |
| Ollama | ≥ 0.35.1 |

---

## Licence

Apache-2.0 (same as Spring Boot, Spring AI, and Clef / Clef-Flash).
