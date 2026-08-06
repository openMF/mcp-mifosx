// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-PROXY: bare-Fineract STAFF passthroughs (ACCESS_MODEL companion_service_exec). A few app
// services call raw Fineract paths that a self-service client can't reach, and whose request/response
// bodies are already Fineract-native (they were direct Fineract calls before the single-host
// migration re-pointed their base URL at the companion). Rather than reshape each into a bespoke
// BFF DTO, the companion forwards them VERBATIM to Fineract with the shared service credential:
//
//   GET  /groups/{groupId}?associations=…    -> group + members/roles  (loanapply/meetingconduct.getGroupMembers)
//   POST /clients                            -> create member client    (memberadd.createClient)
//   GET  /clients/{clientId}                 -> member profile          (memberprofile.getMemberProfile)
//   POST /clients/{clientId}/images          -> member photo (multipart) (memberadd.uploadMemberPhoto)
//
// Each is a raw reverse-proxy of method + path + query + body + Content-Type, so the multipart photo
// upload streams through unchanged (DoRequest's JSON marshaling can't carry multipart). The client's
// bare path equals Fineract's path after BaseURL (which already carries /fineract-provider/api/v1), so
// the target is BaseURL + r.URL.Path. This shares proxyToFineract with the /self/* handler
// (self_passthrough.go) — the ONLY difference is the credential: service here, the caller's own for /self/*.
package companion

import (
	"bytes"
	"io"
	"net/http"
	"strings"
)

// registerFineractPassthroughRoutes wires the COMP-PROXY staff passthroughs. Called from RegisterRoutes.
func (h *Handler) registerFineractPassthroughRoutes(mux *http.ServeMux) {
	// {groupId}/{clientId} are single-segment patterns — distinct from the deeper
	// /groups/{groupId}/clients + /clients/{clientId}/accounts routes (Go 1.22 dispatches by depth).
	mux.HandleFunc("GET /groups/{groupId}", h.handleFineractServiceProxy)
	mux.HandleFunc("POST /clients", h.handleFineractServiceProxy)
	mux.HandleFunc("GET /clients/{clientId}", h.handleFineractServiceProxy)
	mux.HandleFunc("POST /clients/{clientId}/images", h.handleFineractServiceProxy)
}

// handleFineractServiceProxy forwards the request to Fineract with the SERVICE credential.
func (h *Handler) handleFineractServiceProxy(w http.ResponseWriter, r *http.Request) {
	h.proxyToFineract(w, r, true)
}

// proxyToFineract is a verbatim reverse-proxy to Fineract at BaseURL + the incoming path: it forwards
// the method, path, query, request body, and Content-Type, and streams the upstream status +
// Content-Type + body back unchanged. serviceAuth=true attaches the shared service BasicAuth (staff
// operations the app can't perform itself); serviceAuth=false forwards the CALLER's own Authorization
// header (the /self/* native self-service surface). The tenant header is always injected.
func (h *Handler) proxyToFineract(w http.ResponseWriter, r *http.Request, serviceAuth bool) {
	target := h.Fineract.BaseURL + r.URL.Path
	q := r.URL.RawQuery
	// Fineract serializes dates as [year,month,day] ARRAYS by default, but the app's DTOs parse date
	// fields (activationDate, timeline, ...) as yyyy-MM-dd STRINGS — so a raw read deserialization-
	// fails ("Expected beginning of the string, but got [" — member-profile getClient /clients/{id}).
	// Ask Fineract for string dates on every GET read so raw passthroughs match the app's convention.
	if r.Method == http.MethodGet && !strings.Contains(q, "dateFormat=") {
		if q != "" {
			q += "&"
		}
		q += "dateFormat=yyyy-MM-dd&locale=en"
	}
	if q != "" {
		target += "?" + q
	}

	var body io.Reader
	if r.Body != nil {
		buf, _ := io.ReadAll(r.Body)
		body = bytes.NewReader(buf)
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, body)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "build proxy request: "+err.Error())
		return
	}
	if serviceAuth {
		req.SetBasicAuth(h.Fineract.Username, h.Fineract.Password)
	} else if auth := r.Header.Get("Authorization"); auth != "" {
		// Native self-service (/self/*) — act as the logged-in member, never the service account.
		req.Header.Set("Authorization", auth)
	}
	// Preserve the caller's Content-Type verbatim — critical for the multipart photo upload whose
	// header carries the form-data boundary; default to JSON only when the caller sent none.
	if ct := r.Header.Get("Content-Type"); ct != "" {
		req.Header.Set("Content-Type", ct)
	} else {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("fineract-platform-tenantid", h.Fineract.TenantID)

	resp, err := h.Fineract.HTTP.Do(req)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "fineract proxy: "+err.Error())
		return
	}
	defer resp.Body.Close()
	raw, _ := io.ReadAll(resp.Body)

	if ct := resp.Header.Get("Content-Type"); ct != "" {
		w.Header().Set("Content-Type", ct)
	} else {
		setJSON(w)
	}
	w.WriteHeader(resp.StatusCode)
	_, _ = w.Write(raw)
}
