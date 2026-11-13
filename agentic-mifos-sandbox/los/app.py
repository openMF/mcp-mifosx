# Copyright since 2026 Mifos Initiative
# This Source Code Form is subject to the terms of the Mozilla Public License, v. 2.0.
"""Loan origination service (LOS) of the agentic Mifos sandbox.

Keeps commercial opportunities, from first contact until they are registered
in Fineract or discarded. See README.md for the stages and rules.
"""

import os
import re
from contextlib import asynccontextmanager
from datetime import datetime
from decimal import Decimal
from functools import partial
from typing import Annotated, Literal
from uuid import UUID

import pg8000.native
from fastapi import Depends, FastAPI, Query, Request
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import JSONResponse, Response
from pydantic import AfterValidator, BaseModel, ConfigDict, Field, StringConstraints, model_validator

import auth
import rules
import store


def normalize_phone(value: str) -> str:
    """Removes spaces, dashes and brackets, so a search matches however the number was typed."""
    normalized = re.sub(r"[ ()-]", "", value)
    if not re.fullmatch(r"\+?[0-9]+", normalized) or not 7 <= len(normalized) <= 20:
        raise ValueError("phone must be 7 to 20 digits, optionally starting with +")
    return normalized


# Pydantic checks a pattern or length before it trims or upper-cases, so the
# fields that are normalised use these validators instead: normalise, then check.
def text(max_length: int) -> AfterValidator:
    """Trimmed text of 1 to max_length characters."""
    def check(value: str) -> str:
        value = value.strip()
        if not 1 <= len(value) <= max_length:
            raise ValueError(f"must be 1 to {max_length} characters")
        return value
    return AfterValidator(check)


def code(pattern: str, description: str) -> AfterValidator:
    """Trimmed, upper-cased value matching `pattern`."""
    def check(value: str) -> str:
        value = value.strip().upper()
        if not re.fullmatch(pattern, value):
            raise ValueError(f"must be {description}")
        return value
    return AfterValidator(check)


def check_email(value: str) -> str:
    value = value.strip()
    if len(value) > 254 or not re.fullmatch(r"[^@\s]+@[^@\s]+\.[^@\s]+", value):
        raise ValueError("must be an email address")
    return value


ExternalId = Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{1,100}$")]
Channel = Annotated[str, StringConstraints(pattern=r"^[a-z][a-z0-9_-]{0,39}$")]
FullName = Annotated[str, text(200)]
Phone = Annotated[str, AfterValidator(normalize_phone)]
Email = Annotated[str, AfterValidator(check_email)]
NationalId = Annotated[str, code(r"[A-Z0-9-]{1,32}", "1 to 32 letters, digits or dashes")]
Amount = Annotated[Decimal, Field(gt=0, max_digits=14, decimal_places=2)]
Currency = Annotated[str, code(r"[A-Z]{3}", "a three-letter ISO currency code such as MXN")]
Term = Annotated[int, Field(ge=1, le=600)]
ProductiveUse = Annotated[str, text(500)]
ReasonCode = Annotated[str, StringConstraints(pattern=r"^[a-z][a-z0-9_]{1,49}$")]
ReasonNote = Annotated[str, text(1000)]
Stage = Literal[rules.STAGES]


class ApplicantFields(BaseModel):
    """The applicant and loan fields, the only ones that can be edited."""

    model_config = ConfigDict(extra="forbid")

    full_name: FullName | None = None
    phone: Phone | None = None
    email: Email | None = None
    national_id: NationalId | None = None
    requested_amount: Amount | None = None
    currency: Currency | None = None
    term_months: Term | None = None
    productive_use: ProductiveUse | None = None


class OpportunityCreate(ApplicantFields):
    external_id: ExternalId | None = Field(None, description="Carried across systems; defaults to opp-<id>")
    channel: Channel = Field(description="Where the opportunity came from, e.g. web, whatsapp, mifos-mobile")

    @model_validator(mode="after")
    def amount_needs_currency(self):
        if self.requested_amount is not None and self.currency is None:
            raise ValueError("a requested_amount needs a currency")
        return self


class StageChange(BaseModel):
    model_config = ConfigDict(extra="forbid")

    stage: Stage
    reason_code: ReasonCode | None = Field(None, description="Required for paused and discarded, e.g. not_interested")
    reason_note: ReasonNote | None = None


class RegistrationCompared(BaseModel):
    """The opportunity's fields as the registration read them to make the loan application."""

    model_config = ConfigDict(extra="forbid")

    national_id: str | None
    full_name: str | None
    requested_amount: Decimal | None
    currency: str | None
    term_months: int | None


class Registration(BaseModel):
    model_config = ConfigDict(extra="forbid")

    fineract_client_id: int = Field(gt=0)
    fineract_loan_id: int = Field(gt=0)
    compared: RegistrationCompared | None = Field(None, description="Refused (409) if any of these changed since")


