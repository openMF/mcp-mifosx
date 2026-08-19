// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOANLIST: the loan-list read facade. It reshapes the real Fineract per-member loan accounts
// (GET clients/{id}/accounts + loans/{id}?associations=repaymentSchedule) into the offset-paginated
// contract the MifosSave app's LoanApiImpl fans in:
//
//	GET /groups/{groupId}/loans   -> LoanPageDto                 (the EXACT path + DTO the app calls)
//	GET /clients/{clientId}/loans -> []LoanSummaryDto            (LoanApi.getClientLoans reuse, same row shape)
//
// Source of truth for the wire shapes is the app's OWN approved contract:
//   - service : core/network/.../service/loanlist/LoanApiImpl.kt   (paths + params limit/offset/loanStatus)
//   - model   : core/network/.../model/LoanSummaryDto.kt           (LoanPageDto / LoanSummaryDto / LoanAccountStatusDto)
//   - mapper  : core/network/.../mapper/LoanSummaryMappers.kt      (field-by-field, no unmapped field)
//
// DATE-FORMAT decision (per LoanSummaryMappers.kt):
//   - LoanSummaryDto.nextRepaymentDate -> pass-through String? (domain LoanSummary.nextRepaymentDate
//     is String?, never parsed downstream) => emit "YYYY-MM-DD" (fmtFineractDate). NOT an Instant field.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// registerLoanListRoutes wires the COMP-LOANLIST endpoints. Called from RegisterRoutes.
func (h *Handler) registerLoanListRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /groups/{groupId}/loans", h.HandleGroupLoans)
	mux.HandleFunc("GET /clients/{clientId}/loans", h.HandleClientLoans)
}

// ---- App-facing wire contract (exact match to core/network/.../model/LoanSummaryDto.kt) ----

// LoanSummaryDto == one row of LoanPageDto.pageItems (GET /groups/{groupId}/loans).
type LoanSummaryDto struct {
	ID               int64   `json:"id"`
	MemberID         int64   `json:"memberId"`
	MemberName       string  `json:"memberName"`
	MemberPhotoURL   *string `json:"memberPhotoUrl"`
	LoanProductName  string  `json:"loanProductName"`
	PrincipalAmount  float64 `json:"principalAmount"`
	OutstandingBalance float64 `json:"outstandingBalance"`
	OverdueAmount    float64 `json:"overdueAmount"`
	Status           string  `json:"status"`
	NextRepaymentDate *string `json:"nextRepaymentDate"`
	IsOverdue        bool    `json:"isOverdue"`
	FineractLoanID   int64   `json:"fineractLoanId"`
}

// LoanPageDto == GET /groups/{groupId}/loans (offset-paginated envelope, page_size=20).
type LoanPageDto struct {
	TotalFilteredRecords int              `json:"totalFilteredRecords"`
	PageItems            []LoanSummaryDto `json:"pageItems"`
}

// ---- Fineract wire types (only the fields we consume) ----

type fnLoanAccountFull struct {
	ID           int64    `json:"id"`
	ProductName  string   `json:"productName"`
	Status       fnStatus `json:"status"`
	InArrears    bool     `json:"inArrears"`
	OriginalLoan float64  `json:"originalLoan"`
	LoanBalance  float64  `json:"loanBalance"`
}

type fnClientLoanAccounts struct {
	LoanAccounts []fnLoanAccountFull `json:"loanAccounts"`
}

// fnLoanDetail is the loans/{id}?associations=repaymentSchedule read — used only to enrich a row
// with the REAL next-due date + overdue amount the account summary does not carry.
type fnLoanDetail struct {
	Summary struct {
		TotalOverdue     float64 `json:"totalOverdue"`
		PrincipalOverdue float64 `json:"principalOverdue"`
	} `json:"summary"`
	RepaymentSchedule struct {
		Periods []struct {
			Period            *int    `json:"period"`
			DueDate           []int   `json:"dueDate"`
			Complete          bool    `json:"complete"`
			TotalDueForPeriod float64 `json:"totalDueForPeriod"`
		} `json:"periods"`
	} `json:"repaymentSchedule"`
}

// ---- Handlers ----

// HandleGroupLoans returns the group's loan accounts (one row per active member loan), aggregated
// from LIVE Fineract. limit/offset/loanStatus honored per the app's LoanApi contract.
func (h *Handler) HandleGroupLoans(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	limit := intParam(r, "limit", 20)
	offset := intParam(r, "offset", 0)
	statusFilter := strings.ToLower(strings.TrimSpace(r.URL.Query().Get("loanStatus")))

	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}

	all := make([]LoanSummaryDto, 0)
	for _, m := range members {
		all = append(all, h.memberLoans(m.ID, m.Name)...)
	}
	all = filterLoans(all, statusFilter)

	total := len(all)
	page := LoanPageDto{
		TotalFilteredRecords: total,
		PageItems:            pageSlice(all, offset, limit),
	}
	if page.PageItems == nil {
		page.PageItems = []LoanSummaryDto{}
	}
	_ = json.NewEncoder(w).Encode(page)
}

