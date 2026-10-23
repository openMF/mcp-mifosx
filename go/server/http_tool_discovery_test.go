// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/tools"
)

var clientReadNames = []string{"get_client", "get_client_accounts", "search_clients"}

func assertToolList(t *testing.T, c *client.Client, want []string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	list, err := c.ListTools(ctx, mcp.ListToolsRequest{})
	if err != nil {
		t.Fatal(err)
	}
	names := make([]string, 0, len(list.Tools))
	for _, tool := range list.Tools {
		names = append(names, tool.Name)
	}
	sort.Strings(names)
	if !reflect.DeepEqual(names, want) {
		t.Errorf("tools/list = %v, want %v", names, want)
	}
}

func assertCallerInvocation(t *testing.T, c *client.Client, name string, allowed bool) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	result, err := c.CallTool(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{
		Name: name, Arguments: map[string]any{"clientId": 42, "displayName": "Alice"},
	}})
	if allowed {
		if err != nil || result == nil || result.IsError {
			t.Errorf("permitted %s invocation failed: %v %v", name, result, err)
		}
		return
	}
	// The SDK deliberately treats filtered tools as unavailable, returning the
	// same safe error as an unknown tool without disclosing permission details.
	if err == nil || !strings.Contains(err.Error(), "not found") {
		t.Errorf("denied %s invocation was not blocked: %v %v", name, result, err)
	}
	encoded := fmt.Sprint(result, err)
	for _, secret := range []string{"discovery-secret", "configured-service", "configured-password", "READ_CLIENT", "internal-role"} {
		if strings.Contains(encoded, secret) {
			t.Error("permission error disclosed sensitive information")
		}
	}
}

func TestHTTPToolDiscoveryAndInvocation(t *testing.T) {
	for _, tc := range []struct {
		name, response string
		status         int
		allowed, basic bool
	}{
		{name: "all client reads", response: `{"authenticated":true,"permissions":["READ_CLIENT"]}`, allowed: true},
		{name: "basic caller", response: `{"authenticated":true,"permissions":["READ_CLIENT"]}`, allowed: true, basic: true},
		{name: "denied", response: `{"authenticated":true,"permissions":[]}`},
		{name: "unknown permission", response: `{"authenticated":true,"permissions":["READ_UNKNOWN"]}`},
		{name: "unresolved", response: `{"authenticated":true}`},
		{name: "malformed", response: `{"authenticated":true,"permissions":["READ_CLIENT",42]}`},
		{name: "lookup failed", status: http.StatusInternalServerError},
		{name: "unauthenticated", status: http.StatusUnauthorized},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var lookups, reads atomic.Int32
			auth := "Bearer discovery-secret"
			if tc.basic {
				auth = "Basic " + base64.StdEncoding.EncodeToString([]byte("officer:discovery-secret"))
			}
			h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != auth || r.Header.Get("fineract-platform-tenantid") != "discovery-tenant" {
					t.Error("capability lookup or execution used a different caller/tenant")
				}
				if r.URL.Path == "/userdetails" || r.URL.Path == "/authentication" {
					lookups.Add(1)
					if tc.basic {
						var credentials map[string]string
						if r.Method != http.MethodPost || r.URL.Path != "/authentication" || json.NewDecoder(r.Body).Decode(&credentials) != nil || credentials["username"] != "officer" || credentials["password"] != "discovery-secret" {
							t.Error("Basic resolution did not use caller credentials")
						}
					} else if r.Method != http.MethodGet || r.URL.Path != "/userdetails" {
						t.Error("Bearer resolution used the wrong endpoint")
					}
					if tc.status != 0 {
						w.WriteHeader(tc.status)
					}
					io.WriteString(w, tc.response)
					return
				}
				reads.Add(1)
				io.WriteString(w, `{"id":42}`)
			})
			c := connect(t, h.URL, auth, "discovery-tenant")
			want := []string{}
			if tc.allowed {
				want = clientReadNames
			}
			assertToolList(t, c, want)
			for _, name := range clientReadNames {
				assertCallerInvocation(t, c, name, tc.allowed)
				status, body, err := postSessionMessage(h.URL+"/api/"+name, auth, "discovery-tenant", `{"clientId":42,"displayName":"Alice"}`)
				if err != nil {
					t.Fatal(err)
				}
				if tc.allowed && status != http.StatusOK || !tc.allowed && (status != http.StatusForbidden || strings.TrimSpace(body) != "tool is not available for the authenticated caller") {
					t.Errorf("REST availability for %s: HTTP %d %s", name, status, body)
				}
			}
			if lookups.Load() != 7 {
				t.Errorf("got %d lookups, want one per list/call/REST request (7)", lookups.Load())
			}
			wantReads := int32(0)
			if tc.allowed {
				wantReads = 6
			}
			if reads.Load() != wantReads {
				t.Errorf("data requests = %d, want %d", reads.Load(), wantReads)
			}
		})
	}
}

