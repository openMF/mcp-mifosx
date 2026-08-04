// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-LOANDETAIL: the loan-detail read facade. The MifosSave
// (mifos-x-group-banking) app's LoanDetailApiImpl calls a single composite read
// at a bare Fineract path (its base URL points at THIS Go server):
//
//	GET /loans/{loanId}?associations=repaymentSchedule,transactions -> LoanDetailResponseDto
//
// Source of truth for the wire shape is the app's OWN approved contract:
//   - service : loandetail/LoanDetailApiImpl.kt   (GET /loans/{loanId}?associations=...)
//   - model   : LoanDetailDto.kt                  (LoanDetailResponseDto / LoanDetailDto /
//               RepaymentScheduleRowDto / RepaymentTransactionDto)
//   - mapper  : LoanDetailMappers.kt              (field-by-field, no unmapped field)
//
// The app DTO is the COMPANION-NORMALIZED shape (a flat `loan` object + typed
// repaymentSchedule/transactions rows), explicitly NOT the raw literal Fineract
// loan payload (which nests `summary`, status/type as {id,value} pairs, dates as
// [y,m,d] arrays, and buries the schedule under repaymentSchedule.periods). This
// handler performs exactly that normalization the DTO kdoc says "the companion
// bridge" is responsible for.
//
// Reads use the shared FineractClient service credential (adapter.DoRequest),
// same as every other companion read facade. Reuses fnStatus + loanStatusFor
// (loan_list.go) for the loan-status mapping and fmtFineractDate (groups.go) for
// every [y,m,d] -> "YYYY-MM-DD" conversion.
//
// DATE-FORMAT decision: LoanDetailDto.disbursedDate / RepaymentScheduleRowDto.dueDate /
// RepaymentTransactionDto.date are all plain Kotlin String (never Instant.parse'd downstream —
// LoanDetailMappers.kt passes them straight through), so "YYYY-MM-DD" (fmtFineractDate) is emitted,
// matching the loan-list precedent (LoanSummaryDto.nextRepaymentDate).
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"time"
)

// registerLoanDetailRoutes wires the COMP-LOANDETAIL endpoint. Called from
// RegisterRoutes. GET /loans/{loanId} is disjoint from the POST /loans and
// POST /loans/{loanId}/transactions write routes (loan_write.go) and from
// loan_list.go's group/client loan-list reads.
func (h *Handler) registerLoanDetailRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /loans/{loanId}", h.HandleLoanDetail)
}

// ---- App-facing wire contract (exact match to LoanDetailDto.kt) ----

// loanDetailInnerDto == LoanDetailDto (the flat `loan` header).
type loanDetailInnerDto struct {
	ID                  int64   `json:"id"`
	MemberID            int64   `json:"memberId"`
	MemberName          string  `json:"memberName"`
	LoanProductName     string  `json:"loanProductName"`
	PrincipalAmount     float64 `json:"principalAmount"`
	DisbursedDate       string  `json:"disbursedDate"`
	InterestRatePercent float64 `json:"interestRatePercent"`
	TotalOutstanding    float64 `json:"totalOutstanding"`
	TotalOverdue        float64 `json:"totalOverdue"`
	Status              string  `json:"status"`
	FineractLoanID      int64   `json:"fineractLoanId"`
}

// repaymentScheduleRowDto == RepaymentScheduleRowDto (one installment period).
type repaymentScheduleRowDto struct {
	WeekNumber int     `json:"weekNumber"`
	DueDate    string  `json:"dueDate"`
	DueAmount  float64 `json:"dueAmount"`
	PaidAmount float64 `json:"paidAmount"`
	Balance    float64 `json:"balance"`
	Status     string  `json:"status"`
}

// repaymentTransactionDto == RepaymentTransactionDto (one posted transaction).
type repaymentTransactionDto struct {
	ID     int64   `json:"id"`
	Type   string  `json:"type"`
	Date   string  `json:"date"`
	Amount float64 `json:"amount"`
}

// loanDetailResponseDto == LoanDetailResponseDto (the composite envelope).
type loanDetailResponseDto struct {
	Loan              loanDetailInnerDto        `json:"loan"`
	RepaymentSchedule []repaymentScheduleRowDto `json:"repaymentSchedule"`
	Transactions      []repaymentTransactionDto `json:"transactions"`
}

// ---- Fineract wire types (only the fields we consume) ----

type fnLoanTimeline struct {
	ActualDisbursementDate   []int `json:"actualDisbursementDate"`
	ExpectedDisbursementDate []int `json:"expectedDisbursementDate"`
}

type fnLoanSummary struct {
	TotalOutstanding float64 `json:"totalOutstanding"`
	TotalOverdue     float64 `json:"totalOverdue"`
}

type fnLoanPeriod struct {
	Period                          *int    `json:"period"`
	DueDate                         []int   `json:"dueDate"`
	Complete                        bool    `json:"complete"`
	TotalDueForPeriod               float64 `json:"totalDueForPeriod"`
	TotalPaidForPeriod              float64 `json:"totalPaidForPeriod"`
	TotalOutstandingForPeriod       float64 `json:"totalOutstandingForPeriod"`
	PrincipalLoanBalanceOutstanding float64 `json:"principalLoanBalanceOutstanding"`
}

