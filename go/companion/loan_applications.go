// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOAN-REVIEW: the group loan-REVIEW facade for the meeting-conduct step-5 "loan applications"
// panel + the approve→disburse action. The VSLA model is request-then-approve: a member files a
// PENDING dt_loan_request row (loan_products.go HandlePostLoanRequest); the organizer reviews the
// group's pending requests at the next meeting, votes, and approves — which must MATERIALISE the
// request into a real Fineract loan (create → approve → disburse). Two routes:
//
//	GET  /companion/groups/{groupId}/loan-requests      -> []loanApplicationDto   (pending review list)
//	POST /companion/loan-applications/{clientId}/disburse { amount } -> {loanId}  (approve+disburse)
//
// Before this, MeetingConductRepositoryImpl.loadMeetingData hard-coded pendingLoanApplications =
// emptyList() (the CFF1 gap — no api.yaml endpoint loaded it), so the review step was always empty
// and the disburse branch dead. These routes close the loan lifecycle end-to-end.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
	"time"
)

func (h *Handler) registerLoanApplicationRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/groups/{groupId}/loan-requests", h.HandleGroupLoanRequests)
	mux.HandleFunc("POST /companion/loan-applications/{clientId}/disburse", h.HandleDisburseLoanApplication)
}

// loanApplicationDto == the app's LoanApplication domain shape (core/model meeting.LoanApplication).
// id == clientId (string) so the disburse action can resolve the borrower from the application id.
type loanApplicationDto struct {
	ID              string `json:"id"`
	MemberID        string `json:"memberId"`
	MemberName      string `json:"memberName"`
	RequestedAmount int64  `json:"requestedAmount"`
	Purpose         string `json:"purpose"`
}

// HandleGroupLoanRequests returns every PENDING dt_loan_request row across the group's clients,
// projected to the app's LoanApplication shape for the meeting-conduct review step. A client with
// no request (404 on the datatable read) contributes nothing; the group simply shows fewer rows.
func (h *Handler) HandleGroupLoanRequests(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	clients, err := h.groupClients(groupID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "group clients: "+err.Error())
		return
	}
	out := make([]loanApplicationDto, 0, len(clients))
	for _, c := range clients {
		rows := h.pendingLoanRequestRows(c.ID)
		for _, row := range rows {
			out = append(out, loanApplicationDto{
				ID:              fmt.Sprintf("%d", c.ID),
				MemberID:        fmt.Sprintf("%d", c.ID),
				MemberName:      c.Name,
				RequestedAmount: row.amount,
				Purpose:         row.purpose,
			})
		}
	}
	_ = json.NewEncoder(w).Encode(out)
}

// loanRequestRow is a decoded dt_loan_request row (the columns HandlePostLoanRequest writes).
// id is the datatable row's own id (dt_loan_request is multi-row per client) — needed to mark a
// specific request APPROVED without touching the client's other requests.
type loanRequestRow struct {
	id       int64
	amount   int64
	purpose  string
	status   string
	duration int
}

// pendingLoanRequestRows reads dt_loan_request/{clientId} and returns the PENDING rows. Fineract
// returns datatable rows as an array of column maps; a 404 / non-array degrades to no rows.
func (h *Handler) pendingLoanRequestRows(clientID int64) []loanRequestRow {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/dt_loan_request/%d", clientID), nil, nil)
	if err != nil {
		return nil
	}
	var rows []map[string]interface{}
	if json.Unmarshal(raw, &rows) != nil {
		return nil
	}
	out := make([]loanRequestRow, 0, len(rows))
	for _, m := range rows {
		status, _ := m["status"].(string)
		if !strings.EqualFold(strings.TrimSpace(status), "PENDING") {
			continue
		}
		amt, _ := m["amount"].(float64)
		dur, _ := m["duration_weeks"].(float64)
		purpose, _ := m["purpose"].(string)
		rowID, _ := m["id"].(float64)
		out = append(out, loanRequestRow{
			id:       int64(rowID),
			amount:   int64(amt),
			purpose:  purpose,
			status:   status,
			duration: int(dur),
		})
	}
	return out
}

