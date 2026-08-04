// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOAN-APPLY reads: the loan-apply screen's 5-way template combine. The CommonPurse app's
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

// appCurrencyCode is the CommonPurse app's fixed working currency. Loan-product options are
// filtered to it so only the provisioned VSLA products (KES) reach the loan-apply picker.
const appCurrencyCode = "KES"

func (h *Handler) registerLoanProductRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /loanproducts", h.HandleLoanProducts)
	mux.HandleFunc("GET /loans/template", h.HandleLoanTemplate)
	mux.HandleFunc("GET /clients/{clientId}/accounts", h.HandleClientAccounts)
	mux.HandleFunc("GET /datatables/dt_group_config/{groupId}", h.HandleGroupConfigDatatable)
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

// HandleGroupConfigDatatable proxies GET /datatables/dt_group_config/{groupId}. An unprovisioned
// or empty config degrades to [] (the app defaults the loan multiplier) rather than failing the
// loan-apply combine.
func (h *Handler) HandleGroupConfigDatatable(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/dt_group_config/%d", groupID), nil, nil)
	if err != nil {
		_, _ = w.Write([]byte("[]"))
		return
	}
	_, _ = w.Write(raw)
}
