// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package companion

// Raw Fineract passthroughs the MifosSave app calls at their bare paths (not
// under /companion/…). The app's GroupCreateApi fetches offices via GET /offices
// (list_all_offices) to populate the create-group wizard's Office dropdown; the
// app's OfficeDto is {id,name,nameDecorated,externalId}, a subset of Fineract's
// office shape (extra fields ignored by the app's ignoreUnknownKeys). We forward
// Fineract's response verbatim.

import "net/http"

func (h *Handler) registerPassthroughRoutes(mux *http.ServeMux) {
	mux.HandleFunc("GET /offices", h.HandleOffices)
}

// HandleOffices proxies GET /offices to Fineract (service credential) and returns
// the office list the create-group wizard's Office dropdown consumes.
func (h *Handler) HandleOffices(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	raw, err := h.Fineract.DoRequest("GET", "offices", nil, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	_, _ = w.Write(raw)
}
