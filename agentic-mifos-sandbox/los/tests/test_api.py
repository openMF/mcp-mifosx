# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""API tests against a real Postgres database (see conftest.py)."""

import json
import uuid

import pytest
from fastapi.routing import APIRoute

import app

ANA = {
    "channel": "whatsapp",
    "full_name": "Ana López",
    "phone": "+52 55 1234-5678",
    "requested_amount": 15000,
    "currency": "MXN",
    "term_months": 12,
    "productive_use": "Inventory for a grocery store",
}


def create(client, body=None, headers=None):
    response = client.post("/opportunities", json=body or ANA, headers=headers)
    assert response.status_code == 201, response.text
    return response.json()


def move(client, opportunity, stage, headers=None, **reason):
    return client.post(f"/opportunities/{opportunity['id']}/stage", json={"stage": stage, **reason}, headers=headers)


def action(client, opportunity, path):
    return client.post(f"/opportunities/{opportunity['id']}/{path}")


def events(client, opportunity):
    response = client.get(f"/opportunities/{opportunity['id']}/events")
    assert response.status_code == 200, response.text
    return response.json()["events"]


def test_health(client):
    assert client.get("/health").json() == {"status": "UP"}


# --- Create -------------------------------------------------------------------

def test_create_starts_new_with_a_generated_external_id(client):
    opportunity = create(client)
    assert opportunity["stage"] == "new"
    assert opportunity["external_id"] == f"opp-{opportunity['id']}"
    assert opportunity["phone"] == "+525512345678"
    assert opportunity["requested_amount"] == "15000.00"
    assert opportunity["opted_out"] is False
    assert opportunity["bureau_consent_at"] is None


def test_create_keeps_a_given_external_id_and_refuses_a_duplicate(client):
    assert create(client, {**ANA, "external_id": "lead-42"})["external_id"] == "lead-42"
    response = client.post("/opportunities", json={**ANA, "external_id": "lead-42"})
    assert response.status_code == 409
    assert "lead-42" in response.json()["detail"]


def test_create_normalizes_national_id_and_currency(client):
    opportunity = create(client, {"channel": "web", "national_id": " goma850101hdfrrn09 ", "requested_amount": "2500.5", "currency": "mxn",
                                  "full_name": "  Beto Ruiz ", "email": " beto@example.com "})
    assert (opportunity["full_name"], opportunity["email"]) == ("Beto Ruiz", "beto@example.com")
    assert opportunity["national_id"] == "GOMA850101HDFRRN09"
    assert opportunity["currency"] == "MXN"
    assert opportunity["requested_amount"] == "2500.50"


@pytest.mark.parametrize("body", [
    {"channel": "web", "requested_amount": 1000},
    {"channel": "web", "full_name": "   "},
    {"channel": "web", "email": "not-an-email"},
    {"channel": "web", "currency": "MX", "requested_amount": 10},
    {"channel": "Web"},
    {"full_name": "No channel"},
    {"channel": "web", "phone": "55-12ab"},
    {"channel": "web", "requested_amount": "10.005", "currency": "MXN"},
    {"channel": "web", "term_months": 0},
    {"channel": "web", "stage": "qualified"},
    {"channel": "web", "bureau_consent_at": "2026-10-01T00:00:00Z"},
])
def test_create_rejects_invalid_or_non_editable_input(client, body):
    assert client.post("/opportunities", json=body).status_code == 422


# --- Read and search ----------------------------------------------------------

def test_an_unknown_opportunity_is_404(client):
    assert client.get(f"/opportunities/{uuid.uuid4()}").status_code == 404


def test_search_filters_and_order(client):
    ana = create(client)
    beto = create(client, {"channel": "web", "phone": "5512345678", "national_id": "ABC123"})
    assert move(client, beto, "contacting").status_code == 200

    def found(**query):
        response = client.get("/opportunities", params=query)
        assert response.status_code == 200, response.text
        return [o["id"] for o in response.json()["opportunities"]]

    assert found(phone="+52 (55) 1234 5678") == [ana["id"]]
    assert found(national_id="abc123") == [beto["id"]]
    assert found(stage="contacting") == [beto["id"]]
    assert found(external_id=ana["external_id"]) == [ana["id"]]
    assert found() == [beto["id"], ana["id"]]
    assert found(limit=1) == [beto["id"]]


def test_search_limit_is_at_most_200(client):
    assert client.get("/opportunities", params={"limit": 201}).status_code == 422


# --- Edit ---------------------------------------------------------------------

