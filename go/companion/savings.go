// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-SAVINGS: the savings-dashboard + member-savings-detail read facade. It reshapes the real
// Fineract group corpus (GET groups/{id}/accounts + savingsaccounts/{id}?associations=transactions)
// into the three companion contracts the MifosSave app's SavingsApiImpl calls:
//
//	GET /companion/groups/{groupId}/savings                          -> GroupSavingsSummaryDto
//	GET /companion/groups/{groupId}/savings/individual              -> IndividualSavingsSummaryDto
//	GET /companion/groups/{groupId}/members/{memberId}/savings      -> MemberSavingsDetailDto
//
// Source of truth for the wire shapes is the app's OWN approved contract:
//   - service : core/network/.../service/savings/SavingsApiImpl.kt
//   - model   : core/network/.../model/SavingsDto.kt
//   - mapper  : core/network/.../mapper/SavingsMappers.kt
//
// DATE-FORMAT decision (per the app's mapper — SavingsMappers.kt):
//   - SavingsStatementEntryDto.date       -> LocalDate.parse(date)                 => emit "YYYY-MM-DD" (fmtFineractDate)
//   - MemberIndividualSavingsRowDto.lastTransactionDate -> LocalDate.parse(it)     => emit "YYYY-MM-DD" (fmtFineractDate)
//   - SavingsDataPointDto.date            -> pass-through String (not parsed)       => emit "YYYY-MM-DD" (safe, consistent)
// NONE of these use kotlinx Instant.parse, so NO full-instant field appears on these endpoints.
// (WeeklyContributionPointDto.weekLabel is a display label, not a date; every *Contributed /
// *Balance / lastContribution / lastTransaction field is a Long amount, not a date.)
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
)

// registerSavingsRoutes wires the COMP-SAVINGS endpoints. Called from RegisterRoutes.
func (h *Handler) registerSavingsRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/groups/{groupId}/savings", h.HandleGroupSavings)
	mux.HandleFunc("GET /companion/groups/{groupId}/savings/individual", h.HandleIndividualSavings)
	mux.HandleFunc("GET /companion/groups/{groupId}/members/{memberId}/savings", h.HandleMemberSavingsDetail)
}

// ---- App-facing wire contract (exact match to core/network/.../model/SavingsDto.kt) ----

// WeeklyContributionPointDto == one point on the group/individual contribution trend chart.
type WeeklyContributionPointDto struct {
	WeekLabel        string `json:"weekLabel"`
	GroupAmount      int64  `json:"groupAmount"`
	IndividualAmount int64  `json:"individualAmount"`
}

// MemberGroupSavingsRowDto == one member row on GroupSavingsSummaryDto.memberRows.
// sharesHeld/shareValue populated only for SHARE_BASED_VARIABLE groups; null for FIXED_AMOUNT.
type MemberGroupSavingsRowDto struct {
	MemberID            string `json:"memberId"`
	Name                string `json:"name"`
	TotalContributed    int64  `json:"totalContributed"`
	LastContribution    int64  `json:"lastContribution"`
	MeetingsContributed int    `json:"meetingsContributed"`
	SharesHeld          *int   `json:"sharesHeld"`
	ShareValue          *int64 `json:"shareValue"`
}

// GroupSavingsSummaryDto == GET /companion/groups/{groupId}/savings.
type GroupSavingsSummaryDto struct {
	CycleTarget    int64                        `json:"cycleTarget"`
	CycleCollected int64                        `json:"cycleCollected"`
	TotalCollected int64                        `json:"totalCollected"`
	WeeklyTrend    []WeeklyContributionPointDto `json:"weeklyTrend"`
	MemberRows     []MemberGroupSavingsRowDto   `json:"memberRows"`
}

// MemberIndividualSavingsRowDto == one member row on IndividualSavingsSummaryDto.memberRows.
type MemberIndividualSavingsRowDto struct {
	MemberID            string  `json:"memberId"`
	Name                string  `json:"name"`
	CurrentBalance      int64   `json:"currentBalance"`
	LastTransaction     *int64  `json:"lastTransaction"`
	LastTransactionDate *string `json:"lastTransactionDate"`
}

// IndividualSavingsSummaryDto == GET /companion/groups/{groupId}/savings/individual.
type IndividualSavingsSummaryDto struct {
	TotalBalance int64                          `json:"totalBalance"`
	WeeklyTrend  []WeeklyContributionPointDto   `json:"weeklyTrend"`
	MemberRows   []MemberIndividualSavingsRowDto `json:"memberRows"`
}

// SavingsMemberDto == the member-identity subset of MemberSavingsDetailDto.
type SavingsMemberDto struct {
	MemberID    string  `json:"memberId"`
	DisplayName string  `json:"displayName"`
	PhotoURI    *string `json:"photoUri"`
}

// SavingsDataPointDto == one balance sparkline point (date pass-through String; "YYYY-MM-DD").
type SavingsDataPointDto struct {
	Date    string  `json:"date"`
	Balance float64 `json:"balance"`
}

