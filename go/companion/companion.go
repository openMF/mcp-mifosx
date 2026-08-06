// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// Package companion is a thin REST facade the MifosSave app talks to for
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
	"runtime/debug"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

// Handler owns the companion REST routes. It borrows BaseURL / TenantID / HTTP
// from the shared FineractClient but deliberately does NOT use DoRequest, which
// would inject the service BasicAuth and mask a bad end-user password.
type Handler struct {
	Fineract *adapter.FineractClient
	// selfMux is the companion's own router, retained so the offline-sync /batches drain can
	// SELF-DISPATCH each queued write through the same mapping handlers that serve the live path.
	selfMux *http.ServeMux
}

// New builds a companion Handler bound to the shared Fineract client.
func New(f *adapter.FineractClient) *Handler {
	return &Handler{Fineract: f}
}

// HandleBuild returns the git revision + build metadata embedded by `go build` (module-aware
// builds from a git checkout record vcs.revision/vcs.time automatically). This is the deploy-truth
// probe: hit GET /companion/build and compare .revision to the pushed HEAD to know exactly which
// commit onrender is serving — no guessing whether a redeploy landed.
func (h *Handler) HandleBuild(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	out := map[string]string{"revision": "unknown", "time": "", "modified": ""}
	if bi, ok := debug.ReadBuildInfo(); ok {
		for _, s := range bi.Settings {
			switch s.Key {
			case "vcs.revision":
				out["revision"] = s.Value
			case "vcs.time":
				out["time"] = s.Value
			case "vcs.modified":
				out["modified"] = s.Value
			}
		}
	}
	_ = json.NewEncoder(w).Encode(out)
}