class Opportunity(BaseModel):
    id: UUID
    external_id: str
    channel: str
    stage: str
    full_name: str | None
    phone: str | None
    email: str | None
    national_id: str | None
    requested_amount: Decimal | None
    currency: str | None
    term_months: int | None
    productive_use: str | None
    bureau_consent_at: datetime | None
    sat_consent_at: datetime | None
    contact_consent_at: datetime | None
    opted_out: bool
    reason_code: str | None
    reason_note: str | None
    fineract_client_id: int | None
    fineract_loan_id: int | None
    created_at: datetime
    updated_at: datetime


class OpportunityList(BaseModel):
    opportunities: list[Opportunity]


class Event(BaseModel):
    id: int
    at: datetime
    actor: str
    action: str
    from_stage: str | None
    to_stage: str | None
    detail: dict


class EventList(BaseModel):
    events: list[Event]


class Document(BaseModel):
    """A document without its image, with its latest check."""

    id: UUID
    opportunity_id: UUID
    kind: str
    content_type: str
    size: int
    sha256: str
    uploaded_at: datetime
    uploaded_by: str
    checked_at: datetime | None
    check_result: str | None
    check_reasons: list | None
    extracted: dict | None


class DocumentList(BaseModel):
    documents: list[Document]


class CheckReason(BaseModel):
    model_config = ConfigDict(extra="forbid")

    code: ReasonCode
    detail: ReasonNote


ExtractedKey = Annotated[str, StringConstraints(pattern=r"^[A-Za-z_]{1,40}$")]
ExtractedValue = Annotated[str, StringConstraints(max_length=200)] | bool | float | None


class CheckCompared(BaseModel):
    """The applicant's fields as the check read them from the opportunity, to compare with the document."""

    model_config = ConfigDict(extra="forbid")

    national_id: str | None
    full_name: str | None


class DocumentCheck(BaseModel):
    """What a document's check found. `extracted` is what the model read from the image."""

    model_config = ConfigDict(extra="forbid")

    result: Literal[rules.CHECK_RESULTS]
    reasons: Annotated[list[CheckReason], Field(max_length=20)] = []
    extracted: Annotated[dict[ExtractedKey, ExtractedValue], Field(max_length=20)] = {}
    compared: CheckCompared


class CheckRecorded(BaseModel):
    document: Document
    opportunity: Opportunity


@asynccontextmanager
async def lifespan(application: FastAPI):
    application.state.tokens = auth.load_tokens(os.environ)
    con = store.connect()
    try:
        store.create_schema(con)
    finally:
        con.close()
    yield


app = FastAPI(
    title="Agentic Mifos sandbox LOS",
    description="Commercial opportunities before they reach Fineract. Only a qualified opportunity is registered there. "
                "Every call but /health needs a caller's token (Authorize).",
    version="1.0.0",
    lifespan=lifespan,
)


def _error(status: int, headers: dict | None = None):
    return lambda _request, exc: JSONResponse(status_code=status, content={"detail": str(exc)}, headers=headers)


app.add_exception_handler(store.NotFound, _error(404))
app.add_exception_handler(rules.Conflict, _error(409))
app.add_exception_handler(rules.Invalid, _error(422))
app.add_exception_handler(rules.TooLarge, _error(413))
app.add_exception_handler(rules.UnsupportedType, _error(415))
app.add_exception_handler(auth.Unauthorized, _error(401, {"WWW-Authenticate": "Bearer"}))
app.add_exception_handler(auth.Forbidden, _error(403))


def connection():
    con = store.connect()
    try:
        yield con
    finally:
        con.close()


Con = Annotated[pg8000.native.Connection, Depends(connection)]


@app.get("/health")
def health():
    try:
        con = store.connect()
        try:
            con.run("SELECT 1")
        finally:
            con.close()
    except Exception:
        return JSONResponse(status_code=503, content={"status": "DOWN"})
    return {"status": "UP"}


@app.post("/opportunities", status_code=201, response_model=Opportunity)
def create_opportunity(who: Annotated[auth.Who, auth.change("create")], body: OpportunityCreate, con: Con):
    return store.create(con, body.model_dump(exclude_none=True), who.actor)


@app.get("/opportunities", response_model=OpportunityList, dependencies=[auth.allow("search")])
def search_opportunities(
    con: Con,
    stage: Stage | None = None,
    phone: str | None = None,
    national_id: str | None = None,
    external_id: str | None = None,
    limit: Annotated[int, Query(ge=1, le=200)] = 50,
):
    """Newest first. Phone and national ID match however they were typed."""
    filters = {}
    if stage:
        filters["stage"] = stage
    if phone:
        try:
            filters["phone"] = normalize_phone(phone)
        except ValueError as e:
            raise rules.Invalid(str(e)) from None
    if national_id:
        filters["national_id"] = national_id.strip().upper()
    if external_id:
        filters["external_id"] = external_id
    return {"opportunities": store.search(con, filters, limit)}


@app.get("/opportunities/{opportunity_id}", response_model=Opportunity, dependencies=[auth.allow("read")])
def get_opportunity(opportunity_id: UUID, con: Con):
    return store.get(con, opportunity_id)


@app.patch("/opportunities/{opportunity_id}", response_model=Opportunity)
def edit_opportunity(who: Annotated[auth.Who, auth.change("edit")], opportunity_id: UUID, body: ApplicantFields, con: Con):
    """Changes applicant and loan fields; null clears a field."""
    return store.update(con, opportunity_id, body.model_dump(exclude_unset=True), who.actor)