def test_edit_changes_fields_and_audits_only_their_names(client):
    opportunity = create(client)
    response = client.patch(f"/opportunities/{opportunity['id']}", json={"email": "ana@example.com", "full_name": "Ana María López"},
                            headers={"X-Actor": "officer-1"})
    assert response.status_code == 200, response.text
    assert response.json()["email"] == "ana@example.com"

    trail = events(client, opportunity)
    assert trail[-1]["action"] == "updated"
    assert trail[-1]["actor"] == "officer:officer-1"
    assert trail[-1]["detail"] == {"fields": ["email", "full_name"]}
    assert "ana@example.com" not in json.dumps(trail)
    assert "López" not in json.dumps(trail)


def test_edit_with_null_clears_a_field(client):
    opportunity = create(client)
    response = client.patch(f"/opportunities/{opportunity['id']}", json={"productive_use": None})
    assert response.json()["productive_use"] is None


@pytest.mark.parametrize("body", [
    {"stage": "qualified"},
    {"external_id": "other"},
    {"channel": "web"},
    {"opted_out": True},
    {"contact_consent_at": "2026-10-01T00:00:00Z"},
    {"reason_code": "duplicate"},
    {"fineract_loan_id": 1},
])
def test_edit_refuses_fields_that_are_not_editable(client, body):
    opportunity = create(client)
    assert client.patch(f"/opportunities/{opportunity['id']}", json=body).status_code == 422


def test_edit_refuses_an_amount_without_currency(client):
    opportunity = create(client, {"channel": "web"})
    response = client.patch(f"/opportunities/{opportunity['id']}", json={"requested_amount": 500})
    assert response.status_code == 422
    assert "currency" in response.json()["detail"]


def test_edit_is_refused_once_final(client):
    opportunity = create(client)
    assert move(client, opportunity, "discarded", reason_code="duplicate").status_code == 200
    assert client.patch(f"/opportunities/{opportunity['id']}", json={"email": "a@b.co"}).status_code == 409


# --- Stage --------------------------------------------------------------------

def test_stages_move_forward_and_back(client):
    opportunity = create(client)
    for stage in ("qualifying", "documents", "assessing", "documents", "qualified"):
        response = move(client, opportunity, stage)
        assert response.status_code == 200, response.text
        assert response.json()["stage"] == stage


def test_pause_needs_a_reason_and_resuming_clears_it(client):
    opportunity = create(client)
    assert move(client, opportunity, "paused").status_code == 422
    paused = move(client, opportunity, "paused", reason_code="no_response", reason_note="Three calls, no answer").json()
    assert (paused["reason_code"], paused["reason_note"]) == ("no_response", "Three calls, no answer")

    resumed = move(client, opportunity, "qualifying").json()
    assert (resumed["stage"], resumed["reason_code"], resumed["reason_note"]) == ("qualifying", None, None)
    assert events(client, opportunity)[1]["detail"] == {"reason_code": "no_response"}


def test_registered_is_refused_through_stage(client):
    opportunity = create(client)
    move(client, opportunity, "qualified")
    assert move(client, opportunity, "registered").status_code == 409


def test_contacting_is_refused_while_opted_out(client):
    opportunity = create(client)
    assert action(client, opportunity, "opt-out").status_code == 200
    assert move(client, opportunity, "contacting").status_code == 409
    assert move(client, opportunity, "qualifying").status_code == 200


# --- Consent and opt-out ------------------------------------------------------

def test_consent_is_timestamped_by_los_and_keeps_the_first_time(client):
    opportunity = create(client)
    first = action(client, opportunity, "consents/bureau").json()["bureau_consent_at"]
    assert first is not None
    again = action(client, opportunity, "consents/bureau").json()
    assert again["bureau_consent_at"] == first
    assert again["sat_consent_at"] is None
    assert [e["action"] for e in events(client, opportunity)] == ["created", "consent"]


def test_an_unknown_consent_kind_is_refused(client):
    opportunity = create(client)
    assert action(client, opportunity, "consents/marketing").status_code == 422


def test_consent_is_refused_once_final(client):
    opportunity = create(client)
    move(client, opportunity, "discarded", reason_code="not_interested")
    assert action(client, opportunity, "consents/sat").status_code == 409


def test_opt_out_clears_contact_consent_and_new_consent_lifts_it(client):
    opportunity = create(client)
    assert action(client, opportunity, "consents/contact").json()["contact_consent_at"] is not None

    opted_out = action(client, opportunity, "opt-out").json()
    assert (opted_out["opted_out"], opted_out["contact_consent_at"]) == (True, None)

    consented = action(client, opportunity, "consents/contact").json()
    assert consented["opted_out"] is False
    assert consented["contact_consent_at"] is not None


