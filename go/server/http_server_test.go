// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"bytes"
	"context"
	"encoding/json"
	"log"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

type observedCall struct{ Authorization, Tenant, Path string }

func TestCallerCatalogHasNoConfiguredExecutor(t *testing.T) {
	var calls atomic.Int32
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls.Add(1) }))
	defer bank.Close()
	t.Setenv("MIFOSX_BASE_URL", bank.URL)
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	pilot := newCallerScopedHTTPServer(adapter.NewCaller(), &mcpserver.Hooks{})
	if pilot.Registry.Fineract != nil {
		t.Fatal("HTTP catalog retained a configured executor")
	}
	// Even direct handler execution without HTTP middleware must fail closed.
	result, err := pilot.Registry.ToolDefs["get_client"].Handler(context.Background(), mcp.CallToolRequest{
		Params: mcp.CallToolParams{Name: "get_client", Arguments: map[string]interface{}{"clientId": 42}},
	})
	if err != nil || result == nil || !result.IsError {
		t.Fatalf("missing caller context did not fail closed: %v %v", result, err)
	}
	if calls.Load() != 0 {
		t.Fatal("missing caller context reached Fineract")
	}
}

func pilot(t *testing.T, downstream http.HandlerFunc) (*httptest.Server, *MifosMcpServer) {
	t.Helper()
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	bank := httptest.NewServer(downstream)
	t.Cleanup(bank.Close)
	t.Setenv("MIFOSX_BASE_URL", bank.URL)
	srv := NewMifosMcpServer(adapter.NewConfigured())
	httpSrv := httptest.NewServer(NewHTTPServer(adapter.NewCaller(), "").Handler())
	t.Cleanup(httpSrv.Close)
	return httpSrv, srv
}

func connect(t *testing.T, url, auth, tenant string) *client.Client {
	t.Helper()
	c, err := client.NewSSEMCPClient(url+"/sse", client.WithHeaders(map[string]string{
		"Authorization": auth, "X-Mifos-Tenant-Id": tenant,
	}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	t.Cleanup(cancel)
	if err = c.Start(ctx); err != nil {
		t.Fatal(err)
	}
	var init mcp.InitializeRequest
	init.Params.ProtocolVersion = mcp.LATEST_PROTOCOL_VERSION
	init.Params.ClientInfo = mcp.Implementation{Name: "caller-test", Version: "1"}
	if _, err = c.Initialize(ctx, init); err != nil {
		t.Fatal(err)
	}
	return c
}

func callClient(c *client.Client, name string) (*mcp.CallToolResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{Name: name, Arguments: map[string]interface{}{"clientId": 42}}})
}

func callAccounts(c *client.Client, id int) (*mcp.CallToolResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "get_client_accounts", Arguments: map[string]interface{}{"clientId": id}}})
}

func callSearch(c *client.Client, name string) (*mcp.CallToolResult, error) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "search_clients", Arguments: map[string]interface{}{"displayName": name}}})
}

func TestHTTPClientAccountsCallerIsolation(t *testing.T) {
	var mu sync.Mutex
	var seen []observedCall
	h, configured := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, observedCall{r.Header.Get("Authorization"), r.Header.Get("fineract-platform-tenantid"), r.URL.Path})
		mu.Unlock()
		w.Write([]byte(`{"loanAccounts":[],"savingsAccounts":[]}`))
	})
	a := connect(t, h.URL, "Bearer accounts-A-secret", "accounts-tenant-A")
	b := connect(t, h.URL, "Bearer accounts-B-secret", "accounts-tenant-B")
	var wg sync.WaitGroup
	for _, tc := range []struct {
		c  *client.Client
		id int
	}{{a, 42}, {b, 73}} {
		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func(c *client.Client, id int) {
				defer wg.Done()
				result, err := callAccounts(c, id)
				if err != nil || result.IsError {
					t.Errorf("accounts call failed: %v %v", result, err)
				}
			}(tc.c, tc.id)
		}
	}
	wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 40 {
		t.Fatalf("got %d downstream calls", len(seen))
	}
	counts := map[string]int{}
	for _, got := range seen {
		want := map[string]struct{ tenant, path string }{
			"Bearer accounts-A-secret": {"accounts-tenant-A", "/clients/42/accounts"},
			"Bearer accounts-B-secret": {"accounts-tenant-B", "/clients/73/accounts"},
		}[got.Authorization]
		if want.tenant == "" || got.Tenant != want.tenant || got.Path != want.path {
			t.Errorf("unexpected caller, tenant, or path: %+v", got)
		}
		counts[got.Authorization]++
	}
	if counts["Bearer accounts-A-secret"] != 20 || counts["Bearer accounts-B-secret"] != 20 {
		t.Fatal(counts)
	}
	if configured.Registry.Fineract.Username != "configured-service" || configured.Registry.Fineract.TenantID != "configured-tenant" {
		t.Fatal("shared client mutated")
	}
}