// RegisterRoutes wires the companion endpoints onto an existing mux. The mux's
// outer handler already sets the CORS headers, so these routes inherit them.
func (h *Handler) RegisterRoutes(mux *http.ServeMux) {
	mux.HandleFunc("/companion/auth/login", h.HandleLogin)
	mux.HandleFunc("/companion/auth/self-register", h.HandleSelfRegister)
	mux.HandleFunc("/companion/auth/me", h.HandleMe)
	// COMP-BUILD: deploy-truth probe — returns the VCS revision baked in at build time so we can
	// prove WHICH commit onrender is actually running (no more "is the deploy live?" guessing).
	mux.HandleFunc("/companion/build", h.HandleBuild)

	// COMP-GRP: group-dashboard read facade (see groups.go).
	h.registerGroupRoutes(mux)
	// COMP-GRP-001: group-CREATE write facade (see group_create.go).
	h.registerGroupCreateRoutes(mux)
	// COMP-DT-002/003/005: member-INVITE write facade (see member_invite.go).
	h.registerMemberInviteRoutes(mux)
	h.registerAssociateRoutes(mux)
	h.registerLoanProductRoutes(mux)
	h.registerLoanApplicationRoutes(mux)
	h.registerMemberAddRoutes(mux)
	h.registerBatchRoutes(mux)
	h.registerOrganizerRoutes(mux)

	// COMP-MEMBERLIST / COMP-SAVINGS / COMP-MEMBERDASH / COMP-LOANLIST: group-scoped read
	// facades the MifosSave app fans in (see member_list.go / savings.go /
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
	// COMP-CHANGEPW: change-password via SERVICE creds (replaces the /self/user/updatePassword call,
	// which 403s for client-only users). Registered BEFORE the /self/ subtree so the specific
	// PUT /companion/self/user/updatePassword wins — though it is a distinct /companion/ prefix anyway.
	h.registerChangePasswordRoutes(mux)
	// COMP-SELF: native self-service passthrough (/self/* forwarded with the caller's own creds).
	h.registerSelfPassthroughRoutes(mux)
	// COMP-FO: field-officer staff facade (groups-by-staff + FieldOfficerGroupReport).
	h.registerFieldOfficerRoutes(mux)
	// COMP-DT-ROLE: dt_member_role datatable facade (assign/read/update a member's group role).
	h.registerMemberRoleRoutes(mux)
	// COMP-PROXY: bare-Fineract staff passthroughs (group members, client create/read/photo).
	h.registerFineractPassthroughRoutes(mux)
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

	memberships, gerr := h.resolveGroups(fa)
	if gerr != nil {
		// Credentials were valid but the group listing flapped — return a retryable 502 rather than a
		// successful login with an empty membership set (which the app renders as the zero-groups
		// screen, wrongly telling a real member they belong to nothing).
		writeErr(w, http.StatusBadGateway, "upstream_error", "resolve groups: "+gerr.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(AuthResponse{
		UserID:           fmt.Sprintf("%d", fa.UserID),
		SessionToken:     fa.Base64Key,
		TokenExpiresAt:   time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339),
		GroupMemberships: memberships,
	})
}

// HandleSelfRegister lives in self_register.go — it provisions a REAL Fineract
// client + login user via the service credential so a brand-new MifosSave
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
	memberships, gerr := h.resolveGroups(fa)
	if gerr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "resolve groups: "+gerr.Error())
		return
	}
	_ = json.NewEncoder(w).Encode(meResponse{
		UserID:           fmt.Sprintf("%d", fa.UserID),
		Name:             h.resolveDisplayName(fa), // real "Firstname Lastname", not the raw username/phone
		EmailPhone:       fa.Username,
		GroupMemberships: memberships,
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
// userClientIDs returns the Fineract client id(s) that belong to the logged-in person, resolved
// via the client's externalId (== the account email OR phone, set at self-registration). Match on
// externalId for BOTH email and phone usernames — a phone-registered member (e.g. +2547…) was
// previously dropped by an email-only `@` gate and wrongly given the unscoped all-groups (staff)
// view, which made /companion/groups/mine aggregate every group and time out. Admin/staff users
// (username has no matching client — e.g. the seeded `mifos` organizer) still get nil → staff view.
func (h *Handler) userClientIDs(fa *fineractAuthResponse) map[int64]bool {
	if fa == nil || strings.TrimSpace(fa.Username) == "" {
		return nil
	}
	raw, err := h.Fineract.DoRequest("GET", "clients", nil, map[string]string{"externalId": fa.Username})
	if err != nil {
		return nil
	}
	set := make(map[int64]bool)
	var paged struct {
		PageItems []struct {
			ID         int64  `json:"id"`
			ExternalID string `json:"externalId"`
		} `json:"pageItems"`
	}
	if json.Unmarshal(raw, &paged) == nil {
		for _, c := range paged.PageItems {
			// Guard against a Fineract build that ignores the externalId filter and returns all.
			if c.ExternalID == fa.Username {
				set[c.ID] = true
			}
		}
	}
	// GET /clients?externalId= can also come back as a BARE ARRAY (not the paged envelope),
	// depending on the Fineract build. If the paged decode found nothing, try the array shape —
	// otherwise a phone/email member silently resolves to zero clients and falls through to the
	// staff-all-groups path (the /groups/mine timeout + login-shows-39-memberships bug).
	if len(set) == 0 {
		var arr []struct {
			ID         int64  `json:"id"`
			ExternalID string `json:"externalId"`
		}
		if json.Unmarshal(raw, &arr) == nil {
			for _, c := range arr {
				if c.ExternalID == fa.Username {
					set[c.ID] = true
				}
			}
		}
	}
	if len(set) == 0 {
		return nil
	}
	return set
}

// resolveGroups returns the caller's group memberships with per-user data isolation:
//   - a self-service user (has a linked client) sees ONLY groups their client belongs to;
//   - an admin/staff user (no linked client, e.g. the seeded `mifos` organizer) sees all
//     active groups (the organizer/back-office view).
func (h *Handler) resolveGroups(fa *fineractAuthResponse) ([]GroupMembership, error) {
	out := []GroupMembership{}
	raw, err := h.Fineract.DoRequest("GET", "groups", nil, nil)
	if err != nil {
		// The primary group listing failed (a mifos-bank-2 502 flap outlasting DoRequest's retry).
		// Do NOT fail-soft to an empty list — empty is INDISTINGUISHABLE from "genuinely no groups"
		// and strands a real member on the zero-groups screen. Surface the error so the caller
		// returns a retryable 502 instead of a false empty membership set.
		return nil, fmt.Errorf("list groups: %s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var groups []fnGroup
	if err := json.Unmarshal(raw, &groups); err != nil {
		return nil, fmt.Errorf("decode groups: %w", err)
	}
	var clientIDs map[int64]bool
	scopedRole := "ORGANIZER"
	if ids := h.userClientIDs(fa); len(ids) > 0 {
		clientIDs = ids
		scopedRole = "MEMBER" // a client-member is a MEMBER unless a group role says otherwise
	}

	// Admin/staff (no linked client) → all active groups as ORGANIZER, no per-group membership
	// probe needed (the cheap path).
	if clientIDs == nil {
		for _, g := range groups {
			if !g.Active {
				continue
			}
			out = append(out, GroupMembership{
				GroupID:   strconv.FormatInt(g.ID, 10),
				GroupName: g.Name,
				Role:      "ORGANIZER",
				JoinedAt:  fmtFineractInstant(g.ActivationDate),
			})
		}
		return out, nil
	}

	// Self-service member: probe each active group's client list to isolate the user's groups.
	// These probes are independent Fineract reads (~0.6s each) — running them SERIALLY made login
	// the slowest screen (~6s for ~10 groups). Fan them out concurrently (own result slot per
	// group; assemble in group order afterward) so login costs ~one probe's latency.
	active := make([]fnGroup, 0, len(groups))
	for _, g := range groups {
		if g.Active {
			active = append(active, g)
		}
	}
	// Resolve the caller's REAL per-group committee role from dt_member_role (one read per linked
	// client). Without this every client-member came back as plain "MEMBER", so an organizer /
	// treasurer / chair / secretary was mis-routed to the personal dashboard instead of the
	// organizer dashboard (the app routes any leadership role → organizer-dashboard).
	roleByGroup := h.memberRolesByGroup(clientIDs)
	memberships := make([]*GroupMembership, len(active))
	var wg sync.WaitGroup
	for i, g := range active {
		wg.Add(1)
		go func(i int, g fnGroup) {
			defer wg.Done()
			members, mErr := h.groupClients(g.ID)
			if mErr != nil {
				return
			}
			for _, m := range members {
				if clientIDs[m.ID] {
					role := scopedRole
					if r, ok := roleByGroup[strconv.FormatInt(g.ID, 10)]; ok {
						role = r
					}
					memberships[i] = &GroupMembership{
						GroupID:   strconv.FormatInt(g.ID, 10),
						GroupName: g.Name,
						Role:      role,
						// The app parses joinedAt with kotlinx Instant.parse (LoginSignupMappers.kt:52),
						// which requires a full ISO-8601 instant — a date-only string crashes it.
						JoinedAt: fmtFineractInstant(g.ActivationDate),
					}
					return
				}
			}
		}(i, g)
	}
	wg.Wait()
	for _, m := range memberships {
		if m != nil {
			out = append(out, *m)
		}
	}
	return out, nil
}

// memberRolesByGroup reads the caller's dt_member_role rows (one read per linked client — usually
// exactly one) and returns a group_id(string) -> normalized-role map. A group with several rows for
// the client keeps the strongest (a leadership row wins over a plain MEMBER row). Best-effort: a read
// failure for one client just omits its rows (the caller falls back to the default scoped role).
func (h *Handler) memberRolesByGroup(clientIDs map[int64]bool) map[string]string {
	roles := map[string]string{}
	for cid := range clientIDs {
		raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/dt_member_role/%d", cid), nil, nil)
		if err != nil {
			continue
		}
		var rows []struct {
			GroupID int64  `json:"group_id"`
			Role    string `json:"role"`
		}
		if json.Unmarshal(raw, &rows) != nil {
			continue
		}
		for _, row := range rows {
			if row.GroupID <= 0 || strings.TrimSpace(row.Role) == "" {
				continue
			}
			gid := strconv.FormatInt(row.GroupID, 10)
			norm := normalizeGroupRole(row.Role)
			if existing, ok := roles[gid]; !ok || (existing == "MEMBER" && norm != "MEMBER") {
				roles[gid] = norm
			}
		}
	}
	return roles
}

// normalizeGroupRole maps a raw dt_member_role value onto the app's GroupRole enum
// (ORGANIZER | MEMBER | TREASURER | SECRETARY). CHAIRPERSON has no app-enum member, so it folds onto
// ORGANIZER (a chair is an organizer-level leader); any unrecognized value is treated as MEMBER so an
// unknown role never accidentally grants the organizer view.
func normalizeGroupRole(role string) string {
	switch strings.ToUpper(strings.TrimSpace(role)) {
	case "ORGANIZER", "CHAIRPERSON", "CHAIR":
		return "ORGANIZER"
	case "TREASURER":
		return "TREASURER"
	case "SECRETARY":
		return "SECRETARY"
	default:
		return "MEMBER"
	}
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