// HandleDisburseLoanApplication materialises an approved loan request into a live Fineract loan:
// create (from the KES VSLA product template) -> approve (backdated) -> disburse, then marks the
// dt_loan_request row APPROVED. Idempotent-ish: a client with no PENDING request is a 404.
func (h *Handler) HandleDisburseLoanApplication(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	clientID, ok := pathInt64Param(w, r, "clientId")
	if !ok {
		return
	}
	var body struct {
		Amount float64 `json:"amount"`
	}
	_ = json.NewDecoder(r.Body).Decode(&body)

	pending := h.pendingLoanRequestRows(clientID)
	if len(pending) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", fmt.Sprintf("no pending loan request for client %d", clientID))
		return
	}
	req := pending[0]
	amount := body.Amount
	if amount <= 0 {
		amount = float64(req.amount)
	}
	weeks := req.duration
	if weeks <= 0 {
		weeks = 12
	}

	productID, terms, perr := h.resolveKesLoanProduct(clientID)
	if perr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "resolve loan product: "+perr.Error())
		return
	}

	// Backdate 3 days so approve/disburse clear the Fineract business-date skew + client-activation
	// floor (same convention as loan_write.go handleLoanDisburse).
	date := time.Now().AddDate(0, 0, -3).Format("02 January 2006")
	loanID, cerr := h.createApproveDisburseLoan(clientID, productID, amount, weeks, date, terms)
	if cerr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create/disburse loan: "+cerr.Error())
		return
	}

	// Best-effort: stamp THIS request row APPROVED so it drops out of the pending list (and can't be
	// re-disbursed). dt_loan_request is multi-row per client, so the update needs the row id
	// (PUT /datatables/{table}/{clientId}/{rowId}) — a client-only PUT is ambiguous.
	if req.id > 0 {
		if _, merr := h.Fineract.DoRequest("PUT", fmt.Sprintf("datatables/dt_loan_request/%d/%d", clientID, req.id),
			map[string]interface{}{"status": "APPROVED", "locale": "en"}, nil); merr != nil {
			_ = merr // non-fatal: the loan is live; the row-status update is bookkeeping.
		}
	}

	_ = json.NewEncoder(w).Encode(map[string]interface{}{"loanId": loanID, "resourceId": loanID})
}

// loanProductTerms carries the product-template defaults the loan-create needs.
type loanProductTerms struct {
	interestRatePerPeriod         float64
	repaymentEvery                int
	repaymentFrequencyType        int
	loanTermFrequencyType         int
	amortizationType              int
	interestType                  int
	interestCalculationPeriodType int
	transactionProcessingStrategy string
}

