# LOS: loan origination service

Keeps commercial opportunities (leads and applicants) from first contact until they are registered in Fineract or discarded. It has its own database and is linked to Fineract by external ID: only a qualified opportunity reaches Fineract.

Leads from channels arrive through the OpenFn intake workflow, which checks them (duplicates, a way to contact) and creates them here with its own token, as the actor `intake`. Once a person has moved an opportunity to `qualifying`, the OpenFn eligibility workflow checks it against the Fineract loan product and the applicant's history there, and moves it on to `documents` or pauses it with a reason code, as the actor `eligibility`. In `documents`, the OpenFn identity workflow checks the applicant's CURP constancia, uploaded here, with a vision model, records the result on the document, and moves the opportunity on to `assessing` or pauses it, as the actor `identity`. See the sandbox README, section 3.3.

The LOS has no access to Fineract. When an opportunity qualifies, the write-back agent creates the client and the loan application with its own Fineract role, then records their ids here (`POST /opportunities/{id}/registration`). The opportunity's `external_id` becomes the loan's external ID in Fineract.

Run it with the sandbox (`docker compose up -d`); the API and its docs are at http://localhost:8090/docs.

## Stages

```
new · contacting · qualifying · documents · assessing · pre_approved · qualified ──registration──▶ registered
 └─ any of these ──(reason_code)──▶ paused ──resume to any of these
 └─ any of these, or paused ──(reason_code)──▶ discarded
```

- An opportunity moves between the stages from `new` to `qualified` in either direction; whether it should is for the eligibility rules to decide. Every move is recorded.
- `paused` and `discarded` need a `reason_code` (a lowercase name such as `not_interested`, `no_response`, `duplicate`). Resuming clears the reason.
- `registered` comes only from `qualified`, through the registration endpoint, with both Fineract ids. `registered` and `discarded` are final: no more moves, edits or consents.

## Consent and opt-out

Consent is the client's act. Bureau, SAT and contact consent are recorded by `POST /opportunities/{id}/consents/{bureau|sat|contact}`, with the time the LOS receives it; they cannot be set by editing the opportunity, and only the officer token allows this endpoint, never an agent's or a job's.

`POST /opportunities/{id}/opt-out` stops contact in any stage, clears contact consent and blocks moving to `contacting`. New contact consent lifts it while the opportunity is open; once it is `registered` or `discarded`, consents are refused (409) and the opt-out stays.

## API

| Method and path | |
|---|---|
| `POST /opportunities` | Create (stage `new`); `external_id` defaults to `opp-<id>` |
| `GET /opportunities?stage=&phone=&national_id=&external_id=&limit=` | Search, newest first (at most 200) |
| `GET /opportunities/{id}` | One opportunity |
| `PATCH /opportunities/{id}` | Edit the applicant and loan fields; `null` clears a field |
| `POST /opportunities/{id}/stage` | `{stage, reason_code, reason_note}` |
| `POST /opportunities/{id}/consents/{kind}` | Record a consent |
| `POST /opportunities/{id}/opt-out` | Stop contact |
| `POST /opportunities/{id}/registration` | `{fineract_client_id, fineract_loan_id}` |
| `POST /opportunities/{id}/documents/{kind}` | Upload a document: the image is the body (see Documents) |
| `GET /opportunities/{id}/documents` | Its documents, newest first, without the images |
| `GET /opportunities/{id}/documents/{document_id}/content` | A document's image |
| `POST /opportunities/{id}/documents/{document_id}/check` | `{result, reasons, extracted, compared}`: what its check found, recorded with the move it calls for |
| `GET /opportunities/{id}/events` | Audit trail |

Every call but `/health` needs a caller's token (see Access). Phone numbers are stored without spaces, dashes or brackets and national IDs in upper case, so searches match however they were typed. Amounts are returned as strings (`"15000.00"`). The audit trail names the fields that changed, never their values.

## Documents

Identity documents are kept with the opportunity. `POST /opportunities/{id}/documents/curp` takes the image itself as the body, with `Content-Type: image/png` or `image/jpeg` (`curl --data-binary @curp.png`; `/docs` offers a file picker):

- At most 5 MB (`413`), and a complete file of that type, whatever the header says: a PNG from its signature and `IHDR` to `IEND`, a JPEG from its start to its end marker (`415`). The LOS checks those bytes and never decodes an image; reading it is left to the vision model
- Only while the opportunity is open (`409` once registered or discarded)
- Every upload is kept, with its size, SHA-256 and who uploaded it; the newest is the one checked

A check records `result` (`valid`, `request_document` or `document_mismatch`), `reasons` (`{code, detail}`) and `extracted`, what the vision model read (strings of at most 200 characters, booleans or numbers), and in the same transaction makes the move the result calls for: `valid` moves the opportunity on to `assessing`, `document_mismatch` pauses it with the reasons as its note, and with `request_document` it stays in `documents`. `compared` is the opportunity's `national_id` and `full_name` as the check read them. The LOS refuses the check (`409`) once it no longer applies: the opportunity is no longer in `documents`, a newer document of the same kind was uploaded, or its CURP or name changed since. The same check again, once applied, changes nothing, so an interrupted run can be retried. A later check of the same document replaces it. The events `document_added` and `document_checked` name the document, its kind and size, and the result; never the image or what was read from it. Only kind `curp` exists so far.

## Access

Each caller has its own token, `LOS_<CALLER>_TOKEN` in the sandbox's `.env` (made by `scripts/gen-secrets.sh`), sent as `Authorization: Bearer <token>`. The token decides who the caller is and what it may do ([auth.py](auth.py)):

| Caller | Token | May |
|---|---|---|
| `intake` | `LOS_INTAKE_TOKEN`, in the OpenFn credential `los` | Search, create, and move a lead from `new` to `paused` or `discarded` (a possible duplicate, no way to contact) |
| `eligibility` | `LOS_ELIGIBILITY_TOKEN`, in the OpenFn credential `los-eligibility` | Read one opportunity, and move it from `qualifying` to `documents` or `paused` |
| `identity` | `LOS_IDENTITY_TOKEN`, in the OpenFn credential `los-identity` | Read an opportunity, list its documents and read their images, record a check, and move it from `documents` to `assessing` or `paused` |
| `officer` | `LOS_OFFICER_TOKEN`, for people (curl, the **Authorize** button at `/docs`) | Everything, including uploading documents |

- No token, or one the LOS does not know: `401`. A token that does not allow the call: `403`, and nothing is changed.
- `/health`, `/docs` and `/openapi.json` need no token; they hold no opportunity data.
- The audit trail names the caller. `intake`, `eligibility` and `identity` are named by their token: an `X-Actor` naming anyone else is refused. People share the officer token, so `X-Actor` names the person, recorded as `officer:<name>` (`officer` without the header).
- The LOS does not start if a token is missing, shorter than 32 characters, or the same for two callers.

A new caller (an agent, a job) gets its own entry in `auth.py` and its own token.

Besides its database, only the OpenFn worker shares a network with the LOS (`los-api`, for the intake, eligibility and identity jobs), and its port is published on 127.0.0.1 only.

## Tests

The API tests need a Postgres database whose name ends in `_test` (they empty it):

```bash
pip install -r requirements.txt -r requirements-dev.txt
LOS_DB_HOST=localhost LOS_DB_NAME=los_test LOS_DB_USER=los LOS_DB_PASSWORD=los pytest
```
