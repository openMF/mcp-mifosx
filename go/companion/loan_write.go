// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOAN-WRITE: the loan WRITE facade. The MifosSave (mifos-x-group-banking)
// app's three loan-write services each POST at a bare Fineract path (the app's
// base URL points at THIS Go server, which serves only explicitly-registered
// routes — there is no catch-all proxy):
//
//	loan-apply       POST /loans                                   -> ApplyLoanResponseDto
//	loan-repayment   POST /loans/{loanId}/transactions?command=repayment -> RecordRepaymentResponseDto
//	loan-writeoff    POST /loans/{loanId}/transactions?command=writeoff  -> WriteoffLoanResponseDto
//
// Source of truth for the wire shapes is the app's OWN approved contract:
//   - loan-apply    : service loanapply/LoanApplyApiImpl.kt (applyLoan -> POST /loans)
//                     model  LoanApplyDto.kt (ApplyLoanRequestDto / ApplyLoanResponseDto)
//                     mapper LoanApplyMappers.kt (ApplyLoanRequest.toDto)
//   - loan-repay    : service loanrepayment/LoanRepaymentApiImpl.kt (POST .../transactions?command=repayment)
//                     model  RecordRepaymentDto.kt (RecordRepaymentRequestDto / RecordRepaymentResponseDto)
//   - loan-writeoff : service loanwriteoff/LoanWriteoffApiImpl.kt (POST .../transactions?command=writeoff)
//                     model  WriteoffLoanDto.kt (WriteoffLoanRequestDto / WriteoffLoanResponseDto)
//
// Writes use the shared FineractClient service credential (adapter.DoRequest, which
// POSTs with the service BasicAuth) — the correct path for a treasurer/organizer
// write, exactly like the group-create + member-invite facades. The end-user
// credential path (companion.go's fineractPost) is reserved for /authentication.
//
// DATE-FORMAT decisions (per the app's DTOs + mappers):
//   - loan-apply expectedDisbursementDate / submittedOnDate: the app formats both via
//     RecordRepaymentMappers.fineractTransactionDate -> "dd MMMM yyyy" (e.g. "02 August 2026").
//     So this facade sends dateFormat="dd MMMM yyyy", locale="en" on the Fineract create body.
//   - loan-repayment transactionDate + loan-writeoff transactionDate: the app's DTOs
//     force-encode locale="en" + dateFormat="dd MMMM yyyy" (@EncodeDefault ALWAYS) alongside
//     the "dd MMMM yyyy" transactionDate string — an ALREADY-EXACT Fineract transaction body.
//     This facade forwards that body verbatim (re-defaulting locale/dateFormat only if a caller
//     ever omitted them), so no Instant.parse / LocalDate.parse hazard applies here — Fineract
//     itself parses the "dd MMMM yyyy" string via the accompanying dateFormat.
//
// SHAPE-BRIDGE decision (loan-apply only): the app's ApplyLoanRequestDto carries the five
// enum-lookup fields (loanTermFrequencyType / repaymentFrequencyType / amortizationType /
// interestType / interestCalculationPeriodType) as nested {id, value} objects, and OMITS the
// loanType / loanPurposeId-optionality / locale / dateFormat that a raw Fineract POST /loans
// command REQUIRES. Fineract's create-loan command deserializer expects those five fields as
// bare integer enum ids (not objects) plus loanType + locale + dateFormat + a
// transactionProcessingStrategyCode. This facade flattens each {id,value} -> its .id and adds the
// missing required fields — the same "normalize the app shape onto the real Fineract write" role
// the group-create facade plays. The app DTO's declared default ids already ARE the correct
// Fineract enum ids (Weeks=1, Equal installments=1, Declining Balance=0, Same as repayment
// period=1), so the flatten is a faithful pass-through, not a re-mapping.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

const loansPath = "/loans"

