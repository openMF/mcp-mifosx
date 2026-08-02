// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-GRP-001: the group-CREATE write facade. The CommonPurse
// (mifos-x-group-banking) app's GroupCreateApiImpl submits its multi-step
// create-group wizard as ONE orchestration call:
//
//	POST /companion/groups  ->  CreateGroupResponseDto
//
// (see core/network/.../service/groupcreate/GroupCreateApiImpl.kt +
// core/network/.../model/GroupCreateDto.kt). This handler fans that single call
// out across the real Fineract writes on mifos-bank-2:
//
//	1. POST groups {name, officeId, active:false}      -> new pending group
//	2. POST groups/{id}?command=activate               -> activate it
//	3. POST datatables/dt_group_type_config/{id}        -> persist the VSLA typeConfig row
//
// then returns the { groupId, fineractCenterId, inviteCode } shape the app maps
// to core.model.GroupCreationResult. The request payload has NO member list
// (organizer creates an empty group; members join later via the invite code),
// so there is no client-association step.
//
// Writes use the shared FineractClient service credential (adapter.DoRequest,
// which POSTs with the service BasicAuth) — the correct path for an
// organizer/staff write, exactly like the group-dashboard READ facade in
// groups.go. The end-user-credential path (companion.go's fineractPost) is only
// for /authentication, where a wrong password must surface as a real 401.
//
// Field names + casing are matched EXACTLY to the app DTOs: top-level request /
// response fields are camelCase; the nested typeConfig is snake_case (its
// @SerialName's are raw Fineract datatable column names). The response carries
// NO date field, so the Instant.parse / date-only caveat that bit the auth
// path's joinedAt does NOT apply here — groupId is a String, fineractCenterId a
// Long, inviteCode a String.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"time"
)

// groupTypeConfigTable is the Fineract datatable (apptable m_group) the VSLA
// typeConfig row is written to. Provisioned out-of-band via POST /datatables
// (idempotent — Fineract 400s "already registered", treated as OK). Its columns
// mirror CreateGroupTypeConfigDto's snake_case @SerialName's 1:1.
const groupTypeConfigTable = "dt_group_type_config"

// registerGroupCreateRoutes wires the COMP-GRP-001 write endpoint. Called from
// RegisterRoutes. The method-specific pattern coexists with groups.go's
// "GET /companion/groups" (Go 1.22 ServeMux dispatches GET vs POST separately).
func (h *Handler) registerGroupCreateRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /companion/groups", h.HandleCreateGroup)
}

// ---- App-facing wire contract (exact match to the KMP DTOs) ----

// createGroupTypeConfigDto == CreateGroupTypeConfigDto (snake_case wire).
type createGroupTypeConfigDto struct {
	GroupType          string  `json:"group_type"`
	PoolModel          string  `json:"pool_model"`
	ContributionModel  string  `json:"contribution_model"`
	ShareoutFormula    string  `json:"shareout_formula"`
	PayoutOrderMethod  string  `json:"payout_order_method"`
	ShareValue         float64 `json:"share_value"`
	ContributionAmount float64 `json:"contribution_amount"`
	SocialFundEnabled  bool    `json:"social_fund_enabled"`
	SocialFundPercent  float64 `json:"social_fund_percent"`
	CycleLengthMonths  int     `json:"cycle_length_months"`
	LoanMultiplier     float64 `json:"loan_multiplier"`
	InterestRate       float64 `json:"interest_rate"`
	FineAmount         float64 `json:"fine_amount"`
	MaxMembers         int     `json:"max_members"`
}

// createGroupRequestDto == CreateGroupRequestDto (camelCase top-level).
type createGroupRequestDto struct {
	Name        string                   `json:"name"`
	OfficeID    int64                    `json:"officeId"`
	UserID      int64                    `json:"userId"`
	Currency    string                   `json:"currency"`
	MeetingDay  string                   `json:"meetingDay"`
	MeetingTime string                   `json:"meetingTime"`
	TypeConfig  createGroupTypeConfigDto `json:"typeConfig"`
}

// createGroupResponseDto == CreateGroupResponseDto.
type createGroupResponseDto struct {
	GroupID          string `json:"groupId"`
	FineractCenterID int64  `json:"fineractCenterId"`
	InviteCode       string `json:"inviteCode"`
}

// ---- Handler ----

