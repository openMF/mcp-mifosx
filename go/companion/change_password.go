// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// COMP-CHANGEPW: change-password facade via the SERVICE credential.
//
// The app's change-PIN flow used to PUT the raw Fineract self-service endpoint
// `/self/user/updatePassword`, which 403s for every user who is a Fineract CLIENT but not a
// self-service USER (the seeded + companion-self-registered accounts — a self-service login can only
// reach `/self/*`, never a back-office API, and these users are not provisioned as self-service
// users). Fineract's rule is one-directional: a self-service login cannot call back-office APIs, but
// the back-office (service) credential CAN perform the equivalent operation — here `PUT users/{id}`.
// So the companion resolves the caller's Fineract userId from their session and updates the password
// with the service credential, preserving the single-host contract (the app never talks to Fineract
// directly).
func (h *Handler) registerChangePasswordRoutes(mux *http.ServeMux) {
	mux.HandleFunc("PUT /companion/self/user/updatePassword", h.HandleChangePassword)
}

// HandleChangePassword updates the CALLER's own Fineract user password using the service credential.
// The caller is resolved from the bearer session (never a userId in the path), so a user can only
// ever change their own password.
func (h *Handler) HandleChangePassword(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	fa := h.callerFromRequest(r)
	if fa == nil {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "missing or invalid session token")
		return
	}
	if fa.UserID <= 0 {
		writeErr(w, http.StatusBadGateway, "upstream_error", "could not resolve caller userId")
		return
	}

	var req struct {
		Password       string `json:"password"`
		RepeatPassword string `json:"repeatPassword"`
	}
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid body")
		return
	}
	if strings.TrimSpace(req.Password) == "" || req.Password != req.RepeatPassword {
		writeErr(w, http.StatusBadRequest, "bad_request", "password and repeatPassword must be non-empty and match")
		return
	}

	// PUT users/{userId} with the service credential. Fineract validates against the password policy
	// and rejects a weak password with a 4xx, which the passthrough surfaces to the app unchanged.
	raw, err := h.Fineract.DoRequest("PUT", fmt.Sprintf("users/%d", fa.UserID), map[string]string{
		"password":       req.Password,
		"repeatPassword": req.RepeatPassword,
	}, nil)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", strings.TrimSpace(string(nonEmpty(raw))))
		return
	}

	// Return ONLY the resourceId — never proxy Fineract's raw `changes` block back (it can echo the
	// submitted password field). Matches the app's ChangePinResponseDto{resourceId}.
	_ = json.NewEncoder(w).Encode(map[string]int64{"resourceId": fa.UserID})
}