// registerLoanWriteRoutes wires the COMP-LOAN-WRITE endpoints. Called from
// RegisterRoutes. Repayment and writeoff share the SAME path
// (POST /loans/{loanId}/transactions) and differ only by the ?command= query
// param — Go 1.22 ServeMux does not dispatch on the query string, so a single
// handler receives both and switches on command. These patterns are disjoint
// from loan_list.go's GET /groups/{groupId}/loans + GET /clients/{clientId}/loans.
func (h *Handler) registerLoanWriteRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /loans", h.HandleApplyLoan)
	mux.HandleFunc("POST /loans/{loanId}/transactions", h.HandleLoanTransaction)
}

// ---- App-facing wire types (exact match to the KMP DTOs) ----

// statusRefIn == the nested {id, value} lookup object on ApplyLoanRequestDto's
// five enum fields (FineractStatusDto). id is a pointer so a genuinely-absent
// object is distinguishable from an explicit id:0 (interestType's real default).
type statusRefIn struct {
	ID    *int   `json:"id"`
	Value string `json:"value"`
}

// applyLoanRequestIn == ApplyLoanRequestDto (LoanApplyDto.kt) as it arrives on the wire.
type applyLoanRequestIn struct {
	ClientID                      int64       `json:"clientId"`
	ProductID                     int64       `json:"productId"`
	Principal                     float64     `json:"principal"`
	LoanTermFrequency             int         `json:"loanTermFrequency"`
	LoanTermFrequencyType         statusRefIn `json:"loanTermFrequencyType"`
	NumberOfRepayments            int         `json:"numberOfRepayments"`
	RepaymentEvery                int         `json:"repaymentEvery"`
	RepaymentFrequencyType        statusRefIn `json:"repaymentFrequencyType"`
	InterestRatePerPeriod         float64     `json:"interestRatePerPeriod"`
	AmortizationType              statusRefIn `json:"amortizationType"`
	InterestType                  statusRefIn `json:"interestType"`
	InterestCalculationPeriodType statusRefIn `json:"interestCalculationPeriodType"`
	ExpectedDisbursementDate      string      `json:"expectedDisbursementDate"`
	SubmittedOnDate               string      `json:"submittedOnDate"`
	LoanPurposeID                 int         `json:"loanPurposeId"`
}

// loanWriteResponseDto == the shared Fineract loan-command resource-create envelope,
// matching ApplyLoanResponseDto / RecordRepaymentResponseDto / WriteoffLoanResponseDto
// (all four: officeId, clientId, loanId, resourceId). officeId is an int per the app DTOs.
type loanWriteResponseDto struct {
	OfficeID   int   `json:"officeId"`
	ClientID   int64 `json:"clientId"`
	LoanID     int64 `json:"loanId"`
	ResourceID int64 `json:"resourceId"`
}

// ---- Handlers ----

