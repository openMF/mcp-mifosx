// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// Package companion is a thin REST facade the CommonPurse app talks to for
// authentication. It sits IN FRONT of Fineract: it forwards the end-user's
// credentials to Fineract's POST /authentication endpoint (rather than the
// service credential the shared adapter.DoRequest forces) so a wrong password
// genuinely surfaces as a 401, and reshapes the Fineract response into the
// AuthResponse contract the app's CompanionAuthApiImpl expects.
package companion

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

// Handler owns the companion REST routes. It borrows BaseURL / TenantID / HTTP
// from the shared FineractClient but deliberately does NOT use DoRequest, which
// would inject the service BasicAuth and mask a bad end-user password.
type Handler struct {
	Fineract *adapter.FineractClient
}

// New builds a companion Handler bound to the shared Fineract client.
func New(f *adapter.FineractClient) *Handler {
	return &Handler{Fineract: f}
}

// RegisterRoutes wires the companion endpoints onto an existing mux. The mux's
// outer handler already sets the CORS headers, so these routes inherit them.
func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/companion/auth/login", h.HandleLogin)
	mux.HandleFunc("/companion/auth/self-register", h.HandleSelfRegister)
	mux.HandleFunc("/companion/auth/me", h.HandleMe)

	// COMP-GRP: group-dashboard read facade (see groups.go).
	h.registerGroupRoutes(mux)
	// COMP-GRP-001: group-CREATE write facade (see group_create.go).
	h.registerGroupCreateRoutes(mux)
	// COMP-DT-002/003/005: member-INVITE write facade (see member_invite.go).
	h.registerMemberInviteRoutes(mux)
	h.registerOrganizerRoutes(mux)

	// COMP-MEMBERLIST / COMP-SAVINGS / COMP-MEMBERDASH / COMP-LOANLIST: group-scoped read
	// facades the CommonPurse app fans in (see member_list.go / savings.go /
	// member_dashboard.go / loan_list.go).
	h.registerMemberListRoutes(mux)
	h.registerSavingsRoutes(mux)
	h.registerMemberDashboardRoutes(mux)
	h.registerLoanListRoutes(mux)
	// COMP-LOAN-WRITE: loan apply / repayment / writeoff write facade (see loan_write.go).
	h.registerLoanWriteRoutes(mux)
	// COMP-DIST-001/002: share-out / rotation-payout write facade (see share_out_write.go).
	h.registerShareOutRoutes(mux)
	h.registerShareOutPreviewRoutes(mux)
	// COMP-LOANDETAIL: single-loan detail read facade (see loan_detail.go).
	h.registerLoanDetailRoutes(mux)
	h.registerGroupTypeCatalogRoutes(mux)
	h.registerPassthroughRoutes(mux)
	// COMP-CAL: meeting-lifecycle facade (calendar/conduct/summary/previous-review + reschedule;
	// see meeting.go).
	h.registerMeetingRoutes(mux)
}

// ---- Wire contract (exactly what the app sends / expects) ----

type loginRequest struct {
	EmailPhone string `json:"emailPhone"`
	Password   string `json:"password"`
}

type selfRegisterRequest struct {
	Name       string `json:"name"`
	EmailPhone string `json:"emailPhone"`
	Password   string `json:"password"`
}

// GroupMembership mirrors the app's group-membership DTO.
type GroupMembership struct {
	GroupID   string `json:"groupId"`
	GroupName string `json:"groupName"`
	Role      string `json:"role"`
	JoinedAt  string `json:"joinedAt"`
}

// AuthResponse is the shape returned by /login and /self-register on success.
type AuthResponse struct {
	UserID           string            `json:"userId"`
	SessionToken     string            `json:"sessionToken"`
	TokenExpiresAt   string            `json:"tokenExpiresAt"`
	GroupMemberships []GroupMembership `json:"groupMemberships"`
}

type meResponse struct {
	UserID           string            `json:"userId"`
	Name             string            `json:"name"`
	EmailPhone       string            `json:"emailPhone"`
	GroupMemberships []GroupMembership `json:"groupMemberships"`
}

// ---- Fineract wire types ----

type fineractAuthResponse struct {
	Username      string `json:"username"`
	UserID        int64  `json:"userId"`
	Base64Key     string `json:"base64EncodedAuthenticationKey"`
	Authenticated bool   `json:"authenticated"`
	Roles         []struct {
		Name string `json:"name"`
	} `json:"roles"`
}

// ---- Handlers ----

