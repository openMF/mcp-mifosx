// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-DT-002 / COMP-DT-003 / COMP-DT-005: the organizer-side member-INVITE
// write facade. The CommonPurse (mifos-x-group-banking) app's MemberInviteApiImpl
// issues, lists, and revokes single-use invite tokens against the companion
// invitations datatable:
//
//	POST   /companion/datatables/invitations/{groupId}          -> GeneratedInviteDto     (generate)
//	GET    /companion/datatables/invitations/{groupId}          -> [PendingInviteDto]     (list pending)
//	DELETE /companion/datatables/invitations/{groupId}/{rowId}  -> RevokeInviteResponseDto (revoke)
//
// (see core/network/.../service/memberinvite/MemberInviteApiImpl.kt +
// core/network/.../model/MemberInviteDto.kt). This handler maps that contract
// onto real Fineract writes on mifos-bank-2: each invite is one row of the
// `dt_companion_invitations` multiRow datatable attached to apptable m_group.
//
// DISTINCT from the recipient-side join-with-code service (validate/associate/
// mark-accepted, not yet built here) — that models the invitee CONSUMING an
// invite by code; this models the organizer ISSUING/LISTING/REVOKING them. Both
// address the same datatable but with disjoint HTTP verbs.
//
// Writes/reads use the shared FineractClient service credential (adapter.DoRequest,
// which sends the service BasicAuth) — the correct path for an organizer/staff
// write, exactly like the group-create facade in group_create.go.
//
// Field names + casing are matched EXACTLY to the app DTOs: the request/list-row
// fields are the raw snake_case datatable column names (invited_email_phone,
// role_to_assign, expires_at, accepted_at); the generate response is
// {token, invite_link, row_id}; the revoke response is the Fineract
// command-processing envelope {resourceId}.
//
// DATE FORMAT DECISION: expires_at and accepted_at are registered as String
// (VARCHAR) datatable columns, NOT Fineract Date/DateTime types. The app sends
// expires_at as a client-computed ISO-8601 string (now()+7d) and reads it back as
// a plain String (PendingInviteDto.expiresAt / .acceptedAt are Kotlin String); a
// VARCHAR column round-trips that value byte-for-byte with NO Fineract dateFormat/
// locale parsing, so the Instant.parse hazard that bit the auth path's joinedAt
// (fmtFineractInstant) does NOT apply here — the string the app sent is exactly
// the string it gets back. accepted_at stays null for every row this facade
// writes (a pending invite); the list filters accepted_at IS NULL server-side.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strconv"
	"strings"
)

// invitationsTable is the Fineract datatable (apptable m_group) invite rows are
// written to. Provisioned out-of-band via POST /datatables (idempotent — Fineract
// 400s "datatable.already.exists.", treated as OK), same convention as
// groupTypeConfigTable in group_create.go. Columns: token (String 32),
// invited_email_phone (String 191), role_to_assign (String 32), expires_at
// (String 64), accepted_at (String 64, nullable), inviter_client_id (Number,
// nullable). Fineract auto-adds id / group_id / created_at / updated_at.
const invitationsTable = "dt_companion_invitations"

// inviteAlphabet is the 32-char, ambiguity-free (no 0/O/1/I) charset the 6-char
// invite token is drawn from — mirrors the app's "K7X2P9"-style test fixtures.
const inviteAlphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

// registerMemberInviteRoutes wires the COMP-DT-002/003/005 endpoints. Called from
// RegisterRoutes. Method-specific patterns (Go 1.22 ServeMux) — GET vs POST on the
// same {groupId} path dispatch separately, and DELETE carries an extra {rowId}
// segment.
func (h *Handler) registerMemberInviteRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /companion/datatables/invitations/{groupId}", h.HandleGenerateInvite)
	mux.HandleFunc("GET /companion/datatables/invitations/{groupId}", h.HandleListInvites)
	mux.HandleFunc("DELETE /companion/datatables/invitations/{groupId}/{rowId}", h.HandleRevokeInvite)
}

