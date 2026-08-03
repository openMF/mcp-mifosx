// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-AUTH-SIGNUP: real sign-up for the CommonPurse (mifos-x-group-banking)
// app. The app's CompanionAuthApiImpl POSTs
//
//	POST /companion/auth/self-register  {name, emailPhone, password}  ->  AuthResponse
//
// and expects the SAME AuthResponse contract as /login {userId, sessionToken,
// tokenExpiresAt, groupMemberships[]} — i.e. signup must yield an immediately
// usable session, exactly like login.
//
// Fineract's stock POST /self/registration flow is NOT usable here: it requires
// a pre-existing client whose accountNumber matches (it 400s "accountNumber
// mandatory") AND gates activation behind an email/SMS confirmation token, so no
// synchronous session can be issued. We replace it with an ADMIN-DRIVEN
// provisioning flow that runs against LIVE mifos-bank-2 with the service
// credential (adapter BasicAuth):
//
//	0. GET /roles            -> resolve the "Self Service User" role id (id 2 on mifos-bank-2)
//	1. POST /clients         -> a real active client   {firstname,lastname,officeId:1,active:true,activationDate,legalFormId:1}
//	2. POST /users           -> a real login user       {username:emailPhone, ...,roles:[<self-service>], officeId:1}
//	3. POST /authentication  -> authenticate the new user with its own creds -> AuthResponse
//
// Honesty invariants (mirroring companion.go): every step surfaces the REAL
// Fineract status+body on failure — we NEVER fabricate a session. A duplicate
// username is mapped to a clean 409 the app can surface ("account already
// exists"). groupMemberships is EMPTY for a brand-new user (there is provably no
// membership yet) -> the app's ZeroGroups state, from which the new organizer
// creates their first group.
//
// ---- Fineract shape learned from live mifos-bank-2 validation probes ----
//
//   - Client create uses lowercase `firstname`/`lastname` (NOT firstName), plus
//     officeId, active, activationDate ("dd MMMM yyyy"), dateFormat, locale,
//     legalFormId:1 (PERSON — same lesson group_create.go learned). Returns
//     {clientId,resourceId}.
//   - User create on THIS Fineract fork does NOT support `isSelfServiceUser` or
//     `clientId` params (both 400 "parameter unsupported"). A plain office user
//     (officeId:1) carrying the "Self Service User" role authenticates fine via
//     POST /authentication — which is all the app's login path needs — so we
//     provision that shape. The client and user are both real, created together
//     at signup; the user is not Fineract-linked to the client row, but nothing
//     in the CommonPurse login/organizer flow depends on that linkage.
//   - Usernames MAY contain '@' and '.' — mifos-bank-2 accepts an email verbatim
//     as the username, and POST /authentication authenticates with that same
//     email. So NO derived/sanitized username is needed: emailPhone is stored as
//     the username AND (when it contains '@') the email, and login with the same
//     emailPhone resolves to the same user. (Documented as a real constraint: if
//     a future instance rejects '@' usernames this is where a sanitized-username
//     mapping would be introduced.)
//   - Password policy is a REAL server constraint (12–50 chars, upper+lower+
//     digit+special, no spaces, no consecutive repeats). A non-conforming
//     password is surfaced honestly as Fineract's real 400 — we do NOT transform
//     the password, because login must authenticate with the exact same string
//     the user typed.
package companion

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"
)

// selfServiceRoleName is the Fineract role a signup user is granted so it can
// authenticate as an app end-user. Resolved live from GET /roles (id 2 on
// mifos-bank-2) rather than hardcoded, so it survives a re-seeded instance.
const selfServiceRoleName = "Self Service User"

// HandleSelfRegister provisions a real Fineract client + login user for a new
// CommonPurse signup and returns an immediately-usable AuthResponse.
func (h *Handler) HandleSelfRegister(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	if r.Method != http.MethodPost {
		writeErr(w, http.StatusMethodNotAllowed, "method_not_allowed", "POST required")
		return
	}
	var req selfRegisterRequest
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	emailPhone := strings.TrimSpace(req.EmailPhone)
	if emailPhone == "" || strings.TrimSpace(req.Name) == "" || req.Password == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "name, emailPhone and password are required")
		return
	}

	first, last := splitName(req.Name)

	// 0. Resolve the self-service role id live. A missing role is a real
	// configuration gap, surfaced honestly rather than guessed (assigning a wrong
	// role could over-privilege the signup user).
	roleID, err := h.resolveSelfServiceRoleID()
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "resolve self-service role: "+err.Error())
		return
	}

	// 1. Create the real client (active, PERSON).
	if status, raw, err := h.provisionClient(first, last); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create client: "+err.Error())
		return
	} else if status < 200 || status >= 300 {
		writeUpstreamFailure(w, status, raw)
		return
	}

	// 2. Create the real login user (username == emailPhone).
	status, raw, err := h.provisionUser(emailPhone, first, last, req.Password, roleID)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "create user: "+err.Error())
		return
	}
	if status < 200 || status >= 300 {
		if isDuplicateUsername(raw) {
			w.WriteHeader(http.StatusConflict)
			_ = json.NewEncoder(w).Encode(map[string]interface{}{
				"error":    "account_already_exists",
				"message":  fmt.Sprintf("An account with %q already exists. Please log in instead.", emailPhone),
				"fineract": json.RawMessage(nonEmpty(raw)),
			})
			return
		}
		// Password-policy violation, or any other real rejection — mirror it.
		writeUpstreamFailure(w, status, raw)
		return
	}

	// 3. Authenticate the freshly-created user with its own credentials and issue
	// the same AuthResponse the app gets from /login. groupMemberships is empty:
	// a brand-new user provably belongs to no group yet (ZeroGroups state).
	fa, aStatus, aRaw, err := h.fineractAuthenticate(emailPhone, req.Password)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "authenticate new user: "+err.Error())
		return
	}
	if aStatus != http.StatusOK || fa == nil || !fa.Authenticated {
		writeUpstreamFailure(w, aStatus, aRaw)
		return
	}

	_ = json.NewEncoder(w).Encode(AuthResponse{
		UserID:           fmt.Sprintf("%d", fa.UserID),
		SessionToken:     fa.Base64Key,
		TokenExpiresAt:   time.Now().Add(24 * time.Hour).UTC().Format(time.RFC3339),
		GroupMemberships: []GroupMembership{},
	})
}

