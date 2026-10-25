// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package server

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

const bridgeSecret = "test-bridge-secret-at-least-32-characters"

func clientCreationArgs() map[string]any {
	return map[string]any{"firstname": "Alice", "lastname": "Officer", "officeId": 1,
		"active": true, "activationDate": "2026-10-23", "dateFormat": "yyyy-MM-dd", "locale": "en"}
}

func TestHTTPCreateClientProposalBoundary(t *testing.T) {
	for _, permission := range []string{"CREATE_CLIENT", "ALL_FUNCTIONS", "READ_CLIENT", "ALL_FUNCTIONS_READ", "CREATE_CLIENT_CHECKER", "CREATE_CLIENTIMAGE", "create_client", " CREATE_CLIENT "} {
		t.Run(permission, func(t *testing.T) {
			allowed := permission == "CREATE_CLIENT" || permission == "ALL_FUNCTIONS"
			var writes, proposals atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path != "/copilot/api/v1/internal/mcp/proposals" || r.Header.Get("Authorization") != "Bearer officer-secret" || r.Header.Get("Fineract-Platform-TenantId") != "tenant-X" || r.Header.Get("X-MCP-Approval-Bridge") != bridgeSecret || r.Header.Get("X-MCP-Session-Id") == "" {
					t.Error("proposal changed caller, tenant, session or bridge identity")
				}
				var proposed struct {
					Payload string `json:"payload"`
				}
				if json.NewDecoder(r.Body).Decode(&proposed) != nil {
					t.Error("invalid proposal envelope")
				}
				action, err := adapter.ParseClientCreation([]byte(proposed.Payload))
				if err != nil || action.Firstname != "Alice" || action.OfficeID != 1 {
					t.Error("invalid proposal payload")
				}
				proposals.Add(1)
				io.WriteString(w, `{"status":"pending_approval","action_card":{"card_id":"card-test","tool":"create_client"}}`)
			}))
			defer gateway.Close()
			t.Setenv("MIFOSX_APPROVAL_GATEWAY_URL", gateway.URL)
			t.Setenv("MCP_APPROVAL_BRIDGE_TOKEN", bridgeSecret)
			h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
				if r.URL.Path == "/userdetails" {
					json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": []string{permission}})
					return
				}
				writes.Add(1)
				t.Error("proposal reached Fineract write endpoint")
			})
			c := connect(t, h.URL, "Bearer officer-secret", "tenant-X")
			want := []string{}
			if allowed {
				want = append(want, "create_client")
			}
			if permission == "ALL_FUNCTIONS" || permission == "READ_CLIENT" || permission == "ALL_FUNCTIONS_READ" {
				want = append(want, clientReadNames...)
			}
			assertToolList(t, c, want)
			result, err := c.CallTool(context.Background(), mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "create_client", Arguments: clientCreationArgs()}})
			if allowed {
				if err != nil || result.IsError || !strings.Contains(result.Content[0].(mcp.TextContent).Text, "pending_approval") {
					t.Fatalf("proposal failed: %v %v", result, err)
				}
			} else if err == nil {
				t.Error("direct invocation bypassed capability filter")
			}
			expected := int32(0)
			if allowed {
				expected = 1
			}
			if proposals.Load() != expected || writes.Load() != 0 {
				t.Fatal("incorrect proposal/write counts")
			}
			if !allowed {
				caller, err := adapter.NewCaller()
				if err != nil {
					t.Fatal(err)
				}
				ctx, err := adapter.WithCallerContext(context.Background(), "Bearer officer-secret", "tenant-X")
				if err != nil {
					t.Fatal(err)
				}
				if _, err := caller.ProposeClient(ctx, adapter.NewApprovalBridge(), "session", clientCreationArgs()); err == nil {
					t.Fatal("direct proposal bypassed capability enforcement")
				}
			}

			for _, name := range []string{"approve", "approve_action", "execute_create_client"} {
				_, err := c.CallTool(context.Background(), mcp.CallToolRequest{Params: mcp.CallToolParams{Name: name}})
				if err == nil {
					t.Fatal("model can access an approval tool")
				}
			}
		})
	}
}

func TestCreateClientValidationAndMissingContext(t *testing.T) {
	t.Setenv("MIFOSX_APPROVAL_GATEWAY_URL", "")
	t.Setenv("MCP_APPROVAL_BRIDGE_TOKEN", "")
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/userdetails" {
			io.WriteString(w, `{"authenticated":true,"permissions":["CREATE_CLIENT"]}`)
			return
		}
		calls.Add(1)
	})
	c := connect(t, h.URL, "Bearer officer-secret", "tenant-X")
	for _, change := range []func(map[string]any){
		func(a map[string]any) { delete(a, "firstname") },
		func(a map[string]any) { a["lastname"] = " " },
		func(a map[string]any) { a["officeId"] = 1.5 },
		func(a map[string]any) { a["officeId"] = -1 },
		func(a map[string]any) { a["officeId"] = "1" },
		func(a map[string]any) { a["active"] = "true" },
		func(a map[string]any) { a["approve"] = true },
		func(a map[string]any) { a["Authorization"] = "Bearer injected" },
		func(a map[string]any) { a["activationDate"] = "" },
	} {
		args := clientCreationArgs()
		change(args)
		result, err := c.CallTool(context.Background(), mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "create_client", Arguments: args}})
		if err == nil && !result.IsError {
			t.Fatal("invalid arguments accepted")
		}
	}
	// Even direct adapter invocation cannot fall back to configured identity.
	caller, err := adapter.NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	if _, err = caller.ProposeClient(context.Background(), adapter.NewApprovalBridge(), "session", clientCreationArgs()); err == nil {
		t.Fatal("missing caller accepted")
	}
	if _, err = caller.ExecuteApprovedClient(context.Background(), adapter.NewApprovalBridge(), "session", "card-00000000-0000-0000-0000-000000000000", "ticket"); err == nil {
		t.Fatal("missing caller execution accepted")
	}
	// Configured or missing bridge credentials fail closed, with no downstream write.
	result, err := c.CallTool(context.Background(), mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "create_client", Arguments: clientCreationArgs()}})
	if err == nil && !result.IsError {
		t.Fatal("missing bridge accepted")
	}
	if calls.Load() != 0 {
		t.Fatal("invalid proposal or missing context caused a write")
	}
}

func TestHTTPCreateClientExecutionRequiresCallerAndBridge(t *testing.T) {
	t.Setenv("MCP_APPROVAL_BRIDGE_TOKEN", bridgeSecret)
	t.Setenv("MIFOSX_APPROVAL_GATEWAY_URL", "http://127.0.0.1:1")
	var calls atomic.Int32
	h, _ := pilot(t, func(http.ResponseWriter, *http.Request) { calls.Add(1) })
	for _, identity := range [][2]string{{"", "tenant"}, {"Bearer officer", ""}, {"", ""}, {"Bearer configured-service", "configured-tenant"}} {
		req, _ := http.NewRequest(http.MethodPost, h.URL+"/internal/approved-create-client/card-00000000-0000-0000-0000-000000000000", nil)
		req.Header.Set("Authorization", identity[0])
		req.Header.Set("X-Mifos-Tenant-Id", identity[1])
		req.Header.Set("X-MCP-Approval-Bridge", bridgeSecret)
		req.Header.Set("X-MCP-Session-Id", "unowned-session")
		response, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		response.Body.Close()
		if response.StatusCode != 401 && response.StatusCode != 403 {
			t.Fatal("execution accepted missing/foreign caller context")
		}
	}
	if calls.Load() != 0 {
		t.Fatal("missing/foreign identity reached Fineract")
	}
}