// HandleApplyLoan (loan-apply) decodes the app's ApplyLoanRequestDto, flattens it onto a real
// Fineract create-loan command body, POSTs /loans, and returns the ApplyLoanResponseDto envelope.
func (h *Handler) HandleApplyLoan(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var in applyLoanRequestIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if in.ClientID <= 0 || in.ProductID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "clientId and productId are required")
		return
	}

	repaymentEvery := in.RepaymentEvery
	if repaymentEvery <= 0 {
		repaymentEvery = 1 // ApplyLoanRequestDto's declared default.
	}

	body := map[string]interface{}{
		"clientId":                      in.ClientID,
		"productId":                     in.ProductID,
		"loanType":                      "individual", // required by Fineract; app DTO omits it (per-client loan).
		"principal":                     in.Principal,
		"loanTermFrequency":             in.LoanTermFrequency,
		"loanTermFrequencyType":         refID(in.LoanTermFrequencyType, 1), // Weeks
		"numberOfRepayments":            in.NumberOfRepayments,
		"repaymentEvery":                repaymentEvery,
		"repaymentFrequencyType":        refID(in.RepaymentFrequencyType, 1), // Weeks
		"interestRatePerPeriod":         in.InterestRatePerPeriod,
		"amortizationType":              refID(in.AmortizationType, 1), // Equal installments
		"interestType":                  refID(in.InterestType, 0),     // Declining Balance
		"interestCalculationPeriodType": refID(in.InterestCalculationPeriodType, 1), // Same as repayment period
		// Modern Fineract (mifos-bank-2) takes transactionProcessingStrategyCode (a String),
		// NOT the legacy int transactionProcessingStrategyId the app DTO carries — confirmed by
		// the raw loan read exposing "transactionProcessingStrategyCode". Standard strategy.
		"transactionProcessingStrategyCode": "mifos-standard-strategy",
		"expectedDisbursementDate":          in.ExpectedDisbursementDate,
		"submittedOnDate":                   in.SubmittedOnDate,
		"locale":                            "en",
		"dateFormat":                        "dd MMMM yyyy",
	}
	// loanPurposeId references an m_code_value row; only forward a real, positive id
	// (a 0/absent purpose would make Fineract reject an otherwise-valid application).
	if in.LoanPurposeID > 0 {
		body["loanPurposeId"] = in.LoanPurposeID
	}

	raw, err := h.Fineract.DoRequest("POST", "loans", body, nil)
	if err != nil {
		writeUpstreamLoanError(w, raw, "apply loan", err)
		return
	}
	writeLoanEnvelope(w, raw)
}

// HandleLoanTransaction (loan-repayment + loan-writeoff) posts a repayment or writeoff transaction
// against {loanId}, dispatching on the ?command= query param. The app's DTOs already emit an
// exact Fineract transaction body (transactionDate "dd MMMM yyyy" + locale + dateFormat), so the
// body is forwarded near-verbatim.
func (h *Handler) HandleLoanTransaction(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	loanID, ok := loanIDParam(w, r)
	if !ok {
		return
	}
	command := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("command")))
	switch command {
	case "repayment", "writeoff", "disburse":
		// supported
	case "":
		writeErr(w, http.StatusBadRequest, "bad_request", "command query param is required (repayment|writeoff|disburse)")
		return
	default:
		writeErr(w, http.StatusBadRequest, "bad_request", "unsupported command: "+command)
		return
	}

	var body map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if body == nil {
		body = map[string]interface{}{}
	}

	// disburse is the meeting-conduct loan-approval leg: the app posts it to the /transactions
	// path with ?command=disburse, but in Fineract disburse (and its prerequisite approve) are
	// LOAN commands on /loans/{id}, not transactions — and a member's loan-apply leaves the loan
	// SUBMITTED (pending). So approve-then-disburse here so an approved application actually
	// disburses the money to the member.
	if command == "disburse" {
		h.handleLoanDisburse(w, loanID, body)
		return
	}
	// Fineract needs locale + dateFormat to parse the "dd MMMM yyyy" transactionDate. The app's
	// DTOs force-encode these, but re-default defensively for any caller that omitted them.
	if _, present := body["locale"]; !present {
		body["locale"] = "en"
	}
	if _, present := body["dateFormat"]; !present {
		body["dateFormat"] = "dd MMMM yyyy"
	}
	// paymentTypeId is optional on a Fineract repayment; a 0/negative value means "unselected"
	// and would make Fineract reject the transaction ("payment type 0 does not exist"). Drop it
	// when non-positive so an unselected payment method never blocks a repayment. (writeoff never
	// carries a paymentTypeId.)
	if v, present := body["paymentTypeId"]; present {
		if n, isNum := v.(float64); isNum && n <= 0 {
			delete(body, "paymentTypeId")
		}
	}

	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("loans/%d/transactions", loanID), body, map[string]string{"command": command})
	if err != nil {
		writeUpstreamLoanError(w, raw, command+" transaction", err)
		return
	}
	writeLoanEnvelope(w, raw)
}

