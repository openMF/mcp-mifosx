// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"

	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"
)

type failedCapabilityWriter struct {
	*httptest.ResponseRecorder
	writes int
}

func (w *failedCapabilityWriter) Write([]byte) (int, error) {
	w.writes++
	return 0, errors.New("connection closed")
}

func TestHTTPCapabilityEncodingFailureIsLogged(t *testing.T) {
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		io.WriteString(w, `{"authenticated":true,"permissions":["READ_CLIENT"]}`)
	})
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	req := httptest.NewRequest(http.MethodGet, "/api/capabilities", nil)
	req.Header.Set("Authorization", "Bearer capability-secret")
	req.Header.Set("X-Mifos-Tenant-Id", "tenant")
	w := &failedCapabilityWriter{ResponseRecorder: httptest.NewRecorder()}
	h.Config.Handler.ServeHTTP(w, req)
	if w.writes != 1 || !strings.Contains(logs.String(), "[HTTP] encode capabilities response: connection closed") {
		t.Fatal("encoding failure was not logged once without retrying the response")
	}
	if strings.Contains(logs.String(), "capability-secret") {
		t.Fatal("encoding failure log disclosed caller credentials")
	}
}

func capabilityRequest(t *testing.T, baseURL, auth, tenant string) (int, string) {
	t.Helper()
	req, err := http.NewRequest(http.MethodGet, baseURL+"/api/capabilities", nil)
	if err != nil {
		t.Fatal(err)
	}
	req.Header.Set("Authorization", auth)
	req.Header.Set("X-Mifos-Tenant-Id", tenant)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	body, err := io.ReadAll(resp.Body)
	if err != nil {
		t.Fatal(err)
	}
	if resp.Header.Get("Cache-Control") != "no-store" {
		t.Error("caller capability response can be cached")
	}
	if resp.StatusCode == http.StatusOK && resp.Header.Get("Content-Type") != "application/json" {
		t.Error("capabilities did not return JSON")
	}
	return resp.StatusCode, string(body)
}

func assertCapabilities(t *testing.T, body string, available bool) {
	t.Helper()
	reason := ""
	if !available {
		reason = "missing_permission"
	}
	want, err := json.Marshal(struct {
		Tools []tools.ToolCapability `json:"tools"`
	}{Tools: []tools.ToolCapability{
		{Tool: "get_client", Available: available, Reason: reason},
		{Tool: "search_clients", Available: available, Reason: reason},
		{Tool: "get_client_accounts", Available: available, Reason: reason},
	}})
	if err != nil {
		t.Fatal(err)
	}
	if strings.TrimSpace(body) != string(want) {
		t.Errorf("unexpected capability projection: %s", body)
	}
}