@app.post("/opportunities/{opportunity_id}/stage", response_model=Opportunity)
def change_stage(who: Annotated[auth.Who, auth.change("stage")], opportunity_id: UUID, body: StageChange, con: Con):
    return store.change_stage(con, opportunity_id, body.stage, body.reason_code, body.reason_note, who.actor,
                              may_move=partial(auth.check_move, who.caller))


@app.post("/opportunities/{opportunity_id}/consents/{kind}", response_model=Opportunity)
def record_consent(who: Annotated[auth.Who, auth.change("consent")], opportunity_id: UUID, kind: Literal[rules.CONSENT_KINDS], con: Con):
    """Records the client's consent with the time LOS receives it. Consent is the client's act:
    only the officer token allows it."""
    return store.record_consent(con, opportunity_id, kind, who.actor)


@app.post("/opportunities/{opportunity_id}/opt-out", response_model=Opportunity)
def opt_out(who: Annotated[auth.Who, auth.change("opt_out")], opportunity_id: UUID, con: Con):
    """Stops contact. Allowed in every stage."""
    return store.opt_out(con, opportunity_id, who.actor)


@app.post("/opportunities/{opportunity_id}/registration", response_model=Opportunity)
def register(who: Annotated[auth.Who, auth.change("register")], opportunity_id: UUID, body: Registration, con: Con):
    """Records the Fineract client and loan application created for a qualified opportunity. With `compared`,
    refused (409) if the opportunity's CURP, name, amount, currency or term changed since they were read."""
    return store.register(con, opportunity_id, body.fineract_client_id, body.fineract_loan_id, who.actor,
                          body.compared.model_dump() if body.compared else None)


# The image is the request body; /docs offers a file picker for it.
DOCUMENT_BODY = {"requestBody": {"required": True, "content": {
    content_type: {"schema": {"type": "string", "format": "binary"}} for content_type in rules.DOCUMENT_TYPES}}}


@app.post("/opportunities/{opportunity_id}/documents/{kind}", status_code=201, response_model=Document, openapi_extra=DOCUMENT_BODY)
async def upload_document(who: Annotated[auth.Who, auth.change("upload")], opportunity_id: UUID, kind: Literal[rules.DOCUMENT_KINDS],
                          request: Request, con: Con):
    """Keeps an identity document of an open opportunity. The body is the image: PNG or JPEG, at most 5 MB."""
    content_type = request.headers.get("content-type", "").split(";")[0].strip().lower()
    rules.check_document_type(content_type)
    content = bytearray()
    async for chunk in request.stream():
        content += chunk
        if len(content) > rules.MAX_DOCUMENT_BYTES:
            raise rules.TooLarge(f"a document may be at most {rules.MAX_DOCUMENT_BYTES} bytes")
    rules.check_document(content_type, bytes(content))
    return await run_in_threadpool(store.add_document, con, opportunity_id, kind, content_type, bytes(content), who.actor)


@app.get("/opportunities/{opportunity_id}/documents", response_model=DocumentList, dependencies=[auth.allow("documents")])
def list_documents(opportunity_id: UUID, con: Con):
    """Newest first, each with its latest check; without the images."""
    return {"documents": store.documents(con, opportunity_id)}


@app.get("/opportunities/{opportunity_id}/documents/{document_id}/content", response_class=Response,
         dependencies=[auth.allow("document_content")],
         responses={200: {"content": {content_type: {} for content_type in rules.DOCUMENT_TYPES}}})
def get_document_content(opportunity_id: UUID, document_id: UUID, con: Con):
    """The document's image, as uploaded."""
    found = store.document_content(con, opportunity_id, document_id)
    return Response(content=found["content"], media_type=found["content_type"],
                    headers={"Cache-Control": "no-store", "X-Content-Type-Options": "nosniff"})


@app.post("/opportunities/{opportunity_id}/documents/{document_id}/check", response_model=CheckRecorded)
def record_document_check(who: Annotated[auth.Who, auth.change("check")], opportunity_id: UUID, document_id: UUID,
                          body: DocumentCheck, con: Con):
    """Records what a document's check found (the identity workflow) and, in the same transaction, the move
    its result calls for: valid moves the opportunity on to assessing, document_mismatch pauses it with the
    reasons. Refused (409) once the check no longer applies: the opportunity has left documents, a newer
    document was uploaded, or its CURP or name changed since `compared`. The same check again changes nothing."""
    document, opportunity = store.record_check(
        con, opportunity_id, document_id, body.result, [r.model_dump() for r in body.reasons], body.extracted,
        body.compared.model_dump(), who.actor, may_move=partial(auth.check_move, who.caller))
    return {"document": document, "opportunity": opportunity}


@app.get("/opportunities/{opportunity_id}/events", response_model=EventList, dependencies=[auth.allow("events")])
def opportunity_events(opportunity_id: UUID, con: Con):
    """Audit trail, oldest first. Events name changed fields, never their values."""
    return {"events": store.events(con, opportunity_id)}