func TestHTTPClientAccountsMissingIdentityAndPermissionFailure(t *testing.T) {
	var calls atomic.Int32
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(r.Header.Get("Authorization") + " configured-password"))
	})
	for _, headers := range []map[string]string{
		{"X-Mifos-Tenant-Id": "caller-tenant"},
		{"Authorization": "Bearer accounts-secret"},
	} {
		req, _ := http.NewRequest("POST", h.URL+"/api/get_client_accounts", strings.NewReader(`{"clientId":42}`))
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized || calls.Load() != 0 {
			t.Fatalf("missing identity reached Fineract: status=%d calls=%d", resp.StatusCode, calls.Load())
		}
	}
	c := connect(t, h.URL, "Bearer accounts-secret", "caller-tenant")
	result, err := callAccounts(c, 42)
	if err != nil || !result.IsError || calls.Load() != 1 {
		t.Fatalf("403 became success or was retried: %v %v calls=%d", result, err, calls.Load())
	}
	encoded, _ := json.Marshal(result)
	if !strings.Contains(string(encoded), "403") {
		t.Fatal("permission status lost")
	}
	for _, secret := range []string{"accounts-secret", "configured-password", "configured-service"} {
		if strings.Contains(string(encoded)+logs.String(), secret) {
			t.Fatal("credential disclosed")
		}
	}
}

func TestHTTPClientAccountsRESTUsesCaller(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer accounts-rest-secret" ||
			r.Header.Get("fineract-platform-tenantid") != "accounts-rest-tenant" ||
			r.URL.Path != "/clients/1000000/accounts" {
			t.Error("REST caller or account path changed")
		}
		w.Write([]byte(`{"loanAccounts":[]}`))
	})
	for _, id := range []string{"1000000", "", "-1", "1.5", "../loans", "42?command=delete"} {
		req, _ := http.NewRequest("POST", h.URL+"/api/get_client_accounts", strings.NewReader(`{"clientId":"`+id+`","Authorization":"forged","tenant":"forged"}`))
		req.Header.Set("Authorization", "Bearer accounts-rest-secret")
		req.Header.Set("X-Mifos-Tenant-Id", "accounts-rest-tenant")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if id == "1000000" && resp.StatusCode != http.StatusOK || id != "1000000" && resp.StatusCode == http.StatusOK {
			t.Errorf("unexpected status %d for id %q", resp.StatusCode, id)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("invalid IDs reached Fineract")
	}
}

