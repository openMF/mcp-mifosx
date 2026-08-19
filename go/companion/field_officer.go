// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-FO: the field-officer staff facade. The MifosSave (mifos-x-group-banking) app's
// FieldOfficerApiImpl needs two STAFF-scoped Fineract reads that a self-service client cannot call
// directly (ACCESS_MODEL companion_service_exec — a native staff API exists, but the user can't
// reach it, so the companion runs it on their behalf with the service credential):
//
//   GET /companion/field-officer/groups?staffId=&paged=&limit=&offset=
//       -> Fineract GET /groups?staffId=…  -> PagedGroupsResponseDto  (fieldofficerdashboard.getGroupsForStaff)
//   GET /companion/field-officer/report?R_staffId=&output-type=
//       -> Fineract GET /runreports/FieldOfficerGroupReport?…  -> ByteArray  (fieldofficerdashboard.runReport)
//
// Both run with the shared service credential (adapter.DoRequest) and stream Fineract's response
// through verbatim — the app DTOs already match Fineract's native paged-groups / runreport shapes,
// because the field-officer client was calling those raw Fineract paths before the single-host
// migration re-pointed its base URL at the companion.
package companion

import (
	"net/http"
	"strings"
)

// registerFieldOfficerRoutes wires the COMP-FO endpoints. Called from RegisterRoutes.
func (h *Handler) registerFieldOfficerRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /companion/field-officer/groups", h.HandleFieldOfficerGroups)
	mux.HandleFunc("GET /companion/field-officer/report", h.HandleFieldOfficerReport)
}

// HandleFieldOfficerGroups proxies the staff-scoped group list (GET /groups?staffId=…) and streams
// Fineract's paged response through unchanged.
func (h *Handler) HandleFieldOfficerGroups(w http.ResponseWriter, r *http.Request) {
	q := passthroughQuery(r, "staffId", "paged", "limit", "offset")
	raw, err := h.Fineract.DoRequest("GET", "groups", nil, q)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "field-officer groups: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	setJSON(w)
	_, _ = w.Write(raw)
}

// HandleFieldOfficerReport proxies the FieldOfficerGroupReport run-report. The app reads the result
// as an opaque ByteArray, so the report bytes are streamed through verbatim; Content-Type is set from
// the output-type param (json default) purely so a browser/devtools render is sensible.
func (h *Handler) HandleFieldOfficerReport(w http.ResponseWriter, r *http.Request) {
	// The app passes the field officer's staff id as R_staffId. The FieldOfficerGroupReport declares
	// the stock `loanOfficerIdSelectAll` parameter (SQL var ${loanOfficerId}, run-param R_loanOfficerId)
	// — in Fineract a group's staff IS its loan officer — so translate R_staffId -> R_loanOfficerId
	// before running the report. (Registered by server-layer/migrations/register-reports.)
	q := passthroughQuery(r, "output-type")
	if staff := strings.TrimSpace(r.URL.Query().Get("R_staffId")); staff != "" {
		q["R_loanOfficerId"] = staff
	}
	raw, err := h.Fineract.DoRequest("GET", "runreports/FieldOfficerGroupReport", nil, q)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "field-officer report: "+strings.TrimSpace(string(nonEmpty(raw))))
		return
	}
	switch strings.ToLower(strings.TrimSpace(r.URL.Query().Get("output-type"))) {
	case "csv":
		w.Header().Set("Content-Type", "text/csv")
	case "pdf":
		w.Header().Set("Content-Type", "application/pdf")
	default:
		setJSON(w)
	}
	_, _ = w.Write(raw)
}

// passthroughQuery collects the named query params that are present + non-blank into the
// map[string]string DoRequest forwards to Fineract. Absent/blank keys are dropped (never sent as
// empty), so an omitted optional param stays omitted upstream.
func passthroughQuery(r *http.Request, keys ...string) map[string]string {
	out := make(map[string]string, len(keys))
	for _, k := range keys {
		if v := strings.TrimSpace(r.URL.Query().Get(k)); v != "" {
			out[k] = v
		}
	}
	return out
}