def test_opt_out_is_allowed_after_discard(client):
    opportunity = create(client)
    move(client, opportunity, "discarded", reason_code="duplicate")
    assert action(client, opportunity, "opt-out").json()["opted_out"] is True


# --- Registration -------------------------------------------------------------

def register(client, opportunity, client_id=7, loan_id=14):
    return client.post(f"/opportunities/{opportunity['id']}/registration",
                       json={"fineract_client_id": client_id, "fineract_loan_id": loan_id})


def test_registration_only_from_qualified(client):
    opportunity = create(client)
    move(client, opportunity, "pre_approved")
    response = register(client, opportunity)
    assert response.status_code == 409
    assert "pre_approved" in response.json()["detail"]


def test_registration_records_the_fineract_ids_once(client):
    opportunity = create(client)
    move(client, opportunity, "qualified")
    registered = register(client, opportunity).json()
    assert (registered["stage"], registered["fineract_client_id"], registered["fineract_loan_id"]) == ("registered", 7, 14)

    assert register(client, opportunity).status_code == 200
    assert register(client, opportunity, loan_id=15).status_code == 409
    assert move(client, opportunity, "qualified").status_code == 409
    assert [e["action"] for e in events(client, opportunity)] == ["created", "stage", "registered"]


# --- Audit trail --------------------------------------------------------------

def test_events_record_actors_and_stage_moves(client, intake):
    opportunity = create(client, headers=intake)
    move(client, opportunity, "contacting", headers={"X-Actor": "maria"})
    trail = events(client, opportunity)
    assert [(e["action"], e["actor"], e["from_stage"], e["to_stage"]) for e in trail] == [
        ("created", "intake", None, "new"),
        ("stage", "officer:maria", "new", "contacting"),
    ]


def test_an_officer_without_x_actor_is_recorded_as_officer(client):
    assert events(client, create(client))[0]["actor"] == "officer"
    assert client.post("/opportunities", json=ANA, headers={"X-Actor": "bad/name"}).status_code == 422


def test_events_of_an_unknown_opportunity_are_404(client):
    assert client.get(f"/opportunities/{uuid.uuid4()}/events").status_code == 404


# --- Access -------------------------------------------------------------------

def test_every_route_but_health_needs_a_token(client):
    del client.headers["Authorization"]
    calls = [(method, route.path.replace("{opportunity_id}", str(uuid.uuid4())).replace("{kind}", "bureau"))
             for route in app.app.routes if isinstance(route, APIRoute) and route.path != "/health"
             for method in route.methods]
    assert len(calls) == 9
    for method, path in calls:
        response = client.request(method, path)
        assert response.status_code == 401, (method, path, response.text)
        assert response.headers["WWW-Authenticate"] == "Bearer"
    for path in ("/health", "/docs", "/openapi.json"):
        assert client.get(path).status_code == 200, path


@pytest.mark.parametrize("authorization", ["Basic b2ZmaWNlcjpvZmZpY2Vy", "Bearer", "Bearer unknown-token-0123456789abcdef0123"])
def test_a_malformed_or_unknown_token_is_401(client, authorization):
    assert client.get("/opportunities", headers={"Authorization": authorization}).status_code == 401


def test_intake_searches_creates_and_sets_new_leads_aside(client, intake):
    duplicate = create(client, headers=intake)
    no_contact = create(client, {"channel": "web", "full_name": "No Contact"}, headers=intake)
    found = client.get("/opportunities", params={"phone": ANA["phone"]}, headers=intake)
    assert [o["id"] for o in found.json()["opportunities"]] == [duplicate["id"]]

    assert move(client, duplicate, "paused", headers=intake, reason_code="possible_duplicate").status_code == 200
    assert move(client, no_contact, "discarded", headers=intake, reason_code="no_contact").status_code == 200
    assert [e["actor"] for e in events(client, duplicate)] == ["intake", "intake"]


@pytest.mark.parametrize("stage", ["contacting", "qualified"])
def test_intake_may_not_move_a_lead_forward(client, intake, stage):
    lead = create(client, headers=intake)
    response = move(client, lead, stage, headers=intake)
    assert response.status_code == 403
    assert response.json()["detail"] == "intake may only move an opportunity from new to discarded or paused"


