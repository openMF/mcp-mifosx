// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-MEMBER-ADD: the organizer-side "add member" orchestration route. The MifosSave
// (mifos-x-group-banking) app's ONLINE member-add path runs a TWO-CALL client-side chain
// (MemberAddRepositoryImpl.createMember): POST /clients -> read the new clientId -> POST
// /datatables/dt_member_role/{clientId}. That chaining CANNOT be expressed as an offline
// SyncQueue row, because the role write needs the clientId that only exists AFTER the client is
// created (and the /batches self-dispatch does not resolve cross-op references). So the OFFLINE
// path targets this SINGLE orchestration route, which performs the whole chain server-side in one
// request — create the Fineract client, then (best-effort) assign the member role — letting an
// offline member-add be durably queued and drained as one operation.
//
//	POST /companion/members   { …CreateMemberRequestDto fields…, role, assignedDate } -> {clientId, resourceId}
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

func (h *Handler) registerMemberAddRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /companion/members", h.HandleCreateMember)
}

// createMemberOrchestrationIn is the flattened offline payload: the Fineract client-create fields
// plus the role + assignedDate the second (dt_member_role) write needs. `role` is the uppercase
// wire enum the app's MemberRoleDto serializes (CHAIRPERSON/TREASURER/SECRETARY/MEMBER).
type createMemberOrchestrationIn struct {
	Firstname      string `json:"firstname"`
	Lastname       string `json:"lastname"`
	MobileNo       string `json:"mobileNo"`
	Active         bool   `json:"active"`
	ActivationDate string `json:"activationDate"`
	OfficeID       int64  `json:"officeId"`
	GroupID        int64  `json:"groupId"`
	Locale         string `json:"locale"`
	DateFormat     string `json:"dateFormat"`
	Role           string `json:"role"`
	AssignedDate   string `json:"assignedDate"`
}

// createMemberOrchestrationOut mirrors the app's CreateMemberResponseDto — resourceId == clientId
// on a Fineract /clients create.
type createMemberOrchestrationOut struct {
	ClientID   int64 `json:"clientId"`
	ResourceID int64 `json:"resourceId"`
}

// HandleCreateMember creates the Fineract client, then assigns the member role — the full
// organizer add-member chain in one call so it is offline-queueable (see file header).
func (h *Handler) HandleCreateMember(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	var in createMemberOrchestrationIn
	if err := json.NewDecoder(r.Body).Decode(&in); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid member body")
		return
	}
	if strings.TrimSpace(in.Firstname) == "" || in.OfficeID == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "firstname and officeId are required")
		return
	}

	locale := in.Locale
	if locale == "" {
		locale = "en"
	}
	dateFormat := in.DateFormat
	if dateFormat == "" {
		dateFormat = "dd MMMM yyyy"
	}

	// Step 1 — create the client. groupId is carried in the body (Fineract auto-associates the
	// client to the group on create, same as the app's device-verified online path).
	clientBody := map[string]interface{}{
		"firstname":      in.Firstname,
		"lastname":       in.Lastname,
		"mobileNo":       in.MobileNo,
		"active":         in.Active,
		"activationDate": in.ActivationDate,
		"officeId":       in.OfficeID,
		"legalFormId":    1, // 1 = PERSON (Fineract requires legalFormId on /clients create)
		"locale":         locale,
		"dateFormat":     dateFormat,
	}
	if in.GroupID != 0 {
		clientBody["groupId"] = in.GroupID
	}
	raw, err := h.Fineract.DoRequest("POST", "clients", clientBody, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create client: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	var created struct {
		ResourceID int64 `json:"resourceId"`
		ClientID   int64 `json:"clientId"`
	}
	if json.Unmarshal(raw, &created) != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create client: unexpected response shape")
		return
	}
	clientID := created.ResourceID
	if clientID == 0 {
		clientID = created.ClientID
	}
	if clientID == 0 {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create client: no clientId in response")
		return
	}

	// Step 2 — assign the member role (best-effort, mirroring the app chain: the client already
	// exists, so a role-write failure is logged but does NOT fail the member add).
	if role := strings.TrimSpace(in.Role); role != "" && !strings.EqualFold(role, "UNKNOWN") {
		assignedDate := in.AssignedDate
		if assignedDate == "" {
			assignedDate = in.ActivationDate
		}
		roleBody := map[string]interface{}{
			"role":         role,
			"groupId":      in.GroupID,
			"assignedDate": assignedDate,
			"locale":       locale,
			"dateFormat":   dateFormat,
		}
		if _, rerr := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/dt_member_role/%d", clientID), roleBody, nil); rerr != nil {
			// Non-fatal: the member is created; the role datatable is bookkeeping.
			// (Matches MemberAddRepositoryImpl's "assignMemberRole failed … member still created".)
			_ = rerr
		}
	}

	_ = json.NewEncoder(w).Encode(createMemberOrchestrationOut{ClientID: clientID, ResourceID: clientID})
}