func TestHTTPSearchClientsCallerIsolation(t *testing.T) {
	var mu sync.Mutex
	var seen []observedCall
	h, configured := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, observedCall{r.Header.Get("Authorization"), r.Header.Get("fineract-platform-tenantid"), r.URL.RequestURI()})
		mu.Unlock()
		w.Write([]byte(`{"pageItems":[]}`))
	})
	a := connect(t, h.URL, "Bearer caller-A-secret", "tenant-A")
	b := connect(t, h.URL, "Bearer caller-B-secret", "tenant-B")
	var wg sync.WaitGroup
	for _, tc := range []struct {
		c    *client.Client
		name string
	}{{a, "Alice & Bob"}, {b, "Béatrice"}} {
		for i := 0; i < 20; i++ {
			wg.Add(1)
			go func(c *client.Client, name string) {
				defer wg.Done()
				result, err := callSearch(c, name)
				if err != nil || result.IsError {
					t.Errorf("search failed: %v %v", result, err)
				}
			}(tc.c, tc.name)
		}
	}
	wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 40 {
		t.Fatalf("got %d downstream calls", len(seen))
	}
	counts := map[string]int{}
	for _, got := range seen {
		want := map[string]struct{ tenant, path string }{
			"Bearer caller-A-secret": {"tenant-A", "/clients?displayName=Alice+%26+Bob"},
			"Bearer caller-B-secret": {"tenant-B", "/clients?displayName=B%C3%A9atrice"},
		}[got.Authorization]
		if want.tenant == "" || got.Tenant != want.tenant || got.Path != want.path {
			t.Errorf("unexpected caller, tenant, or query: %+v", got)
		}
		counts[got.Authorization]++
	}
	if counts["Bearer caller-A-secret"] != 20 || counts["Bearer caller-B-secret"] != 20 {
		t.Fatal(counts)
	}
	if configured.Registry.Fineract.Username != "configured-service" || configured.Registry.Fineract.TenantID != "configured-tenant" {
		t.Fatal("shared client mutated")
	}
}

func TestHTTPSearchClientsMissingIdentityAndPermissionFailure(t *testing.T) {
	var calls atomic.Int32
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(r.Header.Get("Authorization") + " configured-password"))
	})
	for _, headers := range []map[string]string{
		{"X-Mifos-Tenant-Id": "caller-tenant"},
		{"Authorization": "Bearer caller-secret"},
	} {
		req, _ := http.NewRequest("POST", h.URL+"/api/search_clients", strings.NewReader(`{"displayName":"Alice"}`))
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized || calls.Load() != 0 {
			t.Fatalf("missing identity reached Fineract: status=%d calls=%d", resp.StatusCode, calls.Load())
		}
	}
	c := connect(t, h.URL, "Bearer caller-secret", "caller-tenant")
	result, err := callSearch(c, "Alice")
	if err != nil || !result.IsError || calls.Load() != 1 {
		t.Fatalf("403 became success or was retried: %v %v calls=%d", result, err, calls.Load())
	}
	encoded, _ := json.Marshal(result)
	if !strings.Contains(string(encoded), "403") {
		t.Fatal("permission status lost")
	}
	for _, secret := range []string{"caller-secret", "configured-password", "configured-service"} {
		if strings.Contains(string(encoded)+logs.String(), secret) {
			t.Fatal("credential disclosed")
		}
	}
}

func TestHTTPSearchClientsRESTUsesCaller(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer rest-secret" ||
			r.Header.Get("fineract-platform-tenantid") != "rest-tenant" ||
			r.URL.RequestURI() != "/clients?displayName=Alice+%26+Bob" {
			t.Error("REST caller or search query changed")
		}
		w.Write([]byte(`{"pageItems":[]}`))
	})
	req, _ := http.NewRequest("POST", h.URL+"/api/search_clients", strings.NewReader(`{"displayName":"Alice & Bob"}`))
	req.Header.Set("Authorization", "Bearer rest-secret")
	req.Header.Set("X-Mifos-Tenant-Id", "rest-tenant")
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK || calls.Load() != 1 {
		t.Fatalf("REST search failed: status=%d calls=%d", resp.StatusCode, calls.Load())
	}
}

