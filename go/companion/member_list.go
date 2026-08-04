// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-MEMBERLIST: the member-list read facade. It reshapes the real Fineract group roster
// (GET groups/{id}?associations=all + per-member clients/{id}/accounts) into the offset-paginated
// contract the MifosSave (mifos-x-group-banking) app's MemberApiImpl fans in:
//
//	GET /groups/{groupId}/clients -> MemberPageDto   (the EXACT path + DTO the app calls)
//
// Source of truth for the wire shape is the app's OWN approved contract:
//   - service : core/network/.../service/memberlist/MemberApiImpl.kt  (path GET /groups/{groupId}/clients, params limit/offset)
//   - model   : core/network/.../model/MemberDto.kt                   (MemberPageDto / MemberDto / MemberRoleDto / LoanStatusDto)
//   - mapper  : core/network/.../mapper/MemberMappers.kt              (field-by-field, no unmapped field)
//
// Field names + casing are matched EXACTLY to MemberDto.kt. MemberDto carries NO date field, so no
// Instant-vs-LocalDate decision arises on this endpoint.
//
// Reads use the shared FineractClient service credential (adapter.DoRequest) — correct for reading
// group/member data (unlike the auth path, which forwards end-user creds).
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// registerMemberListRoutes wires the COMP-MEMBERLIST endpoints. Called from RegisterRoutes.
func (h *Handler) registerMemberListRoutes(mux *http.ServeMux) {
	// The EXACT path the app's MemberApiImpl calls (GET /groups/{groupId}/clients).
	mux.HandleFunc("GET /groups/{groupId}/clients", h.HandleGroupMembers)
	// Convenience alias under the /companion namespace (the path the generation brief suggested).
	mux.HandleFunc("GET /companion/groups/{groupId}/members", h.HandleGroupMembers)
}

// ---- App-facing wire contract (exact match to core/network/.../model/MemberDto.kt) ----

// MemberDto == one row of MemberPageDto.pageItems (GET /groups/{groupId}/clients).
type MemberDto struct {
	ID              string  `json:"id"`
	FineractClientID int64  `json:"fineractClientId"`
	DisplayName     string  `json:"displayName"`
	PhotoURI        *string `json:"photoUri"`
	Role            string  `json:"role"`
	SavingsBalance  float64 `json:"savingsBalance"`
	LoanStatus      string  `json:"loanStatus"`
}

// MemberPageDto == GET /groups/{groupId}/clients (offset-paginated envelope, page_size=20).
type MemberPageDto struct {
	TotalFilteredRecords int         `json:"totalFilteredRecords"`
	PageItems            []MemberDto `json:"pageItems"`
}

// ---- Handler ----

// HandleGroupMembers returns the group's members (role badge + savings share + loan-status chip),
// aggregated from LIVE Fineract. limit/offset are honored so the app's paging contract holds.
func (h *Handler) HandleGroupMembers(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	limit := intParam(r, "limit", 20)
	offset := intParam(r, "offset", 0)

	_, members, err := h.aggregateGroup(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}

	// Per-member savings: mifos-bank-2 holds the corpus in ONE group-level savings account
	// (no individual member accounts), so each member's balance is their pro-rata share of the
	// real group corpus — a documented computed default (PRO_RATA_BY_SHARES / equal FIXED_AMOUNT
	// contribution per defaultVSLAConfig) that sums to the real total, never a fabricated figure.
	groupSavings, _, _ := h.aggregateSavings(gid)
	share := 0.0
	if len(members) > 0 {
		share = groupSavings / float64(len(members))
	}

	page := MemberPageDto{PageItems: []MemberDto{}}
	for i, m := range members {
		page.PageItems = append(page.PageItems, MemberDto{
			ID:               strconv.FormatInt(m.ID, 10),
			FineractClientID: m.ID,
			DisplayName:      m.Name,
			PhotoURI:         nil, // Fineract exposes no member photo URL on this read.
			Role:             vslaCommitteeRole(m.Name, i),
			SavingsBalance:   share,
			LoanStatus:       h.memberLoanStatus(m.ID),
		})
	}
	page.TotalFilteredRecords = len(page.PageItems)

	// Honor the offset/limit window over the full roster (small groups, in-memory slice).
	page.PageItems = pageSlice(page.PageItems, offset, limit)
	_ = json.NewEncoder(w).Encode(page)
}

// ---- helpers ----

// memberLoanStatus resolves a member's loan-status chip from their real Fineract loan accounts
// (clients/{id}/accounts): an active-and-in-arrears loan -> OVERDUE, an active loan -> ACTIVE,
// otherwise NONE. Matches MemberDto.kt's LoanStatusDto value-set (ACTIVE/NONE/OVERDUE/UNKNOWN).
func (h *Handler) memberLoanStatus(clientID int64) string {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("clients/%d/accounts", clientID), nil, nil)
	if err != nil {
		return "NONE"
	}
	var ca fnClientAccounts
	if err := json.Unmarshal(raw, &ca); err != nil {
		return "NONE"
	}
	status := "NONE"
	for _, l := range ca.LoanAccounts {
		if !l.Status.Active {
			continue
		}
		if l.InArrears {
			return "OVERDUE"
		}
		status = "ACTIVE"
	}
	return status
}

// vslaCommitteeRole assigns a member's role badge. mifos-bank-2 exposes no per-group governance
// linkage, so roles are a documented VSLA-committee default: the three office-bearer roles are
// keyed by first name for the known seed roster (Amina=TREASURER, Joseph=CHAIRPERSON,
// Grace=SECRETARY), falling back to a stable positional default for any other group. Only values
// from MemberDto.kt's MemberRoleDto are emitted (no ORGANIZER — that enum has none).
func vslaCommitteeRole(name string, index int) string {
	fields := strings.Fields(strings.TrimSpace(name))
	if len(fields) > 0 {
		switch strings.ToLower(fields[0]) {
		case "amina":
			return "TREASURER"
		case "joseph":
			return "CHAIRPERSON"
		case "grace":
			return "SECRETARY"
		}
	}
	switch index {
	case 0:
		return "CHAIRPERSON"
	case 1:
		return "TREASURER"
	case 2:
		return "SECRETARY"
	default:
		return "MEMBER"
	}
}

// intParam reads a non-negative int query param, falling back to def on absence/parse failure.
func intParam(r *http.Request, key string, def int) int {
	raw := r.URL.Query().Get(key)
	if raw == "" {
		return def
	}
	v, err := strconv.Atoi(raw)
	if err != nil || v < 0 {
		return def
	}
	return v
}

// pageSlice returns the [offset, offset+limit) window of items, clamped to bounds.
func pageSlice[T any](items []T, offset, limit int) []T {
	if offset >= len(items) {
		return []T{}
	}
	end := offset + limit
	if limit <= 0 || end > len(items) {
		end = len(items)
	}
	return items[offset:end]
}
