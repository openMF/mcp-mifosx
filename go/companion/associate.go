// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-GRP-003 / COMP-DT-004 (recipient side): the join-with-code "Join Group"
// write facade. After the invitee validates a code and previews the group, the
// CommonPurse app's InvitationApiImpl issues two calls (see
// core/data/.../InvitationRepositoryImpl.joinGroup):
//
//	POST /companion/groups/{groupId}/associate-clients          -> AssociateClientsResponseDto
//	PUT  /companion/datatables/invitations/{code}/{rowId}       -> MarkAcceptedResponseDto (best-effort)
//
// The first associates the authenticated invitee to the Fineract group as a member
// (the actual join); the second stamps accepted_at on the invite row (bookkeeping —
// the app treats its failure as non-fatal). Both are recipient-side, distinct from
// the organizer's issue/list/revoke facade in member_invite.go.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// associateClientsRequestDto == AssociateClientsRequestDto (join-with-code confirm body).
type associateClientsRequestDto struct {
	ClientIDs    []int64 `json:"clientIds"`
	RoleToAssign string  `json:"roleToAssign"`
}

// associateClientsResponseDto == AssociateClientsResponseDto — the Fineract
// command-processing envelope (resourceId) plus the echoed groupId + clientIds.
type associateClientsResponseDto struct {
	ResourceID int64   `json:"resourceId"`
	GroupID    int64   `json:"groupId"`
	ClientIDs  []int64 `json:"clientIds"`
}

// markAcceptedResponseDto == MarkAcceptedResponseDto — {resourceId, changes:{accepted_at}}.
type markAcceptedResponseDto struct {
	ResourceID int64                  `json:"resourceId"`
	Changes    markAcceptedChangesDto `json:"changes"`
}

type markAcceptedChangesDto struct {
	AcceptedAt string `json:"accepted_at"`
}

func (h *Handler) registerAssociateRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /companion/groups/{groupId}/associate-clients", h.HandleAssociateClients)
	mux.HandleFunc("PUT /companion/datatables/invitations/{code}/{rowId}", h.HandleMarkInvitationAccepted)
}

// HandleAssociateClients (COMP-GRP-003) associates the authenticated invitee's REAL
// Fineract client to the group as a member — the join-with-code "Join Group" write.
//
// The app sends AssociateClientsRequestDto{clientIds:[session.userId], ...}, but session.userId
// is the Fineract USER id, NOT the client id — trusting the request body would associate the
// wrong entity (or fail). Instead resolve the caller's real client id from the bearer session
// (client externalId == account email), exactly like resolveGroups' data-isolation path. Fall
// back to the request's clientIds only if the session yields no linked client (defensive; a
// logged-in invitee always has one).
func (h *Handler) HandleAssociateClients(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	var req associateClientsRequestDto
	_ = json.NewDecoder(r.Body).Decode(&req) // body is a hint; the session is the source of truth

	var clientIDs []int64
	if fa := h.callerFromRequest(r); fa != nil {
		for id := range h.userClientIDs(fa) {
			clientIDs = append(clientIDs, id)
		}
	}
	if len(clientIDs) == 0 {
		clientIDs = req.ClientIDs // defensive fallback (unresolved session client)
	}
	if len(clientIDs) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "no client to associate (unresolved session client)")
		return
	}

	resourceID, err := h.fineractAssociateClients(groupID, clientIDs)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "associate clients: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(associateClientsResponseDto{
		ResourceID: resourceID,
		GroupID:    groupID,
		ClientIDs:  clientIDs,
	})
}

// HandleMarkInvitationAccepted (COMP-DT-004) stamps accepted_at on the invite row so a
// consumed code can't be reused. The app resolves no real rowId (its validate response carries
// none — a known contract gap), so it sends a sentinel; we resolve the true row by TOKEN here
// and update it. Best-effort: the app treats any failure as non-fatal (the join already landed).
func (h *Handler) HandleMarkInvitationAccepted(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	code := strings.TrimSpace(r.PathValue("code"))
	if code == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "missing invite code")
		return
	}
	var req struct {
		AcceptedAt string `json:"accepted_at"`
	}
	_ = json.NewDecoder(r.Body).Decode(&req)
	acceptedAt := strings.TrimSpace(req.AcceptedAt)

	// Resolve the invite row (group + row id) by scanning active groups for the token — the
	// app's {rowId} path segment is an unresolved sentinel, so the token is the real key.
	groupID, rowID, found := h.findInvitationByToken(code)
	if !found {
		writeErr(w, http.StatusNotFound, "not_found", "no invitation row for code "+code)
		return
	}
	if _, err := h.Fineract.DoRequest(
		"PUT",
		fmt.Sprintf("datatables/%s/%d/%d", invitationsTable, groupID, rowID),
		map[string]interface{}{"accepted_at": acceptedAt, "locale": "en"},
		nil,
	); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "mark accepted: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(markAcceptedResponseDto{
		ResourceID: rowID,
		Changes:    markAcceptedChangesDto{AcceptedAt: acceptedAt},
	})
}

// findInvitationByToken scans every active group's invitation rows for a matching token,
// returning its group id + row id. Concurrent scan, same shape as HandleListInvites' token path.
func (h *Handler) findInvitationByToken(token string) (groupID, rowID int64, found bool) {
	raw, err := h.Fineract.DoRequest("GET", "groups", nil, nil)
	if err != nil {
		return 0, 0, false
	}
	var groups []fnGroup
	if json.Unmarshal(raw, &groups) != nil {
		return 0, 0, false
	}
	for _, g := range groups {
		if !g.Active {
			continue
		}
		rows, rerr := h.fetchInvitationRows(g.ID)
		if rerr != nil {
			continue
		}
		for _, row := range rows {
			if strings.EqualFold(strings.TrimSpace(row.Token), token) {
				return g.ID, row.ID, true
			}
		}
	}
	return 0, 0, false
}

// fineractAssociateClients POSTs groups/{id}?command=associateClients {clientMembers:[...]}.
// A client already in the group is treated as success (idempotent join — re-accepting an invite
// or a creator re-associating must never error).
func (h *Handler) fineractAssociateClients(groupID int64, clientIDs []int64) (int64, error) {
	body := map[string]interface{}{"clientMembers": clientIDs}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("groups/%d", groupID), body, map[string]string{"command": "associateClients"})
	if err != nil {
		msg := strings.ToLower(string(nonEmpty(raw)))
		if strings.Contains(msg, "already associated") || strings.Contains(msg, "already a member") || strings.Contains(msg, "already exists") {
			return groupID, nil // idempotent: already a member
		}
		return 0, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
		GroupID    int64 `json:"groupId"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return groupID, nil // command succeeded; response-shape variance is non-fatal
	}
	id := resp.ResourceID
	if id == 0 {
		id = resp.GroupID
	}
	if id == 0 {
		id = groupID
	}
	return id, nil
}