func TestHTTPCallerIsolation(t *testing.T) {
	var mu sync.Mutex
	var seen []observedCall
	h, configured := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, observedCall{r.Header.Get("Authorization"), r.Header.Get("fineract-platform-tenantid"), r.URL.Path})
		mu.Unlock()
		w.Write([]byte(`{"id":42}`))
	})
	a := connect(t, h.URL, "Bearer caller-A-secret", "tenant-A")
	b := connect(t, h.URL, "Bearer caller-B-secret", "tenant-B")
	for _, c := range []*client.Client{a, b} {
		result, err := callClient(c, "get_client")
		if err != nil || result.IsError {
			t.Fatalf("call failed: %v %v", result, err)
		}
	}
	var wg sync.WaitGroup
	for i := 0; i < 20; i++ {
		for _, c := range []*client.Client{a, b} {
			wg.Add(1)
			go func(c *client.Client) {
				defer wg.Done()
				result, err := callClient(c, "get_client")
				if err != nil || result.IsError {
					t.Errorf("call failed: %v %v", result, err)
				}
			}(c)
		}
	}
	wg.Wait()
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 42 {
		t.Fatalf("got %d downstream calls", len(seen))
	}
	counts := map[string]int{}
	for _, got := range seen {
		want := map[string]string{"Bearer caller-A-secret": "tenant-A", "Bearer caller-B-secret": "tenant-B"}[got.Authorization]
		if want == "" || got.Tenant != want || got.Path != "/clients/42" {
			t.Errorf("unexpected headers/path: %+v", got)
		}
		counts[got.Authorization]++
	}
	if counts["Bearer caller-A-secret"] != 21 || counts["Bearer caller-B-secret"] != 21 {
		t.Fatal(counts)
	}
	if configured.Registry.Fineract.Username != "configured-service" || configured.Registry.Fineract.TenantID != "configured-tenant" {
		t.Fatal("shared client mutated")
	}
}

func TestHTTPMissingIdentityNeverFallsBack(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1); w.Write([]byte(`{}`)) })
	c := connect(t, h.URL, "Bearer valid-secret", "valid-tenant")
	endpoint := client.GetEndpoint(c).String()
	for _, headers := range []map[string]string{
		{"X-Mifos-Tenant-Id": "valid-tenant"}, {"Authorization": "Bearer valid-secret"}, {},
	} {
		body := strings.NewReader(`{"jsonrpc":"2.0","id":99,"method":"tools/call","params":{"name":"get_client","arguments":{"clientId":42,"Authorization":"Bearer forged","tenant":"forged"}}}`)
		req, _ := http.NewRequest("POST", endpoint, body)
		for k, v := range headers {
			req.Header.Set(k, v)
		}
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if resp.StatusCode != http.StatusUnauthorized {
			t.Errorf("got status %d", resp.StatusCode)
		}
	}
	if calls.Load() != 0 {
		t.Fatalf("made %d downstream requests", calls.Load())
	}
}

func TestHTTPPermissionFailureAndNoCredentialDisclosure(t *testing.T) {
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		w.WriteHeader(http.StatusForbidden)
		w.Write([]byte(r.Header.Get("Authorization") + " configured-password"))
	})
	c := connect(t, h.URL, "Bearer private-token", "tenant-A")
	result, err := callClient(c, "get_client")
	if err != nil || !result.IsError {
		t.Fatalf("403 became success: %v %v", result, err)
	}
	if calls.Load() != 1 {
		t.Fatalf("403 retried: %d calls", calls.Load())
	}
	encoded, _ := json.Marshal(result)
	for _, secret := range []string{"private-token", "configured-password", "configured-service"} {
		if strings.Contains(string(encoded)+logs.String(), secret) {
			t.Errorf("credential disclosed: %s", secret)
		}
	}
	if !strings.Contains(string(encoded), "403") {
		t.Fatal("permission status lost")
	}
}

