// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-MEMBERDASH: the personal (member-scoped) dashboard read facade. It reshapes the real
// Fineract group corpus + the member's loan into the single companion contract the CommonPurse
// app's MemberDashboardApiImpl calls:
//
//	GET /companion/member/dashboard?selectedGroupId={id} -> MemberDashboardResponseDto
//
// Source of truth for the wire shape is the app's OWN approved contract:
//   - service : core/network/.../service/personaldashboard/MemberDashboardApiImpl.kt  (path + optional selectedGroupId param)
//   - model   : core/network/.../model/MemberDashboardDto.kt + SavingsTransactionDto.kt
//   - mapper  : core/network/.../mapper/MemberDashboardMappers.kt + SavingsTransactionMappers.kt
//
// DATE-FORMAT decision:
//   - SavingsTransactionDto.date (recentTransactions[]) -> LocalDate.parse(date) in
//     SavingsTransactionMappers.kt  => emit "YYYY-MM-DD" (fmtFineractDate). NOT an Instant field.
//   - MemberDashboardResponseDto.nextRecipientEta -> pass-through String? (never parsed); null here
//     because this seed is an ACCUMULATING (VSLA) pool, where the rotation triad is null by contract.
//
// The endpoint carries no memberId param (the caller identity is implicit), so the dashboard is
// resolved for a stable default member of the selected group (Grace Wanjiru, who holds the real
// loan) — the documented hook for real per-user resolution once member<->session linkage lands.
package companion

import (
	"encoding/json"
	"net/http"
	"strconv"
	"strings"
)

// registerMemberDashboardRoutes wires the COMP-MEMBERDASH endpoint. Called from RegisterRoutes.
func (h *Handler) registerMemberDashboardRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/member/dashboard", h.HandleMemberDashboard)
}

// ---- App-facing wire contract (exact match to core/network/.../model/MemberDashboardDto.kt) ----

// GroupSummaryDto == one row of myGroups / selectedGroup.
type GroupSummaryDto struct {
	GroupID   string `json:"groupId"`
	Name      string `json:"name"`
	PoolModel string `json:"poolModel"`
}

// SavingsTransactionDto == one row of recentTransactions (compact companion shape).
type SavingsTransactionDto struct {
	ID     string  `json:"id"`
	Date   string  `json:"date"`
	Type   string  `json:"type"`
	Amount float64 `json:"amount"`
}

// MemberDashboardResponseDto == GET /companion/member/dashboard.
// The pool-model projection axes are mutually exclusive: shareOutProjection for ACCUMULATING pools,
// rotationPosition+nextRecipientEta for ROTATING_PAYOUT pools. The app force-encodes all three
// (@EncodeDefault ALWAYS), so they are emitted even when null.
type MemberDashboardResponseDto struct {
	MemberName                string                  `json:"memberName"`
	ClientID                  int64                   `json:"clientId"`
	GroupLinkedSavingsID      int64                   `json:"groupLinkedSavingsId"`
	IndividualSavingsID       *int64                  `json:"individualSavingsId"`
	MyGroups                  []GroupSummaryDto       `json:"myGroups"`
	SelectedGroup             GroupSummaryDto         `json:"selectedGroup"`
	PoolModel                 string                  `json:"poolModel"`
	GroupLinkedSavingsBalance float64                 `json:"groupLinkedSavingsBalance"`
	IndividualSavingsBalance  float64                 `json:"individualSavingsBalance"`
	ShareOutProjection        *float64                `json:"shareOutProjection"`
	RotationPosition          *int                    `json:"rotationPosition"`
	NextRecipientEta          *string                 `json:"nextRecipientEta"`
	RecentTransactions        []SavingsTransactionDto `json:"recentTransactions"`
}

// ---- Handler ----