// HandleLogin authenticates emailPhone/password against Fineract and returns an
// AuthResponse. Wrong credentials -> 401.
func (h *Handler) HandleLogin(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "method_not_allowed", "POST required")
		return
	}
	var req loginRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	// Match self-register's lowercase normalisation so a login with the exact email a user typed
	// authenticates against the (case-sensitive) Fineract username stored at registration.
	emailPhone := strings.ToLower(strings.TrimSpace(req.EmailPhone))
	if emailPhone == "" || req.Password == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "emailPhone and password are required")
		return
	}

	fa, status, raw, err := h.fineractAuthenticate(emailPhone, req.Password)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if status == http.StatusUnauthorized {
		writeErr(w, http.StatusUnauthorized, "invalid_credentials", "Invalid email/phone or password")
		return
	}
	if status != http.StatusOK || fa == nil || !fa.Authenticated {
		// Pass the upstream failure through honestly rather than fabricate a success.
		writeUpstreamFailure(w, status, raw)
		return
	}

	_ = json.NewEncoder(w).Encode(AuthResponse{
		UserID:           fmt.Sprintf("%d", fa.UserID),
		SessionToken:     fa.Base64Key,
		TokenExpiresAt:   time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339),
		GroupMemberships: h.resolveGroups(fa),
	})
}

// HandleSelfRegister lives in self_register.go — it provisions a REAL Fineract
// client + login user via the service credential so a brand-new CommonPurse
// signup yields an immediately-usable session (same AuthResponse as login).

// HandleMe resolves the caller from a bearer token. Fineract's
// base64EncodedAuthenticationKey IS base64(username:password), so we decode it
// and re-authenticate to obtain userId/username live.
func (h *Handler) HandleMe(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	token := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	if token == "" {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "missing bearer token")
		return
	}
	username, password, ok := decodeSessionToken(token)
	if !ok {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "malformed session token")
		return
	}
	fa, status, _, err := h.fineractAuthenticate(username, password)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if status != http.StatusOK || fa == nil || !fa.Authenticated {
		writeErr(w, http.StatusUnauthorized, "unauthorized", "token invalid or expired")
		return
	}
	_ = json.NewEncoder(w).Encode(meResponse{
		UserID:           fmt.Sprintf("%d", fa.UserID),
		Name:             h.resolveDisplayName(fa), // real "Firstname Lastname", not the raw username/phone
		EmailPhone:       fa.Username,
		GroupMemberships: h.resolveGroups(fa),
	})
}

// ---- Fineract calls (end-user creds, NOT the service credential) ----

func (h *Handler) fineractAuthenticate(username, password string) (*fineractAuthResponse, int, []byte, error) {
	status, raw, err := h.fineractPost("/authentication", map[string]string{
		"username": username,
		"password": password,
	})
	if err != nil {
		return nil, 0, nil, err
	}
	if status != http.StatusOK {
		return nil, status, raw, nil
	}
	var fa fineractAuthResponse
	if err := json.Unmarshal(raw, &fa); err != nil {
		return nil, status, raw, fmt.Errorf("decode fineract auth response: %w", err)
	}
	return &fa, status, raw, nil
}

func (h *Handler) fineractPost(endpoint string, body interface{}) (int, []byte, error) {
	url := h.Fineract.BaseURL + "/" + strings.TrimPrefix(endpoint, "/")
	payload, err := json.Marshal(body)
	if err != nil {
		return 0, nil, err
	}
	// This path is only /authentication (login/me/self-register auth) — side-effect-free,
	// so a transient gateway 5xx or transport blip from the flaky live Fineract is retried
	// (bounded, short backoff) rather than surfaced to the app as a spurious login failure.
	const maxAttempts = 3
	var status int
	var raw []byte
	var lastErr error
	for attempt := 1; attempt <= maxAttempts; attempt++ {
		req, rerr := http.NewRequest(http.MethodPost, url, bytes.NewReader(payload))
		if rerr != nil {
			return 0, nil, rerr
		}
		req.Header.Set("Content-Type", "application/json")
		req.Header.Set("Accept", "application/json")
		req.Header.Set("fineract-platform-tenantid", h.Fineract.TenantID)

		resp, derr := h.Fineract.HTTP.Do(req)
		if derr != nil {
			lastErr = derr
			if attempt < maxAttempts {
				time.Sleep(time.Duration(attempt) * 400 * time.Millisecond)
				continue
			}
			return 0, nil, derr
		}
		raw, err = io.ReadAll(resp.Body)
		resp.Body.Close()
		status = resp.StatusCode
		if err != nil {
			return status, nil, err
		}
		if (status == http.StatusBadGateway || status == http.StatusServiceUnavailable ||
			status == http.StatusGatewayTimeout) && attempt < maxAttempts {
			lastErr = fmt.Errorf("upstream %d", status)
			time.Sleep(time.Duration(attempt) * 400 * time.Millisecond)
			continue
		}
		return status, raw, nil
	}
	return status, raw, lastErr
}