// ---- App-facing wire contract (exact match to the KMP DTOs) ----

// createInviteRequestDto == CreateInviteRequestDto (raw snake_case body; the
// groupId is a path param, not a body field).
type createInviteRequestDto struct {
	InvitedEmailPhone string `json:"invited_email_phone"`
	RoleToAssign      string `json:"role_to_assign"`
	ExpiresAt         string `json:"expires_at"`
}

// generatedInviteDto == GeneratedInviteDto — the issued token, the shareable
// deep-link URL, and the created datatable row's primary key.
type generatedInviteDto struct {
	Token      string `json:"token"`
	InviteLink string `json:"invite_link"`
	RowID      int64  `json:"row_id"`
}

// pendingInviteDto == PendingInviteDto — one row of the pending-invites list.
// AcceptedAt is a *string so it serializes as JSON null (matching the app's
// `String? = null`); this facade always emits null (only pending rows are listed).
type pendingInviteDto struct {
	RowID             int64   `json:"row_id"`
	Token             string  `json:"token"`
	InvitedEmailPhone string  `json:"invited_email_phone"`
	RoleToAssign      string  `json:"role_to_assign"`
	ExpiresAt         string  `json:"expires_at"`
	AcceptedAt        *string `json:"accepted_at"`
}

// revokeInviteResponseDto == RevokeInviteResponseDto — the Fineract
// command-processing envelope (resourceId = deleted row id).
type revokeInviteResponseDto struct {
	ResourceID int64 `json:"resourceId"`
}

// ---- Fineract wire type (raw datatable row read) ----

// invitationRow is one row of dt_companion_invitations as read back from
// GET /datatables/dt_companion_invitations/{groupId}. Fineract returns a plain
// JSON array of these (id = row primary key). created_at/updated_at/group_id/
// inviter_client_id are present on the wire but unused here.
type invitationRow struct {
	ID                int64   `json:"id"`
	Token             string  `json:"token"`
	InvitedEmailPhone string  `json:"invited_email_phone"`
	RoleToAssign      string  `json:"role_to_assign"`
	ExpiresAt         string  `json:"expires_at"`
	AcceptedAt        *string `json:"accepted_at"`
}

// ---- Handlers ----

// HandleGenerateInvite (COMP-DT-002) creates a single-use invite-token row for the
// posted contact + role and returns {token, invite_link, row_id}.
func (h *Handler) HandleGenerateInvite(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	var req createInviteRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if strings.TrimSpace(req.InvitedEmailPhone) == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "invited_email_phone is required")
		return
	}
	role := strings.TrimSpace(req.RoleToAssign)
	if role == "" {
		role = "member" // least-privilege default, matches the mapper's tolerant fallback.
	}

	// Derive a deterministic, collision-free 6-char token from (groupId, seq),
	// where seq starts at the current row count and increments past any live
	// token (so revoke-then-reissue never reuses a still-active code).
	existing, err := h.fetchInvitationRows(groupID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "read invitations: "+err.Error())
		return
	}
	taken := make(map[string]bool, len(existing))
	for _, row := range existing {
		taken[row.Token] = true
	}
	seq := int64(len(existing))
	token := inviteToken(groupID, seq)
	for taken[token] {
		seq++
		token = inviteToken(groupID, seq)
	}

	rowID, err := h.fineractWriteInvitation(groupID, token, req.InvitedEmailPhone, role, req.ExpiresAt)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "write invitation: "+err.Error())
		return
	}

	_ = json.NewEncoder(w).Encode(generatedInviteDto{
		Token:      token,
		InviteLink: inviteLinkFor(token, groupID),
		RowID:      rowID,
	})
}