// handleLoanDisburse completes the meeting-conduct loan-approval leg: APPROVE the still-pending
// loan (a member's loan-apply leaves it SUBMITTED) then DISBURSE it, both as real Fineract loan
// commands on /loans/{id}. Dates are backdated a few days to clear mifos-bank-2's behind-the-host
// business-date skew (approvedOnDate <= disbursementDate <= business date), same convention as
// group-create / client-activation. The approve amount is the loan's own principal.
func (h *Handler) handleLoanDisburse(w http.ResponseWriter, loanID int64, body map[string]interface{}) {
	lraw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("loans/%d", loanID), nil, nil)
	if err != nil {
		writeUpstreamLoanError(w, lraw, "read loan", err)
		return
	}
	var loan struct {
		Principal float64 `json:"principal"`
		Status    struct {
			PendingApproval bool `json:"pendingApproval"`
		} `json:"status"`
	}
	_ = json.Unmarshal(lraw, &loan)

	amount := loan.Principal
	if v, ok := body["transactionAmount"].(float64); ok && v > 0 {
		amount = v
	}
	date := time.Now().AddDate(0, 0, -3).Format("02 January 2006") // backdated, dd MMMM yyyy

	// 1. Approve the submitted loan (idempotent-ish: skip when already past pending-approval).
	if loan.Status.PendingApproval {
		appr := map[string]interface{}{
			"approvedOnDate":           date,
			"approvedLoanAmount":       amount,
			"expectedDisbursementDate": date,
			"locale":                   "en",
			"dateFormat":               "dd MMMM yyyy",
		}
		if araw, aerr := h.Fineract.DoRequest("POST", fmt.Sprintf("loans/%d", loanID), appr, map[string]string{"command": "approve"}); aerr != nil {
			writeUpstreamLoanError(w, araw, "approve loan", aerr)
			return
		}
	}

	// 2. Disburse to the member.
	disb := map[string]interface{}{
		"actualDisbursementDate": date,
		"transactionAmount":      amount,
		"locale":                 "en",
		"dateFormat":             "dd MMMM yyyy",
	}
	raw, derr := h.Fineract.DoRequest("POST", fmt.Sprintf("loans/%d", loanID), disb, map[string]string{"command": "disburse"})
	if derr != nil {
		writeUpstreamLoanError(w, raw, "disburse loan", derr)
		return
	}
	writeLoanEnvelope(w, raw)
}

// ---- helpers ----

// refID returns the lookup object's Fineract enum id, or the supplied default when the caller
// sent no object / no id (the app's declared per-field default, which equals the real Fineract id).
func refID(ref statusRefIn, def int) int {
	if ref.ID != nil {
		return *ref.ID
	}
	return def
}

// writeLoanEnvelope reshapes Fineract's command-processing response into the app's 4-field
// {officeId, clientId, loanId, resourceId} loan-write envelope. Fineract returns those fields on
// every loan create/transaction command (alongside a `changes` map this facade drops).
func writeLoanEnvelope(w http.ResponseWriter, raw []byte) {
	var resp loanWriteResponseDto
	if err := json.Unmarshal(raw, &resp); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "decode loan-write response: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(resp)
}

// writeUpstreamLoanError mirrors a Fineract write failure. DoRequest returns the raw upstream body
// (Fineract's structured error envelope) alongside its "API Error NNN" error; we surface that body
// with its status when present so the app sees the real validation message, defaulting to 502.
func writeUpstreamLoanError(w http.ResponseWriter, raw []byte, op string, err error) {
	status := http.StatusBadGateway
	if code := statusFromAPIError(err); code >= 400 {
		status = code
	}
	if len(raw) > 0 {
		w.WriteHeader(status)
		_, _ = w.Write(raw)
		return
	}
	writeErr(w, status, "upstream_error", op+": "+err.Error())
}

// statusFromAPIError extracts the HTTP status embedded in adapter.DoRequest's
// "API Error <code>" error string (it returns the raw body + this error on any >=400).
func statusFromAPIError(err error) int {
	if err == nil {
		return 0
	}
	var code int
	if _, e := fmt.Sscanf(err.Error(), "API Error %d", &code); e == nil {
		return code
	}
	return 0
}
