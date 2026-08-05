// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-DT-ROLE: the dt_member_role datatable facade (apptable m_client, keyed by clientId — the
// TRUE parent id per DATATABLE_REGISTRY, with the group_id routing column carried inside the row).
// The MifosSave (mifos-x-group-banking) app reads and assigns a member's group role via the raw
// Fineract datatable, which a self-service client cannot touch directly (ACCESS_MODEL
// companion_service_exec, datatable_ref dt_member_role):
//
//   GET  /datatables/dt_member_role/{clientId}  -> the member's role row(s)  (memberprofile.getMemberRole)
//   POST /datatables/dt_member_role/{clientId}  -> assign role               (memberadd.assignMemberRole, step 2)
//   PUT  /datatables/dt_member_role/{clientId}  -> update role               (memberprofile.updateMemberRole)
//
// Both run with the shared service credential (adapter.DoRequest) and stream Fineract's datatable
// response through verbatim — the app DTOs already match the raw datatable row shape, because these
// were raw Fineract datatable calls before the single-host migration re-pointed the base URL at the
// companion. locale is injected on write so Fineract parses the row (same convention as the
// invitations / meeting facades).
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

const memberRoleTable = "dt_member_role"

// registerMemberRoleRoutes wires the COMP-DT-ROLE endpoints. Called from RegisterRoutes.
func (h *Handler) registerMemberRoleRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /datatables/dt_member_role/{clientId}", h.HandleMemberRoleRead)
	mux.HandleFunc("POST /datatables/dt_member_role/{clientId}", h.HandleMemberRoleWrite)
	mux.HandleFunc("PUT /datatables/dt_member_role/{clientId}", h.HandleMemberRoleUpdate)
}

// HandleMemberRoleRead returns the member's dt_member_role row(s). A member with no role row yet is a
// NORMAL state (Fineract 404s an empty multiRow read), not an error — return an empty JSON array so
// the app degrades to "no role assigned" rather than a spurious NetworkResult.Error.
func (h *Handler) HandleMemberRoleRead(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	clientID, ok := pathInt64Param(w, r, "clientId")
	if !ok {
		return
	}
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", memberRoleTable, clientID), nil, nil)
	if err != nil {
		_, _ = w.Write([]byte("[]"))
		return
	}
	_, _ = w.Write(raw)
}

// HandleMemberRoleWrite inserts one dt_member_role row for the client (assign role) and streams
// Fineract's command-processing response (resourceId envelope) back.
func (h *Handler) HandleMemberRoleWrite(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	clientID, ok := pathInt64Param(w, r, "clientId")
	if !ok {
		return
	}
	body := map[string]interface{}{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	if _, has := body["locale"]; !has {
		body["locale"] = "en"
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/%s/%d", memberRoleTable, clientID), body, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "assign member role: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	_, _ = w.Write(raw)
}

// HandleMemberRoleUpdate updates the member's existing dt_member_role row (single-row datatable, keyed
// by clientId — PUT /datatables/dt_member_role/{clientId}) and streams Fineract's response back.
func (h *Handler) HandleMemberRoleUpdate(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	clientID, ok := pathInt64Param(w, r, "clientId")
	if !ok {
		return
	}
	body := map[string]interface{}{}
	if r.Body != nil {
		_ = json.NewDecoder(r.Body).Decode(&body)
	}
	if _, has := body["locale"]; !has {
		body["locale"] = "en"
	}
	raw, err := h.Fineract.DoRequest("PUT", fmt.Sprintf("datatables/%s/%d", memberRoleTable, clientID), body, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "update member role: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	_, _ = w.Write(raw)
}