func TestHTTPToolDiscoveryCallerTenantIsolation(t *testing.T) {
	var lookups, reads atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		allowed := r.Header.Get("Authorization") == "Bearer officer-A" && r.Header.Get("fineract-platform-tenantid") == "tenant-X"
		if r.URL.Path == "/userdetails" {
			lookups.Add(1)
			permissions := []string{}
			if allowed {
				permissions = []string{"READ_CLIENT"}
			}
			json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": permissions})
			return
		}
		reads.Add(1)
		if !allowed {
			t.Error("denied caller/tenant reached data endpoint")
		}
		io.WriteString(w, `{"id":42}`)
	})
	var wg sync.WaitGroup
	for _, identity := range [][2]string{{"Bearer officer-A", "tenant-X"}, {"Bearer officer-B", "tenant-X"}, {"Bearer officer-A", "tenant-Y"}} {
		c := connect(t, h.URL, identity[0], identity[1])
		allowed := identity[0] == "Bearer officer-A" && identity[1] == "tenant-X"
		wg.Go(func() {
			want := []string{}
			if allowed {
				want = clientReadNames
			}
			for i := 0; i < 5; i++ {
				assertToolList(t, c, want)
				assertCallerInvocation(t, c, "get_client", allowed)
			}
		})
	}
	wg.Wait()
	if lookups.Load() != 30 || reads.Load() != 5 {
		t.Errorf("unexpected cross-caller reuse or execution: lookups=%d reads=%d", lookups.Load(), reads.Load())
	}
}

func TestHTTPToolDiscoveryRevalidatesExistingSession(t *testing.T) {
	var allowed atomic.Bool
	allowed.Store(true)
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/userdetails" {
			permissions := []string{}
			if allowed.Load() {
				permissions = []string{"READ_CLIENT"}
			}
			json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": permissions})
			return
		}
		if !allowed.Load() {
			t.Error("revoked grant reached data endpoint")
		}
		io.WriteString(w, `{"id":42}`)
	})
	c := connect(t, h.URL, "Bearer officer", "tenant")
	assertToolList(t, c, clientReadNames)
	allowed.Store(false)
	assertCallerInvocation(t, c, "get_client", false)
	assertToolList(t, c, []string{})
	allowed.Store(true)
	assertToolList(t, c, clientReadNames)
	assertCallerInvocation(t, c, "get_client", true)
}

func TestHTTPToolDiscoveryMissingIdentity(t *testing.T) {
	var lookups atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) { lookups.Add(1) })
	c := connect(t, h.URL, "Bearer owner", "tenant")
	for _, identity := range [][2]string{{"", "tenant"}, {"Bearer owner", ""}, {"", ""}} {
		for _, body := range []string{`{"jsonrpc":"2.0","id":1,"method":"tools/list"}`, sessionToolMessage} {
			status, _, err := postSessionMessage(client.GetEndpoint(c).String(), identity[0], identity[1], body)
			if err != nil || status != http.StatusUnauthorized {
				t.Errorf("missing identity accepted: HTTP %d, error %v", status, err)
			}
		}
	}
	if lookups.Load() != 0 {
		t.Fatal("missing identity reached Fineract")
	}
}

func TestCallerToolFilterPartialCapabilitiesOverSSE(t *testing.T) {
	// PR 6 currently grants the three CLIENT reads together. Inject capability
	// results here to verify partial and unknown projections without inventing
	// new Fineract permissions or changing the production resolver.
	for _, tc := range []struct {
		name         string
		capabilities []tools.ToolCapability
		err          error
		want         []string
	}{
		{name: "only get_client", capabilities: []tools.ToolCapability{{Tool: "get_client", Available: true}, {Tool: "get_client_accounts", Available: false}}, want: []string{"get_client"}},
		{name: "unknown capability", capabilities: []tools.ToolCapability{{Tool: "unknown", Available: true}}, want: []string{}},
		{name: "failed partial result", capabilities: []tools.ToolCapability{{Tool: "get_client", Available: true}}, err: errors.New("private permission lookup failure"), want: []string{}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var lookups, executions atomic.Int32
			app := mcpserver.NewMCPServer("filter-test", "1", mcpserver.WithToolFilter(callerToolFilter(func(context.Context) ([]tools.ToolCapability, error) {
				lookups.Add(1)
				return tc.capabilities, tc.err
			})))
			for _, name := range clientReadNames {
				app.AddTool(mcp.NewTool(name), func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error) {
					executions.Add(1)
					return mcp.NewToolResultText("ok"), nil
				})
			}
			h := httptest.NewServer(mcpserver.NewSSEServer(app))
			t.Cleanup(h.Close)
			c := connect(t, h.URL, "Bearer officer", "tenant")
			assertToolList(t, c, tc.want)
			for _, name := range clientReadNames {
				assertCallerInvocation(t, c, name, name == "get_client" && len(tc.want) == 1)
			}
			if lookups.Load() != 4 || executions.Load() != int32(len(tc.want)) || len(app.ListTools()) != 3 {
				t.Fatal("filter reused capabilities, executed denied tool, or mutated shared registry")
			}
		})
	}
}
