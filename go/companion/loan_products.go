// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOAN-APPLY reads: the loan-apply screen's 5-way template combine. The MifosSave app's
// LoanApplyApiImpl fetches, on mount / member-select, raw Fineract read paths this facade serves:
//
//	GET /loanproducts                          -> []LoanProductDto      (get_loan_products)
//	GET /loans/template?clientId&productId&…   -> LoanApplyTemplateDto  (get_loan_template)
//	GET /clients/{clientId}/accounts           -> member savings        (get_member_savings)
//	GET /datatables/dt_group_config/{groupId}  -> group config          (get_group_config)
//
// (the other two combine legs — dt_group_corpus + group members — are already served by meeting.go
// / groups.go). Each proxies to Fineract with the shared service credential (adapter.DoRequest,
// which sends Accept: application/json — a bare curl without it gets a 400 from this instance).
// The app DTOs are parsed with ignoreUnknownKeys, so the raw Fineract shapes deserialize directly.
//
// /loanproducts is FILTERED to the app currency (KES): mifos-bank-2 also carries legacy USD/MXN
// demo products, and the loan-apply picker must only offer the VSLA products provisioned by
// server-layer/migrations/register-products.sh. Without this facade the picker was empty and no
// loan could be submitted.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// appCurrencyCode is the MifosSave app's fixed working currency. Loan-product options are
// filtered to it so only the provisioned VSLA products (KES) reach the loan-apply picker.
const appCurrencyCode = "KES"

func (h *Handler) registerLoanProductRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /loanproducts", h.HandleLoanProducts)
	mux.HandleFunc("GET /loans/template", h.HandleLoanTemplate)
	mux.HandleFunc("GET /clients/{clientId}/accounts", h.HandleClientAccounts)
	mux.HandleFunc("GET /datatables/dt_group_config/{groupId}", h.HandleGroupConfigDatatable)
	mux.HandleFunc("POST /datatables/dt_loan_request", h.HandlePostLoanRequest)
}

// HandlePostLoanRequest writes the member's loan APPLICATION (a PENDING dt_loan_request row for
// organizer/group review at the next meeting — the VSLA request-then-approve model). The app's
// LoanRequestApiImpl POSTs the row to a bare /datatables/dt_loan_request with the target client
// carried IN THE BODY as clientId, but a Fineract datatable write needs the entity id in the PATH —
// so we lift clientId out to the path (/datatables/dt_loan_request/{clientId}) and forward the
// remaining snake_case columns (requested_amount/purpose/duration_weeks/savings_balance_at_request/
// submitted_at/status) as the row.
func (h *Handler) HandlePostLoanRequest(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var body map[string]interface{}
	if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	cidF, _ := body["clientId"].(float64)
	clientID := int64(cidF)
	if clientID == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "clientId is required")
		return
	}
	// Map the app's wire field names to the dt_loan_request datatable columns. The app's shape
	// (requested_amount / savings_balance_at_request / submitted_at) differs from the registered
	// columns (amount / requested_at / …), and savings_balance_at_request has NO column — a
	// straight pass-through 400s with "Column not exist in database". clientId is the entity FK
	// (path), never a body column.
	row := map[string]interface{}{"locale": "en"}
	if v, ok := body["requested_amount"]; ok {
		row["amount"] = v
	}
	if v, ok := body["purpose"]; ok {
		row["purpose"] = v
	}
	if v, ok := body["duration_weeks"]; ok {
		row["duration_weeks"] = v
	}
	if s, ok := body["status"].(string); ok && strings.TrimSpace(s) != "" {
		row["status"] = s
	} else {
		row["status"] = "PENDING"
	}
	// submitted_at (ISO-8601) → requested_at (Fineract TIMESTAMP "yyyy-MM-dd HH:mm:ss").
	if s, ok := body["submitted_at"].(string); ok && len(s) >= 19 {
		row["requested_at"] = strings.Replace(s[:19], "T", " ", 1)
		row["dateFormat"] = "yyyy-MM-dd HH:mm:ss"
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/dt_loan_request/%d", clientID), row, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "loan request: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	// Fineract's datatable-create envelope is {officeId, clientId, resourceId} — it omits
	// resourceExternalId, which the app's LoanRequestResponseDto requires (non-null). Inject an
	// empty one so the ONLINE submit deserializes instead of falling back to the offline queue.
	var resp map[string]interface{}
	if json.Unmarshal(raw, &resp) == nil {
		if _, ok := resp["resourceExternalId"]; !ok {
			resp["resourceExternalId"] = ""
		}
		_ = json.NewEncoder(w).Encode(resp)
		return
	}
	_, _ = w.Write(raw)
}