def test_intake_may_set_aside_only_a_new_lead(client, intake):
    lead = create(client)
    move(client, lead, "pre_approved")
    assert move(client, lead, "discarded", headers=intake, reason_code="no_contact").status_code == 403
    assert client.get(f"/opportunities/{lead['id']}").json()["stage"] == "pre_approved"


def test_intake_may_not_read_edit_consent_opt_out_register_or_read_events(client, intake):
    lead = create(client, headers=intake)
    path = f"/opportunities/{lead['id']}"
    for method, url, body in [
        ("GET", path, None),
        ("PATCH", path, {"email": "ana@example.com"}),
        ("POST", f"{path}/consents/bureau", None),
        ("POST", f"{path}/opt-out", None),
        ("POST", f"{path}/registration", {"fineract_client_id": 7, "fineract_loan_id": 14}),
        ("GET", f"{path}/events", None),
    ]:
        response = client.request(method, url, json=body, headers=intake)
        assert response.status_code == 403, (method, url, response.text)
        assert response.json()["detail"].startswith("intake may not ")
    assert client.get(path).json() == lead
    assert [e["action"] for e in events(client, lead)] == ["created"]


def test_intake_is_named_by_its_token(client, intake):
    assert events(client, create(client, headers={**intake, "X-Actor": "intake"}))[0]["actor"] == "intake"
    response = client.post("/opportunities", json=ANA, headers={**intake, "X-Actor": "maria"})
    assert response.status_code == 403
    assert "X-Actor must be intake or absent" in response.json()["detail"]
    assert len(client.get("/opportunities").json()["opportunities"]) == 1


def test_an_officer_cannot_pass_for_intake(client):
    assert events(client, create(client, headers={"X-Actor": "intake"}))[0]["actor"] == "officer:intake"


def qualifying(client):
    """An opportunity an officer has moved to qualifying."""
    opportunity = create(client)
    assert move(client, opportunity, "qualifying").status_code == 200
    return opportunity


def test_eligibility_reads_and_moves_a_qualifying_opportunity_on(client, eligibility):
    opportunity = qualifying(client)
    response = client.get(f"/opportunities/{opportunity['id']}", headers=eligibility)
    assert response.status_code == 200
    assert response.json()["stage"] == "qualifying"
    assert move(client, opportunity, "documents", headers=eligibility).status_code == 200
    assert events(client, opportunity)[-1]["actor"] == "eligibility"


def test_eligibility_pauses_with_a_reason(client, eligibility):
    opportunity = qualifying(client)
    response = move(client, opportunity, "paused", headers=eligibility, reason_code="amount_out_of_range",
                    reason_note="amount 150000.00 above the product's 100000")
    assert response.status_code == 200
    assert (response.json()["stage"], response.json()["reason_code"]) == ("paused", "amount_out_of_range")


@pytest.mark.parametrize("stage, target, reason", [
    ("new", "documents", {}),
    ("documents", "paused", {"reason_code": "needs_review"}),
    ("qualifying", "qualified", {}),
    ("qualifying", "discarded", {"reason_code": "not_interested"}),
])
def test_eligibility_may_make_no_other_move(client, eligibility, stage, target, reason):
    opportunity = create(client)
    if stage != "new":
        move(client, opportunity, stage)
    response = move(client, opportunity, target, headers=eligibility, **reason)
    assert response.status_code == 403
    assert response.json()["detail"] == "eligibility may only move an opportunity from qualifying to documents or paused"
    assert client.get(f"/opportunities/{opportunity['id']}").json()["stage"] == stage


def test_eligibility_may_not_search_create_edit_consent_opt_out_register_or_read_events(client, eligibility):
    opportunity = qualifying(client)
    path = f"/opportunities/{opportunity['id']}"
    for method, url, body in [
        ("GET", "/opportunities", None),
        ("POST", "/opportunities", ANA),
        ("PATCH", path, {"email": "ana@example.com"}),
        ("POST", f"{path}/consents/bureau", None),
        ("POST", f"{path}/opt-out", None),
        ("POST", f"{path}/registration", {"fineract_client_id": 7, "fineract_loan_id": 14}),
        ("GET", f"{path}/events", None),
    ]:
        response = client.request(method, url, json=body, headers=eligibility)
        assert response.status_code == 403, (method, url, response.text)
        assert response.json()["detail"].startswith("eligibility may not ")
    assert [e["action"] for e in events(client, opportunity)] == ["created", "stage"]
    assert len(client.get("/opportunities").json()["opportunities"]) == 1