// HandleMemberDashboard resolves the member-scoped dashboard for the selected group from LIVE
// Fineract: real group corpus (member share), real per-member individual balance, real recent
// deposits, and the ACCUMULATING share-out projection computed from the real corpus net of loans.
func (h *Handler) HandleMemberDashboard(w http.ResponseWriter, r *http.Request) {
	setJSON(w)

	// Resolve the selected group: the query param if valid, else the first active group.
	groups, err := h.activeGroups()
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if len(groups) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "no active groups")
		return
	}
	selectedID := selectGroupID(r.URL.Query().Get("selectedGroupId"), groups)

	_, members, err := h.aggregateGroup(selectedID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if len(members) == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "group has no members")
		return
	}
	member := defaultDashboardMember(members)

	total, savingsIDs, err := h.aggregateSavings(selectedID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	loansOut, _, _, _ := h.aggregateLoans(members)
	memberCount := float64(len(members))
	memberShare := total / memberCount

	// shareOutProjection (ACCUMULATING): the member's share of the corpus net of outstanding loans
	// at cycle-end — computed from the REAL corpus + loans, never fabricated.
	proj := (total - loansOut) / memberCount
	if proj < 0 {
		proj = 0
	}

	groupLinkedSavingsID := int64(0)
	if len(savingsIDs) > 0 {
		groupLinkedSavingsID = savingsIDs[0]
	}

	// recentTransactions: the member's share of the real group deposits, newest-first, capped at 10.
	deposits := h.groupDeposits(savingsIDs)
	recent := make([]SavingsTransactionDto, 0, len(deposits))
	for i := len(deposits) - 1; i >= 0; i-- { // groupDeposits is oldest-first -> reverse for newest-first
		d := deposits[i]
		recent = append(recent, SavingsTransactionDto{
			ID:     d.ID,
			Date:   d.Date, // "YYYY-MM-DD"
			Type:   "DEPOSIT",
			Amount: deref64(d.Amount) / memberCount,
		})
		if len(recent) >= 10 {
			break
		}
	}

	// Build the group-summary chips. poolModel is ACCUMULATING (defaultVSLAConfig.PoolModel).
	poolModel := defaultVSLAConfig().PoolModel
	myGroups := make([]GroupSummaryDto, 0, len(groups))
	var selected GroupSummaryDto
	for _, g := range groups {
		gs := GroupSummaryDto{GroupID: strconv.FormatInt(g.ID, 10), Name: g.Name, PoolModel: poolModel}
		myGroups = append(myGroups, gs)
		if g.ID == selectedID {
			selected = gs
		}
	}

	_ = json.NewEncoder(w).Encode(MemberDashboardResponseDto{
		MemberName:                member.Name,
		ClientID:                  member.ID,
		GroupLinkedSavingsID:      groupLinkedSavingsID,
		IndividualSavingsID:       nil, // no voluntary individual account on this seed
		MyGroups:                  myGroups,
		SelectedGroup:             selected,
		PoolModel:                 poolModel,
		GroupLinkedSavingsBalance: memberShare,
		IndividualSavingsBalance:  0,
		ShareOutProjection:        &proj, // ACCUMULATING pool -> populated
		RotationPosition:          nil,   // ROTATING_PAYOUT-only -> null
		NextRecipientEta:          nil,   // ROTATING_PAYOUT-only -> null
		RecentTransactions:        recent,
	})
}

// ---- helpers ----

// activeGroups returns every active Fineract group (id + name), mirroring resolveGroups/HandleMyGroups.
func (h *Handler) activeGroups() ([]fnGroup, error) {
	raw, err := h.Fineract.DoRequest("GET", "groups", nil, nil)
	if err != nil {
		return nil, err
	}
	var groups []fnGroup
	if err := json.Unmarshal(raw, &groups); err != nil {
		return nil, err
	}
	out := make([]fnGroup, 0, len(groups))
	for _, g := range groups {
		if g.Active {
			out = append(out, g)
		}
	}
	return out, nil
}

// selectGroupID picks the requested group id if it names an active group, else the first active group.
func selectGroupID(requested string, groups []fnGroup) int64 {
	if id, err := strconv.ParseInt(strings.TrimSpace(requested), 10, 64); err == nil {
		for _, g := range groups {
			if g.ID == id {
				return id
			}
		}
	}
	return groups[0].ID
}

// defaultDashboardMember picks the stable default member for the implicit-identity dashboard:
// Grace Wanjiru if present (she holds the real loan → richest dashboard), else the first member.
func defaultDashboardMember(members []memberRef) memberRef {
	for _, m := range members {
		fields := strings.Fields(strings.TrimSpace(m.Name))
		if len(fields) > 0 && strings.EqualFold(fields[0], "grace") {
			return m
		}
	}
	return members[0]
}