// HandleLoanProducts proxies GET /loanproducts, filtered to the app currency (KES).
func (h *Handler) HandleLoanProducts(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	raw, err := h.Fineract.DoRequest("GET", "loanproducts", nil, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "loan products: "+err.Error())
		return
	}
	var all []map[string]interface{}
	if json.Unmarshal(raw, &all) != nil {
		_, _ = w.Write(raw) // unexpected shape → pass through unfiltered rather than drop
		return
	}
	kes := make([]map[string]interface{}, 0, len(all))
	for _, p := range all {
		if cur, ok := p["currency"].(map[string]interface{}); ok {
			if code, _ := cur["code"].(string); code == appCurrencyCode {
				kes = append(kes, p)
			}
		}
	}
	_ = json.NewEncoder(w).Encode(kes)
}

// HandleLoanTemplate proxies GET /loans/template for the picked (clientId, productId), passing the
// app's query params through. templateType defaults to "individual" (a VSLA member borrows as an
// individual client under the group).
func (h *Handler) HandleLoanTemplate(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	q := map[string]string{}
	for _, k := range []string{"clientId", "productId", "groupId", "templateType", "activationDate", "staffId"} {
		if v := strings.TrimSpace(r.URL.Query().Get(k)); v != "" {
			q[k] = v
		}
	}
	if q["templateType"] == "" {
		q["templateType"] = "individual"
	}
	raw, err := h.Fineract.DoRequest("GET", "loans/template", nil, q)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "loan template: "+err.Error())
		return
	}
	_, _ = w.Write(raw)
}

// HandleClientAccounts proxies GET /clients/{clientId}/accounts — the member's savings (+ loan)
// accounts, from which the loan-apply screen derives the savings balance driving eligibility
// (max borrow = loanMultiplier × savings).
//
// ENRICHMENT: Fineract's /clients/{id}/accounts savings SUMMARY omits `accountBalance`, but the
// app's MemberSavingsAccountRowDto requires it (0 balance → 0 eligibility → no loan). So we fetch
// each savings account's real balance (GET /savingsaccounts/{id}#summary.accountBalance) and
// inject it, so the loan-apply eligibility reflects the member's actual savings.
func (h *Handler) HandleClientAccounts(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	clientID, ok := pathInt64Param(w, r, "clientId")
	if !ok {
		return
	}
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", clientID), nil, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "client accounts: "+err.Error())
		return
	}
	var doc map[string]interface{}
	if json.Unmarshal(raw, &doc) != nil {
		_, _ = w.Write(raw) // unexpected shape → pass through
		return
	}
	if sav, ok := doc["savingsAccounts"].([]interface{}); ok {
		for _, s := range sav {
			m, ok := s.(map[string]interface{})
			if !ok {
				continue
			}
			if _, has := m["accountBalance"]; has {
				continue
			}
			idf, _ := m["id"].(float64)
			id := int64(idf)
			if id == 0 {
				continue
			}
			if braw, berr := h.Fineract.DoRequest("GET", fmt.Sprintf("savingsaccounts/%d", id), nil, nil); berr == nil {
				var acc struct {
					Summary struct {
						AccountBalance float64 `json:"accountBalance"`
					} `json:"summary"`
				}
				if json.Unmarshal(braw, &acc) == nil {
					m["accountBalance"] = acc.Summary.AccountBalance
				}
			}
		}
	}
	_ = json.NewEncoder(w).Encode(doc)
}

// HandleGroupConfigDatatable serves GET /datatables/dt_group_config/{groupId} as the app's
// GroupLoanConfigDto OBJECT ({loan_multiplier, max_loan_amount, meeting_frequency}). Fineract returns
// the datatable as an ARRAY of rows and carries no max_loan_amount column, so this unwraps the first
// row (like HandleGroupCorpusRead) and DERIVES max_loan_amount = contribution_amount × loan_multiplier
// × cycle_length_weeks (a full cycle of contributions, leveraged by the multiplier — the standard VSLA
// borrowing cap). An unprovisioned/empty config degrades to {} so the app defaults, not the array
// `[` that broke GroupLoanConfigDto deserialization ("Expected '{' but had '['").
func (h *Handler) HandleGroupConfigDatatable(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/dt_group_config/%d", groupID), nil, nil)
	if err != nil {
		_, _ = w.Write([]byte("{}"))
		return
	}
	var rows []struct {
		LoanMultiplier     float64 `json:"loan_multiplier"`
		ContributionAmount float64 `json:"contribution_amount"`
		CycleLengthWeeks   float64 `json:"cycle_length_weeks"`
		MeetingFrequency   string  `json:"meeting_frequency"`
	}
	if json.Unmarshal(raw, &rows) != nil || len(rows) == 0 {
		_, _ = w.Write([]byte("{}"))
		return
	}
	row := rows[0]
	weeks := row.CycleLengthWeeks
	if weeks <= 0 {
		weeks = 52
	}
	maxLoan := row.ContributionAmount * row.LoanMultiplier * weeks
	_ = json.NewEncoder(w).Encode(map[string]interface{}{
		"loan_multiplier":   row.LoanMultiplier,
		"max_loan_amount":   maxLoan,
		"meeting_frequency": row.MeetingFrequency,
	})
}