func TestHTTPCapabilityResolution(t *testing.T) {
	for _, tc := range []struct {
		name      string
		response  string
		status    int
		available bool
		failure   bool
	}{
		{name: "client read", response: `{"authenticated":true,"permissions":["READ_CLIENT"]}`, available: true},
		{name: "superuser", response: `{"authenticated":true,"permissions":["ALL_FUNCTIONS"]}`, available: true},
		{name: "read superuser", response: `{"authenticated":true,"permissions":["ALL_FUNCTIONS_READ"]}`, available: true},
		{name: "empty permissions", response: `{"authenticated":true,"permissions":[]}`},
		{name: "unrelated permissions", response: `{"authenticated":true,"permissions":["READ_LOAN","READ_CLIENTIMAGE","CREATE_CLIENT","ALL_FUNCTIONS_READONLY","read_client"," READ_CLIENT "]}`},
		{name: "secrets discarded", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"username":"private-officer","base64EncodedAuthenticationKey":"private-key","accessToken":"private-token","Authorization":"Bearer capability-secret"}`, available: true},
		{name: "explicit false gates", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"shouldRenewPassword":false,"isTwoFactorAuthenticationRequired":false}`, available: true},
		{name: "unauthenticated", response: `{"authenticated":false,"permissions":["ALL_FUNCTIONS"]}`, failure: true},
		{name: "no authentication result", response: `{"permissions":["ALL_FUNCTIONS"]}`, failure: true},
		{name: "no permissions", response: `{"authenticated":true}`, failure: true},
		{name: "null permissions", response: `{"authenticated":true,"permissions":null}`, failure: true},
		{name: "permission object", response: `{"authenticated":true,"permissions":{"READ_CLIENT":true}}`, failure: true},
		{name: "malformed permission element", response: `{"authenticated":true,"permissions":["READ_CLIENT",42]}`, failure: true},
		{name: "null permission element", response: `{"authenticated":true,"permissions":["ALL_FUNCTIONS",null]}`, failure: true},
		{name: "blank permission element", response: `{"authenticated":true,"permissions":["ALL_FUNCTIONS",""]}`, failure: true},
		{name: "malformed authentication", response: `{"authenticated":"true","permissions":["ALL_FUNCTIONS"]}`, failure: true},
		{name: "password renewal", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"shouldRenewPassword":true}`, failure: true},
		{name: "two factor required", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"isTwoFactorAuthenticationRequired":true}`, failure: true},
		{name: "alternate two factor field", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"twoFactorAuthenticationRequired":true}`, failure: true},
		{name: "malformed gate", response: `{"authenticated":true,"permissions":["READ_CLIENT"],"shouldRenewPassword":null}`, failure: true},
		{name: "null response", response: `null`, failure: true},
		{name: "non JSON", response: `Bearer capability-secret configured-password`, failure: true},
		{name: "trailing JSON", response: `{"authenticated":true,"permissions":["READ_CLIENT"]}{}`, failure: true},
		{name: "unauthorized", status: 401, failure: true},
		{name: "forbidden", status: 403, failure: true},
		{name: "lookup unsupported", status: 404, failure: true},
		{name: "downstream failure", status: 500, failure: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var calls atomic.Int32
			h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Method != http.MethodGet || r.URL.Path != "/userdetails" || r.Header.Get("Authorization") != "Bearer capability-secret" || r.Header.Get("fineract-platform-tenantid") != "capability-tenant" {
					t.Error("capability lookup used a different identity, tenant, or endpoint")
				}
				if tc.status != 0 {
					w.WriteHeader(tc.status)
					io.WriteString(w, "Bearer capability-secret configured-password")
				} else {
					io.WriteString(w, tc.response)
				}
			})
			status, body := capabilityRequest(t, h.URL, "Bearer capability-secret", "capability-tenant")
			if tc.failure {
				if status != http.StatusBadGateway || body != "caller permissions could not be verified\n" {
					t.Errorf("unverified permissions did not fail closed: %d %s", status, body)
				}
			} else {
				if status != http.StatusOK {
					t.Fatalf("lookup failed: %d %s", status, body)
				}
				assertCapabilities(t, body, tc.available)
			}
			if calls.Load() != 1 {
				t.Fatal("lookup retried or used a credential fallback")
			}
		})
	}
}

func TestHTTPCapabilitiesRequireIdentityAndGET(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	for _, headers := range []http.Header{
		{},
		{"Authorization": {"Bearer capability-secret"}},
		{"X-Mifos-Tenant-Id": {"tenant"}},
		{"Authorization": {" "}, "X-Mifos-Tenant-Id": {"tenant"}},
		{"Authorization": {"Bearer capability-secret"}, "X-Mifos-Tenant-Id": {" "}},
		{"Authorization": {"Bearer capability-secret", "Bearer other"}, "X-Mifos-Tenant-Id": {"tenant"}},
		{"Authorization": {"Bearer capability-secret"}, "X-Mifos-Tenant-Id": {"tenant", "other"}},
	} {
		req := httptest.NewRequest(http.MethodGet, "/api/capabilities?Authorization=forged&tenant=forged", nil)
		req.Header = headers
		rec := httptest.NewRecorder()
		h.Config.Handler.ServeHTTP(rec, req)
		if rec.Code != http.StatusUnauthorized {
			t.Errorf("missing/ambiguous identity accepted: %d", rec.Code)
		}
	}
	req := httptest.NewRequest(http.MethodPost, "/api/capabilities", strings.NewReader(`{"permissions":["ALL_FUNCTIONS"]}`))
	req.Header.Set("Authorization", "Bearer capability-secret")
	req.Header.Set("X-Mifos-Tenant-Id", "tenant")
	rec := httptest.NewRecorder()
	h.Config.Handler.ServeHTTP(rec, req)
	if rec.Code != http.StatusMethodNotAllowed || rec.Header().Get("Allow") != http.MethodGet || calls.Load() != 0 {
		t.Fatal("invalid capability requests reached Fineract")
	}
}

func TestHTTPCapabilitiesCallerTenantIsolation(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		permissions := []string{}
		if r.Header.Get("Authorization") == "Bearer officer-A" && r.Header.Get("fineract-platform-tenantid") == "tenant-A" {
			permissions = []string{"READ_CLIENT"}
		}
		json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": permissions})
	})
	// Same tenant/different caller and same caller/different tenant must both
	// remain distinct. Repetition also guards against a process-wide grant cache.
	var wg sync.WaitGroup
	for i := 0; i < 10; i++ {
		for _, identity := range [][2]string{{"Bearer officer-A", "tenant-A"}, {"Bearer officer-B", "tenant-A"}, {"Bearer officer-A", "tenant-B"}} {
			wg.Add(1)
			go func(auth, tenant string) {
				defer wg.Done()
				status, body := capabilityRequest(t, h.URL, auth, tenant)
				if status != http.StatusOK {
					t.Errorf("lookup failed: %d", status)
					return
				}
				assertCapabilities(t, body, auth == "Bearer officer-A" && tenant == "tenant-A")
			}(identity[0], identity[1])
		}
	}
	wg.Wait()
	if calls.Load() != 30 {
		t.Fatal("capability requests reused cached permissions")
	}
}

func TestCapabilityResolverWithoutHTTPContext(t *testing.T) {
	pilot(t, func(w http.ResponseWriter, r *http.Request) { t.Error("missing context reached Fineract") })
	// The resolver itself must reject a missing CallContext, independently of
	// HTTP middleware. Environment service credentials from pilot cannot help.
	caller, err := adapter.NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	capabilities, err := tools.CallerCapabilities(context.Background(), caller)
	if err == nil || len(capabilities) != 0 {
		t.Fatal("resolver granted capabilities without caller context")
	}
}