func TestHTTPOnlyClientReadsAndConfiguredModePreserved(t *testing.T) {
	var calls atomic.Int32
	h, srv := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		call := calls.Add(1)
		u, p, ok := r.BasicAuth()
		if !ok || u != "configured-service" || p != "configured-password" || r.Header.Get("fineract-platform-tenantid") != "configured-tenant" {
			t.Error("configured mode changed")
		}
		if call == 3 && r.URL.Path != "/clients/42/accounts" {
			t.Error("configured accounts path changed")
		}
		w.Write([]byte(`{"id":42}`))
	})
	c := connect(t, h.URL, "Bearer caller", "caller-tenant")
	list, err := c.ListTools(context.Background(), mcp.ListToolsRequest{})
	if err != nil || len(list.Tools) != 3 {
		t.Fatalf("HTTP tools: %v %v", list, err)
	}
	names := map[string]bool{}
	for _, tool := range list.Tools {
		names[tool.Name] = true
	}
	if !names["get_client"] || !names["search_clients"] || !names["get_client_accounts"] {
		t.Fatalf("HTTP tools: %v", names)
	}
	result, err := callClient(c, "create_client")
	if err == nil && !result.IsError {
		t.Fatal("unsupported tool succeeded")
	}
	if calls.Load() != 0 {
		t.Fatal("unsupported tool called Fineract")
	}
	resp, err := http.Get(h.URL + "/api/create_client")
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Fatal("unsupported REST route exposed")
	}
	if len(srv.Registry.ToolDefs) < 2 {
		t.Fatal("configured tools removed")
	}
	local, err := client.NewInProcessClient(srv.MCPServer)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = local.Close() })
	var init mcp.InitializeRequest
	init.Params.ProtocolVersion = mcp.LATEST_PROTOCOL_VERSION
	init.Params.ClientInfo = mcp.Implementation{Name: "local-test", Version: "1"}
	if _, err := local.Initialize(context.Background(), init); err != nil {
		t.Fatal(err)
	}
	result, err = callClient(local, "get_client")
	if err != nil || result.IsError {
		t.Fatal(err)
	}
	if calls.Load() != 1 {
		t.Fatal("configured request not executed")
	}
	result, err = callSearch(local, "Alice")
	if err != nil || result.IsError || calls.Load() != 2 {
		t.Fatalf("configured search changed: %v %v calls=%d", result, err, calls.Load())
	}
	result, err = callAccounts(local, 42)
	if err != nil || result.IsError || calls.Load() != 3 {
		t.Fatalf("configured accounts changed: %v %v calls=%d", result, err, calls.Load())
	}
}

func TestHTTPRESTUsesCallerAndValidatesClientID(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != "Bearer rest-secret" || r.Header.Get("fineract-platform-tenantid") != "rest-tenant" || r.URL.Path != "/clients/1000000" {
			t.Error("REST caller or path changed")
		}
		w.Write([]byte(`{"id":1000000}`))
	})
	for _, id := range []string{"1000000", "", "-1", "1.5", "../loans", "42?command=delete"} {
		req, _ := http.NewRequest("POST", h.URL+"/api/get_client", strings.NewReader(`{"clientId":"`+id+`","Authorization":"forged","tenant":"forged"}`))
		req.Header.Set("Authorization", "Bearer rest-secret")
		req.Header.Set("X-Mifos-Tenant-Id", "rest-tenant")
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		resp.Body.Close()
		if id == "1000000" && resp.StatusCode != 200 || id != "1000000" && resp.StatusCode == 200 {
			t.Errorf("unexpected status %d for id %q", resp.StatusCode, id)
		}
	}
	if calls.Load() != 1 {
		t.Fatal("invalid IDs reached Fineract")
	}
}

func TestHTTPSuccessfulCredentialEchoIsNotExposed(t *testing.T) {
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		w.Write([]byte(`{"echo":"` + r.Header.Get("Authorization") + `"}`))
	})
	c := connect(t, h.URL, "Bearer successful-echo-secret", "tenant")
	result, err := callClient(c, "get_client")
	if err != nil || !result.IsError {
		t.Fatalf("credential echo not refused: %v %v", result, err)
	}
	encoded, _ := json.Marshal(result)
	if strings.Contains(string(encoded)+logs.String(), "successful-echo-secret") {
		t.Fatal("credential leaked")
	}
}