// HandleCreateGroup runs the create-group orchestration against Fineract and
// returns the app's CreateGroupResponseDto on success.
func (h *Handler) HandleCreateGroup(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var req createGroupRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if strings.TrimSpace(req.Name) == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "name is required")
		return
	}
	officeID := req.OfficeID
	if officeID <= 0 {
		officeID = 1 // Head Office default — the only office the app's dropdown seeds against.
	}

	// 1. Create the group (pending).
	groupID, err := h.fineractCreateGroup(req.Name, officeID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create group: "+err.Error())
		return
	}

	// 2. Activate it.
	if err := h.fineractActivateGroup(groupID); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "activate group: "+err.Error())
		return
	}

	// 3. Persist the VSLA typeConfig datatable row.
	if err := h.fineractWriteGroupTypeConfig(groupID, req.TypeConfig); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "write group_type_config: "+err.Error())
		return
	}

	// 4. Return the app's response shape. fineractCenterId mirrors the new group
	// id (this flow creates a plain group, not a parent center) — a real, non-zero
	// Fineract entity id; the app's success screen surfaces inviteCode.
	_ = json.NewEncoder(w).Encode(createGroupResponseDto{
		GroupID:          strconv.FormatInt(groupID, 10),
		FineractCenterID: groupID,
		InviteCode:       inviteCodeFor(req.Name, groupID),
	})
}

// ---- Fineract writes (service credential via DoRequest) ----

// fineractCreateGroup POSTs a new pending group and returns its id.
func (h *Handler) fineractCreateGroup(name string, officeID int64) (int64, error) {
	body := map[string]interface{}{
		"name":     name,
		"officeId": officeID,
		"active":   false,
	}
	raw, err := h.Fineract.DoRequest("POST", "groups", body, nil)
	if err != nil {
		return 0, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var resp struct {
		GroupID    int64 `json:"groupId"`
		ResourceID int64 `json:"resourceId"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return 0, fmt.Errorf("decode create-group response: %w", err)
	}
	id := resp.ResourceID
	if id == 0 {
		id = resp.GroupID
	}
	if id == 0 {
		return 0, fmt.Errorf("fineract returned no group id: %s", string(raw))
	}
	return id, nil
}

// fineractActivateGroup activates a pending group as of today.
func (h *Handler) fineractActivateGroup(groupID int64) error {
	body := map[string]string{
		"activationDate": time.Now().Format("02 January 2006"), // Fineract "dd MMMM yyyy"
		"dateFormat":     "dd MMMM yyyy",
		"locale":         "en",
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("groups/%d", groupID), body, map[string]string{"command": "activate"})
	if err != nil {
		return fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	return nil
}

// fineractWriteGroupTypeConfig writes the typeConfig row to the group's
// dt_group_type_config datatable. Column names are the raw snake_case
// CreateGroupTypeConfigDto @SerialName's. locale is included so Fineract parses
// the decimal columns; there are no date columns, so no dateFormat is needed.
func (h *Handler) fineractWriteGroupTypeConfig(groupID int64, tc createGroupTypeConfigDto) error {
	row := map[string]interface{}{
		"group_type":          tc.GroupType,
		"pool_model":          tc.PoolModel,
		"contribution_model":  tc.ContributionModel,
		"shareout_formula":    tc.ShareoutFormula,
		"payout_order_method": tc.PayoutOrderMethod,
		"share_value":         tc.ShareValue,
		"contribution_amount": tc.ContributionAmount,
		"social_fund_enabled": tc.SocialFundEnabled,
		"social_fund_percent": tc.SocialFundPercent,
		"cycle_length_months": tc.CycleLengthMonths,
		"loan_multiplier":     tc.LoanMultiplier,
		"interest_rate":       tc.InterestRate,
		"fine_amount":         tc.FineAmount,
		"max_members":         tc.MaxMembers,
		"locale":              "en",
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/%s/%d", groupTypeConfigTable, groupID), row, nil)
	if err != nil {
		return fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	return nil
}

// ---- helpers ----

// inviteCodeFor derives a shareable onboarding code from the group name + id
// (first 3 alpha chars uppercased, padded with X, + zero-padded id). Deterministic
// and unique per group (id-suffixed), e.g. "Test VSLA Group" #7 -> "TES007".
func inviteCodeFor(name string, groupID int64) string {
	var prefix strings.Builder
	for _, r := range strings.ToUpper(name) {
		if r >= 'A' && r <= 'Z' {
			prefix.WriteRune(r)
			if prefix.Len() == 3 {
				break
			}
		}
	}
	p := prefix.String()
	for len(p) < 3 {
		p += "X"
	}
	return fmt.Sprintf("%s%03d", p, groupID)
}
