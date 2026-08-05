// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-SELF: native self-service passthrough. Fineract's /self/* surface authenticates the END
// USER (own profile / own savings / own loans / change own password), so — unlike EVERY other
// companion facade, which runs a staff/service operation with the shared service credential
// (adapter.DoRequest) — this handler forwards the caller's OWN Authorization header straight
// through to Fineract. That is the whole point of a self-service endpoint: it acts as the logged-in
// member, not as the back-office service account.
//
// The MifosSave (mifos-x-group-banking) app is single-host — its shared HttpClient base URL points
// at THIS companion — and its CompanionAuthHeaderPlugin attaches
// `Authorization: Basic <base64(username:password)>` (the Fineract base64EncodedAuthenticationKey
// the login flow stored), which is exactly the credential Fineract self-service expects. Keeping
// /self/* on the companion means the app never talks to Fineract directly (ACCESS_MODEL
// native_self_service). App services that land here:
//   PUT /self/user/updatePassword                       (changepin/ChangePinApiImpl.changePin)
//   GET /self/savingsaccounts/{id}/transactions         (savings/SavingsApiImpl.getSavingsTransactions)
//
// It is a verbatim reverse-proxy: same method + path + query + body + the user's Authorization and
// the tenant header, forwarded to Fineract BaseURL + the same /self/... path, streaming the upstream
// status + Content-Type + body back unchanged. This is the ONLY companion route that forwards the
// user credential rather than the service one.
package companion

import (
	"bytes"
	"io"
	"net/http"
)

// registerSelfPassthroughRoutes wires the /self/* subtree. Called from RegisterRoutes.
func (h *Handler) registerSelfPassthroughRoutes(mux *http.ServeMux) {
	// Go 1.22 subtree pattern (trailing slash) — matches every method + sub-path under /self/.
	mux.HandleFunc("/self/", h.HandleSelfPassthrough)
}

// HandleSelfPassthrough reverse-proxies a /self/* request to Fineract using the caller's OWN
// Authorization header (never the service credential).
func (h *Handler) HandleSelfPassthrough(w http.ResponseWriter, r *http.Request) {
	// BaseURL already carries the /fineract-provider/api/v1 prefix (adapter.New trims any trailing
	// slash), so r.URL.Path (/self/...) appends cleanly with a single separator.
	target := h.Fineract.BaseURL + r.URL.Path
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}

	var body io.Reader
	if r.Body != nil {
		buf, _ := io.ReadAll(r.Body)
		body = bytes.NewReader(buf)
	}

	req, err := http.NewRequestWithContext(r.Context(), r.Method, target, body)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "build self request: "+err.Error())
		return
	}
	// Forward the END-USER credential (NOT the service credential) — the whole point of /self/*.
	if auth := r.Header.Get("Authorization"); auth != "" {
		req.Header.Set("Authorization", auth)
	}
	if ct := r.Header.Get("Content-Type"); ct != "" {
		req.Header.Set("Content-Type", ct)
	} else {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("fineract-platform-tenantid", h.Fineract.TenantID)

	resp, err := h.Fineract.HTTP.Do(req)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "self passthrough: "+err.Error())
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