// ---- provisioning calls (service credential, real status exposed) ----

// resolveSelfServiceRoleID reads GET /roles and returns the id of the
// "Self Service User" role (case-insensitive exact match, else the first role
// whose name contains "self service"). Errors honestly if none is found.
func (h *Handler) resolveSelfServiceRoleID() (int, error) {
	status, raw, err := h.fineractAdminGet("/roles")
	if err != nil {
		return 0, err
	}
	if status != http.StatusOK {
		return 0, fmt.Errorf("GET /roles returned %d: %s", status, strings.TrimSpace(string(raw)))
	}
	var roles []struct {
		ID       int    `json:"id"`
		Name     string `json:"name"`
		Disabled bool   `json:"disabled"`
	}
	if err := json.Unmarshal(raw, &roles); err != nil {
		return 0, fmt.Errorf("decode roles: %w", err)
	}
	var fuzzy int
	for _, role := range roles {
		if role.Disabled {
			continue
		}
		if strings.EqualFold(role.Name, selfServiceRoleName) {
			return role.ID, nil
		}
		if fuzzy == 0 && strings.Contains(strings.ToLower(role.Name), "self service") {
			fuzzy = role.ID
		}
	}
	if fuzzy != 0 {
		return fuzzy, nil
	}
	return 0, fmt.Errorf("no %q role found on this Fineract instance", selfServiceRoleName)
}

// provisionClient creates a real active PERSON client in office 1.
func (h *Handler) provisionClient(first, last string) (int, []byte, error) {
	today := time.Now().Format("02 January 2006") // Fineract "dd MMMM yyyy"
	body := map[string]interface{}{
		"firstname":      first,
		"lastname":       last,
		"officeId":       1,
		"active":         true,
		"activationDate": today,
		"dateFormat":     "dd MMMM yyyy",
		"locale":         "en",
		"legalFormId":    1, // PERSON
	}
	return h.fineractAdminPost("/clients", body)
}

// provisionUser creates a real login user whose username is emailPhone. On this
// Fineract fork isSelfServiceUser/clientId are unsupported, so we provision a
// plain office-1 user carrying the self-service role — it authenticates via
// POST /authentication, which is all the app's login path consumes.
func (h *Handler) provisionUser(username, first, last, password string, roleID int) (int, []byte, error) {
	body := map[string]interface{}{
		"username":            username,
		"firstname":           first,
		"lastname":            last,
		"password":            password,
		"repeatPassword":      password,
		"sendPasswordToEmail": false,
		"roles":               []int{roleID},
		"officeId":            1,
	}
	if strings.Contains(username, "@") {
		body["email"] = username
	}
	return h.fineractAdminPost("/users", body)
}

// isDuplicateUsername reports whether a Fineract error body is the
// username-already-exists integrity violation.
func isDuplicateUsername(raw []byte) bool {
	s := string(raw)
	return strings.Contains(s, "error.msg.user.duplicate.username") ||
		strings.Contains(strings.ToLower(s), "already exists")
}

// ---- service-credential HTTP (exposes the real status code) ----
//
// adapter.DoRequest collapses the status into an "API Error %d" error, which we
// cannot mirror faithfully. These two helpers make the same service-authenticated
// call but return the raw (status, body) so a failing provisioning step can pass
// Fineract's real status+body straight back to the app.

func (h *Handler) fineractAdminPost(endpoint string, body interface{}) (int, []byte, error) {
	payload, err := json.Marshal(body)
	if err != nil {
		return 0, nil, err
	}
	return h.fineractAdminDo(http.MethodPost, endpoint, bytes.NewReader(payload))
}

func (h *Handler) fineractAdminGet(endpoint string) (int, []byte, error) {
	return h.fineractAdminDo(http.MethodGet, endpoint, nil)
}

func (h *Handler) fineractAdminDo(method, endpoint string, reqBody io.Reader) (int, []byte, error) {
	url := h.Fineract.BaseURL + "/" + strings.TrimPrefix(endpoint, "/")
	req, err := http.NewRequest(method, url, reqBody)
	if err != nil {
		return 0, nil, err
	}
	req.SetBasicAuth(h.Fineract.Username, h.Fineract.Password)
	req.Header.Set("fineract-platform-tenantid", h.Fineract.TenantID)
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Accept", "application/json")

	resp, err := h.Fineract.HTTP.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer resp.Body.Close()
	raw, err := io.ReadAll(resp.Body)
	if err != nil {
		return resp.StatusCode, nil, err
	}
	return resp.StatusCode, raw, nil
}
