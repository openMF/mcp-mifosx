# LOS: loan origination service

Keeps commercial opportunities (leads and applicants) from first contact until they are registered in Fineract or discarded. It has its own database and is linked to Fineract by external ID: only a qualified opportunity reaches Fineract.

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

Consent is the client's act. Bureau, SAT and contact consent are recorded by `POST /opportunities/{id}/consents/{bureau|sat|contact}`, with the time the LOS receives it; they cannot be set by editing the opportunity, and agents are not given this endpoint.

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
| `GET /opportunities/{id}/events` | Audit trail |

Writes take an optional `X-Actor` header (the agent or officer) for the audit trail. Phone numbers are stored without spaces, dashes or brackets and national IDs in upper case, so searches match however they were typed. Amounts are returned as strings (`"15000.00"`). The audit trail names the fields that changed, never their values.

The API has no authentication of its own. No other sandbox service shares a network with it except its database, and its port is published on 127.0.0.1 only.

## Tests

The API tests need a Postgres database whose name ends in `_test` (they empty it):

```bash
pip install -r requirements.txt -r requirements-dev.txt
LOS_DB_HOST=localhost LOS_DB_NAME=los_test LOS_DB_USER=los LOS_DB_PASSWORD=los pytest
```