type fnLoanTransaction struct {
	ID     int64 `json:"id"`
	Type   struct {
		Value string `json:"value"`
	} `json:"type"`
	Date   []int   `json:"date"`
	Amount float64 `json:"amount"`
}

type fnLoanDetailRaw struct {
	ID                    int64          `json:"id"`
	ClientID              int64          `json:"clientId"`
	ClientName            string         `json:"clientName"`
	LoanProductName       string         `json:"loanProductName"`
	Principal             float64        `json:"principal"`
	InterestRatePerPeriod float64        `json:"interestRatePerPeriod"`
	InArrears             bool           `json:"inArrears"`
	Status                fnStatus       `json:"status"`
	Timeline              fnLoanTimeline `json:"timeline"`
	Summary               fnLoanSummary  `json:"summary"`
	RepaymentSchedule     struct {
		Periods []fnLoanPeriod `json:"periods"`
	} `json:"repaymentSchedule"`
	Transactions []fnLoanTransaction `json:"transactions"`
}

// ---- Handler ----

// HandleLoanDetail reads the LIVE Fineract loan and reshapes it into LoanDetailResponseDto.
func (h *Handler) HandleLoanDetail(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	loanID, ok := loanIDParam(w, r)
	if !ok {
		return
	}
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("loans/%d", loanID), nil, map[string]string{"associations": "repaymentSchedule,transactions"})
	if err != nil {
		writeUpstreamLoanError(w, raw, "get loan detail", err)
		return
	}
	var l fnLoanDetailRaw
	if err := json.Unmarshal(raw, &l); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "decode loan detail: "+err.Error())
		return
	}

	disbursed := fmtFineractDate(l.Timeline.ActualDisbursementDate)
	if disbursed == "" {
		disbursed = fmtFineractDate(l.Timeline.ExpectedDisbursementDate)
	}

	out := loanDetailResponseDto{
		Loan: loanDetailInnerDto{
			ID:                  l.ID,
			MemberID:            l.ClientID,
			MemberName:          l.ClientName,
			LoanProductName:     l.LoanProductName,
			PrincipalAmount:     l.Principal,
			DisbursedDate:       disbursed,
			InterestRatePercent: l.InterestRatePerPeriod,
			TotalOutstanding:    l.Summary.TotalOutstanding,
			TotalOverdue:        l.Summary.TotalOverdue,
			Status:              loanStatusFor(l.Status, l.InArrears),
			FineractLoanID:      l.ID,
		},
		RepaymentSchedule: buildSchedule(l.RepaymentSchedule.Periods),
		Transactions:      buildLoanTransactions(l.Transactions),
	}
	_ = json.NewEncoder(w).Encode(out)
}

// ---- reshape helpers ----

// buildSchedule maps Fineract repayment-schedule periods to the app rows, skipping the
// disbursement row (period == null / <= 0). balance = principalLoanBalanceOutstanding (the
// declining loan balance after the installment). Row status is derived from complete /
// partial-payment / past-due against today.
func buildSchedule(periods []fnLoanPeriod) []repaymentScheduleRowDto {
	today := time.Now().UTC().Format("2006-01-02")
	out := make([]repaymentScheduleRowDto, 0, len(periods))
	for _, p := range periods {
		if p.Period == nil || *p.Period <= 0 {
			continue // disbursement row — not an installment.
		}
		due := fmtFineractDate(p.DueDate)
		out = append(out, repaymentScheduleRowDto{
			WeekNumber: *p.Period,
			DueDate:    due,
			DueAmount:  p.TotalDueForPeriod,
			PaidAmount: p.TotalPaidForPeriod,
			Balance:    p.PrincipalLoanBalanceOutstanding,
			Status:     scheduleRowStatus(p, due, today),
		})
	}
	return out
}

// scheduleRowStatus maps one period to RepaymentRowStatusDto's value-set
// (PAID / PARTIAL / UPCOMING / OVERDUE). ISO "YYYY-MM-DD" strings sort lexicographically, so a
// direct string compare against today is a valid date comparison.
func scheduleRowStatus(p fnLoanPeriod, dueDate, today string) string {
	if p.Complete {
		return "PAID"
	}
	if p.TotalPaidForPeriod > 0 {
		return "PARTIAL"
	}
	if dueDate != "" && dueDate < today {
		return "OVERDUE"
	}
	return "UPCOMING"
}

// buildLoanTransactions maps Fineract loan transactions to the app rows (type = the human-readable
// transaction-type value, e.g. "Disbursement" / "Repayment" / "Write-off").
func buildLoanTransactions(txns []fnLoanTransaction) []repaymentTransactionDto {
	out := make([]repaymentTransactionDto, 0, len(txns))
	for _, t := range txns {
		out = append(out, repaymentTransactionDto{
			ID:     t.ID,
			Type:   t.Type.Value,
			Date:   fmtFineractDate(t.Date),
			Amount: t.Amount,
		})
	}
	return out
}

// loanIDParam parses the {loanId} path segment shared by the loan-detail read and the
// loan-transaction write routes.
func loanIDParam(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("loanId"), 10, 64)
	if err != nil || id <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid loanId")
		return 0, false
	}
	return id, true
}