// HandleClientLoans returns a single client's loans as a flat []LoanSummaryDto (LoanApi.getClientLoans).
func (h *Handler) HandleClientLoans(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	raw := r.PathValue("clientId")
	cid, err := strconv.ParseInt(raw, 10, 64)
	if err != nil || cid <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid clientId")
		return
	}
	name := h.clientDisplayName(cid)
	loans := h.memberLoans(cid, name)
	if loans == nil {
		loans = []LoanSummaryDto{}
	}
	_ = json.NewEncoder(w).Encode(loans)
}

// ---- aggregation ----

// memberLoans reads a member's real Fineract loan accounts and maps each active loan to a
// LoanSummaryDto, enriching next-due date + overdue amount from the loan's repayment schedule.
func (h *Handler) memberLoans(clientID int64, memberName string) []LoanSummaryDto {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", clientID), nil, nil)
	if err != nil {
		return nil
	}
	var ca fnClientLoanAccounts
	if err := json.Unmarshal(raw, &ca); err != nil {
		return nil
	}
	out := make([]LoanSummaryDto, 0, len(ca.LoanAccounts))
	for _, l := range ca.LoanAccounts {
		nextDue, overdue := h.loanScheduleEnrichment(l.ID)
		out = append(out, LoanSummaryDto{
			ID:                 l.ID,
			MemberID:           clientID,
			MemberName:         memberName,
			MemberPhotoURL:     nil,
			LoanProductName:    l.ProductName,
			PrincipalAmount:    l.OriginalLoan,
			OutstandingBalance: l.LoanBalance,
			OverdueAmount:      overdue,
			Status:             loanStatusFor(l.Status, l.InArrears),
			NextRepaymentDate:  nextDue,
			IsOverdue:          l.InArrears,
			FineractLoanID:     l.ID,
		})
	}
	return out
}

// loanScheduleEnrichment fetches loans/{id}?associations=repaymentSchedule and returns the first
// unpaid period's due date ("YYYY-MM-DD", nil if none) plus the real total-overdue amount. Fail-soft:
// on any upstream error the row still renders with (nil, 0).
func (h *Handler) loanScheduleEnrichment(loanID int64) (*string, float64) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("loans/%d", loanID), nil, map[string]string{"associations": "repaymentSchedule"})
	if err != nil {
		return nil, 0
	}
	var d fnLoanDetail
	if err := json.Unmarshal(raw, &d); err != nil {
		return nil, 0
	}
	var next *string
	for _, p := range d.RepaymentSchedule.Periods {
		if p.Period == nil || p.Complete {
			continue // skip the disbursement row (period=null) and settled periods
		}
		s := fmtFineractDate(p.DueDate)
		if s != "" {
			next = &s
		}
		break
	}
	overdue := d.Summary.TotalOverdue
	if overdue == 0 {
		overdue = d.Summary.PrincipalOverdue
	}
	return next, overdue
}

// clientDisplayName resolves a client's display name for the flat /clients/{id}/loans read.
func (h *Handler) clientDisplayName(clientID int64) string {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d", clientID), nil, nil)
	if err != nil {
		return ""
	}
	var c struct {
		DisplayName string `json:"displayName"`
	}
	if err := json.Unmarshal(raw, &c); err != nil {
		return ""
	}
	return c.DisplayName
}

// ---- helpers ----

// loanStatusFor maps a Fineract loan status (+ arrears flag) to LoanSummaryDto.kt's
// LoanAccountStatusDto value-set (ACTIVE/OVERDUE/CLOSED/PENDING/REJECTED/UNKNOWN).
func loanStatusFor(s fnStatus, inArrears bool) string {
	if s.Active {
		if inArrears {
			return "OVERDUE"
		}
		return "ACTIVE"
	}
	v := strings.ToLower(s.Value)
	switch {
	case strings.Contains(v, "reject"):
		return "REJECTED"
	case strings.Contains(v, "close") || strings.Contains(v, "overpaid") || strings.Contains(v, "written"):
		return "CLOSED"
	case strings.Contains(v, "pending") || strings.Contains(v, "submitted") || strings.Contains(v, "approved") || strings.Contains(v, "waiting"):
		return "PENDING"
	default:
		return "UNKNOWN"
	}
}

// filterLoans applies the optional loanStatus query filter (active|overdue|closed|all).
func filterLoans(loans []LoanSummaryDto, filter string) []LoanSummaryDto {
	if filter == "" || filter == "all" {
		return loans
	}
	want := strings.ToUpper(filter)
	out := make([]LoanSummaryDto, 0, len(loans))
	for _, l := range loans {
		if l.Status == want {
			out = append(out, l)
		}
	}
	return out
}