// SavingsStatementEntryDto == one row of MemberSavingsDetailDto.transactions.
// date is LocalDate.parse'd by the app -> "YYYY-MM-DD".
type SavingsStatementEntryDto struct {
	ID             string  `json:"id"`
	Date           string  `json:"date"`
	Type           string  `json:"type"`
	Amount         float64 `json:"amount"`
	RunningBalance float64 `json:"runningBalance"`
	Reversed       bool    `json:"reversed"`
}

// MemberSavingsDetailDto == GET /companion/groups/{groupId}/members/{memberId}/savings.
type MemberSavingsDetailDto struct {
	Member            SavingsMemberDto           `json:"member"`
	SavingsAccountNo  string                     `json:"savingsAccountNo"`
	SavingsBalance    float64                    `json:"savingsBalance"`
	SharesHeld        *int                       `json:"sharesHeld"`
	ShareValue        *int64                     `json:"shareValue"`
	SparklineData     []SavingsDataPointDto      `json:"sparklineData"`
	Transactions      []SavingsStatementEntryDto `json:"transactions"`
	TotalTransactions int                        `json:"totalTransactions"`
	HasNextPage       bool                       `json:"hasNextPage"`
}

// ---- Handlers ----

// HandleGroupSavings returns the group-tab savings summary. cycleCollected/totalCollected are the
// REAL group corpus; cycleTarget + per-member rows are contribution-model-aware computed defaults
// (FIXED_AMOUNT / ACCUMULATING per defaultVSLAConfig) that never contradict the real corpus.
func (h *Handler) HandleGroupSavings(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	total, savingsIDs, err := h.aggregateSavings(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	deposits := h.groupDeposits(savingsIDs) // real, oldest-first

	memberCount := len(members)
	share := int64(0)
	if memberCount > 0 {
		share = int64(total) / int64(memberCount)
	}
	// cycleTarget: FIXED_AMOUNT contribution * members * weeks-in-cycle — a governance default
	// (contribution_amount=100, cycle_length_months=12 => ~52 weekly meetings per defaultVSLAConfig).
	cfg := defaultVSLAConfig()
	weeksPerCycle := int64(cfg.CycleLengthMonths) * 52 / 12
	cycleTarget := int64(cfg.ContributionAmount) * int64(memberCount) * weeksPerCycle

	rows := make([]MemberGroupSavingsRowDto, 0, memberCount)
	for _, m := range members {
		rows = append(rows, MemberGroupSavingsRowDto{
			MemberID:            strconv.FormatInt(m.ID, 10),
			Name:                m.Name,
			TotalContributed:    share,
			LastContribution:    lastDepositShare(deposits, int64(memberCount)),
			MeetingsContributed: len(deposits),
			SharesHeld:          nil, // FIXED_AMOUNT group -> share fields null per SavingsDto.kt
			ShareValue:          nil,
		})
	}

	_ = json.NewEncoder(w).Encode(GroupSavingsSummaryDto{
		CycleTarget:    cycleTarget,
		CycleCollected: int64(total),
		TotalCollected: int64(total),
		WeeklyTrend:    weeklyTrend(deposits),
		MemberRows:     rows,
	})
}

// HandleIndividualSavings returns the individual-tab summary — voluntary (non-group-linked)
// savings per member. mifos-bank-2 members hold no individual accounts, so balances are a REAL
// zero (honest, not fabricated); the shape is still fully populated so the tab renders.
func (h *Handler) HandleIndividualSavings(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	rows := make([]MemberIndividualSavingsRowDto, 0, len(members))
	var total int64
	for _, m := range members {
		bal := h.memberIndividualSavings(m.ID) // real per-member voluntary balance (0 on this seed)
		total += bal
		rows = append(rows, MemberIndividualSavingsRowDto{
			MemberID:            strconv.FormatInt(m.ID, 10),
			Name:                m.Name,
			CurrentBalance:      bal,
			LastTransaction:     nil,
			LastTransactionDate: nil,
		})
	}
	_ = json.NewEncoder(w).Encode(IndividualSavingsSummaryDto{
		TotalBalance: total,
		WeeklyTrend:  []WeeklyContributionPointDto{},
		MemberRows:   rows,
	})
}

// HandleMemberSavingsDetail returns one member's statement in the group-linked savings account.
// The corpus is one shared group account, so the member's balance + statement is their pro-rata
// share of the REAL deposits (a documented computed default that sums to the real corpus).
func (h *Handler) HandleMemberSavingsDetail(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	mid := r.PathValue("memberId")
	if mid == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid memberId")
		return
	}
	limit := intParam(r, "limit", 20)
	offset := intParam(r, "offset", 0)

	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	member, found := findMember(members, mid)
	if !found {
		writeErr(w, http.StatusNotFound, "not_found", "member or group not found")
		return
	}
	total, savingsIDs, err := h.aggregateSavings(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	memberCount := int64(len(members))
	if memberCount == 0 {
		memberCount = 1
	}
	deposits := h.groupDeposits(savingsIDs) // oldest-first

	// Build the member's running-balance statement from their share of each real group deposit.
	entries := make([]SavingsStatementEntryDto, 0, len(deposits))
	spark := make([]SavingsDataPointDto, 0, len(deposits))
	running := 0.0
	for _, d := range deposits {
		amt := deref64(d.Amount) / float64(memberCount)
		running += amt
		entries = append(entries, SavingsStatementEntryDto{
			ID:             d.ID,
			Date:           d.Date, // already "YYYY-MM-DD" from fmtFineractDate
			Type:           "DEPOSIT",
			Amount:         amt,
			RunningBalance: running,
			Reversed:       false,
		})
		spark = append(spark, SavingsDataPointDto{Date: d.Date, Balance: running})
	}

	savingsAccountNo := ""
	if len(savingsIDs) > 0 {
		savingsAccountNo = fmt.Sprintf("%09d", savingsIDs[0]) // Fineract zero-padded accountNo format
	}

	// Newest-first for the statement page; sparkline stays chronological.
	sort.SliceStable(entries, func(i, j int) bool { return entries[i].Date > entries[j].Date })
	totalTxns := len(entries)
	pagedEntries := pageSlice(entries, offset, limit)

	_ = json.NewEncoder(w).Encode(MemberSavingsDetailDto{
		Member: SavingsMemberDto{
			MemberID:    strconv.FormatInt(member.ID, 10),
			DisplayName: member.Name,
			PhotoURI:    nil,
		},
		SavingsAccountNo:  savingsAccountNo,
		SavingsBalance:    total / memberCountF(members),
		SharesHeld:        nil, // FIXED_AMOUNT group per defaultVSLAConfig
		ShareValue:        nil,
		SparklineData:     spark,
		Transactions:      pagedEntries,
		TotalTransactions: totalTxns,
		HasNextPage:       offset+limit < totalTxns,
	})
}

// ---- helpers ----

// groupDeposits returns every real deposit across the group's savings accounts, oldest-first.
// Reuses the same savingsActivity read (savingsaccounts/{id}?associations=transactions) the
// group-dashboard facade uses (deposits only — the app's savings statement models deposits).
func (h *Handler) groupDeposits(savingsIDs []int64) []ActivityItemDto {
	rows := make([]ActivityItemDto, 0)
	for _, sid := range savingsIDs {
		rows = append(rows, h.savingsActivity(sid)...)
	}
	sort.SliceStable(rows, func(i, j int) bool { return rows[i].Date < rows[j].Date }) // oldest-first
	return rows
}

// memberIndividualSavings sums a member's voluntary (individual) savings balance from their own
// Fineract client accounts (clients/{id}/accounts). Group-linked accounts live at the group level,
// so this returns only truly-individual balances (0 for the current seed roster — a real zero).
func (h *Handler) memberIndividualSavings(clientID int64) int64 {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", clientID), nil, nil)
	if err != nil {
		return 0
	}
	var ca fnGroupAccounts // reuses the savingsAccounts[] shape (id/accountBalance/status)
	if err := json.Unmarshal(raw, &ca); err != nil {
		return 0
	}
	var total float64
	for _, s := range ca.SavingsAccounts {
		if s.Status.Active {
			total += s.AccountBalance
		}
	}
	return int64(total)
}

// weeklyTrend maps the real group deposits to the contribution trend chart (groupAmount = deposit,
// individualAmount = 0 — no individual accounts on this seed). One point per deposit, "W{n}".
func weeklyTrend(deposits []ActivityItemDto) []WeeklyContributionPointDto {
	out := make([]WeeklyContributionPointDto, 0, len(deposits))
	for i, d := range deposits {
		out = append(out, WeeklyContributionPointDto{
			WeekLabel:        fmt.Sprintf("W%d", i+1),
			GroupAmount:      int64(deref64(d.Amount)),
			IndividualAmount: 0,
		})
	}
	return out
}

// lastDepositShare returns a member's share of the most-recent group deposit.
func lastDepositShare(deposits []ActivityItemDto, memberCount int64) int64 {
	if len(deposits) == 0 || memberCount <= 0 {
		return 0
	}
	last := deposits[len(deposits)-1]
	return int64(deref64(last.Amount)) / memberCount
}

// findMember resolves a memberId (string of the Fineract client id) to a memberRef.
func findMember(members []memberRef, id string) (memberRef, bool) {
	for _, m := range members {
		if strconv.FormatInt(m.ID, 10) == id {
			return m, true
		}
	}
	return memberRef{}, false
}

func memberCountF(members []memberRef) float64 {
	if len(members) == 0 {
		return 1
	}
	return float64(len(members))
}

func deref64(p *float64) float64 {
	if p == nil {
		return 0
	}
	return *p
}