// resolveGroups returns the caller's group memberships. mifos-bank-2 exposes no
// per-user member<->group linkage for staff callers, so — mirroring HandleMyGroups
// (/companion/groups/mine) — an organizer/staff caller resolves to every ACTIVE
// group as ORGANIZER (the staff/service account is the organizer of the groups it
// administers via the companion). Fail-soft: any upstream error returns an empty
// (never nil) slice so login still succeeds → the app's ZeroGroups branch. This is
// the documented hook for real per-user resolution once member linkage lands.
func (h *Handler) resolveGroups(_ *fineractAuthResponse) []GroupMembership {
	out := []GroupMembership{}
	raw, err := h.Fineract.DoRequest("GET", "groups", nil, nil)
	if err != nil {
		return out
	}
	var groups []fnGroup
	if err := json.Unmarshal(raw, &groups); err != nil {
		return out
	}
	for _, g := range groups {
		if !g.Active {
			continue
		}
		out = append(out, GroupMembership{
			GroupID:   strconv.FormatInt(g.ID, 10),
			GroupName: g.Name,
			Role:      "ORGANIZER",
			// The app parses joinedAt with kotlinx Instant.parse (LoginSignupMappers.kt:52),
			// which requires a full ISO-8601 instant — a date-only string crashes it.
			JoinedAt: fmtFineractInstant(g.ActivationDate),
		})
	}
	return out
}

// callerFromRequest resolves the authenticated caller from the Authorization
// bearer token (best-effort; nil when the header is absent, malformed, or the
// token no longer authenticates). Reuses the same base64(username:password)
// session-token scheme as HandleMe, so any companion endpoint can identify the
// real end-user behind the request without a new param.
func (h *Handler) callerFromRequest(r *http.Request) *fineractAuthResponse {
	token := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer "))
	if token == "" {
		return nil
	}
	username, password, ok := decodeSessionToken(token)
	if !ok {
		return nil
	}
	fa, status, _, err := h.fineractAuthenticate(username, password)
	if err != nil || status != http.StatusOK || fa == nil || !fa.Authenticated {
		return nil
	}
	return fa
}

// resolveDisplayName looks up the caller's human display name ("Firstname
// Lastname") from Fineract's GET /users/{id} (service credential), falling back
// to the username when the lookup yields nothing. Best-effort and never errors:
// personalization must never break a load. Empty string only when fa is nil.
func (h *Handler) resolveDisplayName(fa *fineractAuthResponse) string {
	if fa == nil {
		return ""
	}
	if raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("users/%d", fa.UserID), nil, nil); err == nil {
		var u struct {
			Firstname string `json:"firstname"`
			Lastname  string `json:"lastname"`
		}
		if json.Unmarshal(raw, &u) == nil {
			name := strings.TrimSpace(strings.TrimSpace(u.Firstname) + " " + strings.TrimSpace(u.Lastname))
			if name != "" {
				return name
			}
		}
	}
	return fa.Username
}

// ---- helpers ----

func setJSON(w http.ResponseWriter) {
	w.Header().Set("Content-Type", "application/json")
}

func writeErr(w http.ResponseWriter, status int, code, message string) {
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(map[string]string{"error": code, "message": message})
}

// writeUpstreamFailure mirrors a non-2xx Fineract response, defaulting to 401.
func writeUpstreamFailure(w http.ResponseWriter, status int, raw []byte) {
	if status < 400 {
		status = http.StatusUnauthorized
	}
	w.WriteHeader(status)
	if len(raw) > 0 {
		_, _ = w.Write(raw)
		return
	}
	_ = json.NewEncoder(w).Encode(map[string]string{"error": "upstream_error", "message": fmt.Sprintf("fineract returned status %d", status)})
}

func nonEmpty(raw []byte) []byte {
	if len(raw) == 0 {
		return []byte("null")
	}
	return raw
}

// decodeSessionToken decodes base64(username:password) back into its parts.
func decodeSessionToken(token string) (string, string, bool) {
	dec, err := base64.StdEncoding.DecodeString(token)
	if err != nil {
		return "", "", false
	}
	parts := strings.SplitN(string(dec), ":", 2)
	if len(parts) != 2 || parts[0] == "" {
		return "", "", false
	}
	return parts[0], parts[1], true
}

// splitName splits a display name into (first, last); last falls back to first.
func splitName(name string) (string, string) {
	fields := strings.Fields(strings.TrimSpace(name))
	if len(fields) == 0 {
		return "", ""
	}
	if len(fields) == 1 {
		return fields[0], fields[0]
	}
	return fields[0], strings.Join(fields[1:], " ")
}