// HandleListInvites (COMP-DT-003) returns all pending (accepted_at IS NULL) invite
// rows for the group.
func (h *Handler) HandleListInvites(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	rows, err := h.fetchInvitationRows(groupID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "read invitations: "+err.Error())
		return
	}
	out := make([]pendingInviteDto, 0, len(rows))
	for _, row := range rows {
		if row.AcceptedAt != nil && strings.TrimSpace(*row.AcceptedAt) != "" {
			continue // pending only — the app expects accepted_at IS NULL filtered server-side.
		}
		out = append(out, pendingInviteDto{
			RowID:             row.ID,
			Token:             row.Token,
			InvitedEmailPhone: row.InvitedEmailPhone,
			RoleToAssign:      row.RoleToAssign,
			ExpiresAt:         row.ExpiresAt,
			AcceptedAt:        nil,
		})
	}
	_ = json.NewEncoder(w).Encode(out)
}

// HandleRevokeInvite (COMP-DT-005) deletes a pending invite row by id and returns
// {resourceId}.
func (h *Handler) HandleRevokeInvite(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	groupID, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	rowID, err := strconv.ParseInt(r.PathValue("rowId"), 10, 64)
	if err != nil || rowID <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid rowId")
		return
	}
	resourceID, err := h.fineractDeleteInvitation(groupID, rowID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "delete invitation: "+err.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(revokeInviteResponseDto{ResourceID: resourceID})
}

// ---- Fineract writes/reads (service credential via DoRequest) ----

// fetchInvitationRows reads every row of the group's invitations datatable.
// Fineract returns a plain JSON array; an empty datatable yields [] (never null).
func (h *Handler) fetchInvitationRows(groupID int64) ([]invitationRow, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", invitationsTable, groupID), nil, nil)
	if err != nil {
		return nil, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var rows []invitationRow
	if err := json.Unmarshal(raw, &rows); err != nil {
		return nil, fmt.Errorf("decode invitations response: %w", err)
	}
	return rows, nil
}

// fineractWriteInvitation inserts one invite row and returns the new row id
// (Fineract's resourceId). All app-owned columns are String, so no dateFormat is
// needed; locale is sent so Fineract is happy to parse the row. accepted_at is
// left unset (null → pending).
func (h *Handler) fineractWriteInvitation(groupID int64, token, emailPhone, role, expiresAt string) (int64, error) {
	row := map[string]interface{}{
		"token":               token,
		"invited_email_phone": emailPhone,
		"role_to_assign":      role,
		"expires_at":          expiresAt,
		"locale":              "en",
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/%s/%d", invitationsTable, groupID), row, nil)
	if err != nil {
		return 0, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return 0, fmt.Errorf("decode write-invitation response: %w", err)
	}
	if resp.ResourceID == 0 {
		return 0, fmt.Errorf("fineract returned no row id: %s", string(raw))
	}
	return resp.ResourceID, nil
}

// fineractDeleteInvitation deletes an invite row and returns the deleted row id.
func (h *Handler) fineractDeleteInvitation(groupID, rowID int64) (int64, error) {
	raw, err := h.Fineract.DoRequest("DELETE", fmt.Sprintf("datatables/%s/%d/%d", invitationsTable, groupID, rowID), nil, nil)
	if err != nil {
		return 0, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return 0, fmt.Errorf("decode delete-invitation response: %w", err)
	}
	id := resp.ResourceID
	if id == 0 {
		id = rowID // Fineract echoed no resourceId — the requested row id is the truth.
	}
	return id, nil
}

// ---- helpers ----

// inviteToken derives a deterministic 6-char token from (groupId, seq) over the
// 32-char ambiguity-free alphabet. seq is offset by the group's row count and
// bumped past collisions by the caller, so the code is unique per live invite.
func inviteToken(groupID, seq int64) string {
	n := groupID*100000 + seq
	b := make([]byte, 6)
	for i := 5; i >= 0; i-- {
		b[i] = inviteAlphabet[n%int64(len(inviteAlphabet))]
		n /= int64(len(inviteAlphabet))
	}
	return string(b)
}

// inviteLinkFor builds the shareable deep-link the app surfaces on the success
// screen — matches the app's fixture shape
// (https://mifos.app/join?token=<TOKEN>&group=<groupId>).
func inviteLinkFor(token string, groupID int64) string {
	return fmt.Sprintf("https://mifos.app/join?token=%s&group=%d", token, groupID)
}