// resolveKesLoanProduct picks the first KES loan product and pulls its default terms from the
// loan template for (clientId, productId) so the create matches the product's own configuration.
func (h *Handler) resolveKesLoanProduct(clientID int64) (int64, loanProductTerms, error) {
	raw, err := h.Fineract.DoRequest("GET", "loanproducts", nil, nil)
	if err != nil {
		return 0, loanProductTerms{}, fmt.Errorf("list products: %s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var products []map[string]interface{}
	if json.Unmarshal(raw, &products) != nil {
		return 0, loanProductTerms{}, fmt.Errorf("decode products")
	}
	var productID int64
	for _, p := range products {
		cur, _ := p["currency"].(map[string]interface{})
		if code, _ := cur["code"].(string); code == appCurrencyCode {
			if idf, ok := p["id"].(float64); ok {
				productID = int64(idf)
				break
			}
		}
	}
	if productID == 0 {
		return 0, loanProductTerms{}, fmt.Errorf("no %s loan product provisioned", appCurrencyCode)
	}

	// Defaults (VSLA weekly), overridden by the product template where present.
	terms := loanProductTerms{
		interestRatePerPeriod:         2,
		repaymentEvery:                1,
		repaymentFrequencyType:        2, // 2 = months (Fineract default period unit)
		loanTermFrequencyType:         2,
		amortizationType:              1, // equal installments
		interestType:                  0, // declining balance
		interestCalculationPeriodType: 1,
		transactionProcessingStrategy: "mifos-standard-strategy",
	}
	traw, terr := h.Fineract.DoRequest("GET", "loans/template", nil, map[string]string{
		"clientId":     fmt.Sprintf("%d", clientID),
		"productId":    fmt.Sprintf("%d", productID),
		"templateType": "individual",
	})
	if terr == nil {
		var t map[string]interface{}
		if json.Unmarshal(traw, &t) == nil {
			if v, ok := t["interestRatePerPeriod"].(float64); ok {
				terms.interestRatePerPeriod = v
			}
			if v, ok := t["repaymentEvery"].(float64); ok {
				terms.repaymentEvery = int(v)
			}
			if strat, ok := t["transactionProcessingStrategyCode"].(string); ok && strat != "" {
				terms.transactionProcessingStrategy = strat
			}
			if rf, ok := t["repaymentFrequencyType"].(map[string]interface{}); ok {
				if id, ok := rf["id"].(float64); ok {
					terms.repaymentFrequencyType = int(id)
					terms.loanTermFrequencyType = int(id)
				}
			}
			if am, ok := t["amortizationType"].(map[string]interface{}); ok {
				if id, ok := am["id"].(float64); ok {
					terms.amortizationType = int(id)
				}
			}
			if it, ok := t["interestType"].(map[string]interface{}); ok {
				if id, ok := it["id"].(float64); ok {
					terms.interestType = int(id)
				}
			}
			if ic, ok := t["interestCalculationPeriodType"].(map[string]interface{}); ok {
				if id, ok := ic["id"].(float64); ok {
					terms.interestCalculationPeriodType = int(id)
				}
			}
		}
	}
	return productID, terms, nil
}

// createApproveDisburseLoan runs the full loan materialisation: POST /loans (individual, from the
// product terms) -> POST /loans/{id}?command=approve -> POST /loans/{id}?command=disburse, all
// backdated to `date`. numberOfRepayments derives from the requested week count / period unit.
func (h *Handler) createApproveDisburseLoan(clientID, productID int64, amount float64, weeks int, date string, terms loanProductTerms) (int64, error) {
	// Map the requested duration (weeks) onto the product's repayment period unit. For a monthly
	// product (repaymentFrequencyType 2) approximate 4 weeks/month, min 1; for weekly, use weeks.
	numRepayments := weeks
	loanTerm := weeks
	if terms.repaymentFrequencyType == 2 { // months
		numRepayments = (weeks + 3) / 4
		if numRepayments < 1 {
			numRepayments = 1
		}
		loanTerm = numRepayments
	}
	create := map[string]interface{}{
		"clientId":                          clientID,
		"productId":                         productID,
		"principal":                         amount,
		"loanType":                          "individual",
		"loanTermFrequency":                 loanTerm,
		"loanTermFrequencyType":             terms.loanTermFrequencyType,
		"numberOfRepayments":                numRepayments,
		"repaymentEvery":                    terms.repaymentEvery,
		"repaymentFrequencyType":            terms.repaymentFrequencyType,
		"interestRatePerPeriod":             terms.interestRatePerPeriod,
		"amortizationType":                  terms.amortizationType,
		"interestType":                      terms.interestType,
		"interestCalculationPeriodType":     terms.interestCalculationPeriodType,
		"transactionProcessingStrategyCode": terms.transactionProcessingStrategy,
		"expectedDisbursementDate":          date,
		"submittedOnDate":                   date,
		"dateFormat":                        "dd MMMM yyyy",
		"locale":                            "en",
	}
	raw, err := h.Fineract.DoRequest("POST", "loans", create, nil)
	if err != nil {
		return 0, fmt.Errorf("create: %s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var created struct {
		LoanID     int64 `json:"loanId"`
		ResourceID int64 `json:"resourceId"`
	}
	if json.Unmarshal(raw, &created) != nil {
		return 0, fmt.Errorf("create: unexpected response")
	}
	loanID := created.ResourceID
	if loanID == 0 {
		loanID = created.LoanID
	}
	if loanID == 0 {
		return 0, fmt.Errorf("create: no loanId in response")
	}

	// approve then disburse, both backdated.
	approveBody := map[string]interface{}{"approvedOnDate": date, "dateFormat": "dd MMMM yyyy", "locale": "en"}
	if araw, aerr := h.Fineract.DoRequest("POST", fmt.Sprintf("loans/%d", loanID), approveBody, map[string]string{"command": "approve"}); aerr != nil {
		return loanID, fmt.Errorf("approve: %s", strings.TrimSpace(string(nonEmpty(araw))))
	}
	disburseBody := map[string]interface{}{
		"actualDisbursementDate": date, "transactionAmount": amount,
		"dateFormat": "dd MMMM yyyy", "locale": "en",
	}
	if draw, derr := h.Fineract.DoRequest("POST", fmt.Sprintf("loans/%d", loanID), disburseBody, map[string]string{"command": "disburse"}); derr != nil {
		return loanID, fmt.Errorf("disburse: %s", strings.TrimSpace(string(nonEmpty(draw))))
	}
	return loanID, nil
}
