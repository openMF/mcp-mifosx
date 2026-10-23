// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package server

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

// Runs against the real Java ApprovalStore and HTTP controller, with real Go SSE
// transport and a recording Fineract. Build gateway first and set this jar path.
func TestHTTPCreateClientApprovalIntegration(t *testing.T) {
	jar := os.Getenv("MCP_APPROVAL_TEST_GATEWAY_JAR")
	if jar == "" {
		t.Skip("set MCP_APPROVAL_TEST_GATEWAY_JAR to the packaged gateway for cross-service integration")
	}
	jar, err := filepath.Abs(jar)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(jar); err != nil {
		t.Fatalf("gateway jar must exist (use an absolute path): %v", err)
	}
	var writes atomic.Int32
	var downstreamStatus atomic.Int32
	var echoCredential atomic.Bool
	var permitted atomic.Bool
	permitted.Store(true)
	var mu sync.Mutex
	var executedPayload string
	var lastCallback http.Header
	var expectedKey, expectedCard string
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer original-officer-secret" || r.Header.Get("fineract-platform-tenantid") != "tenant-X" {
			t.Error("execution or capability lookup switched original caller/tenant")
		}
		if r.URL.Path == "/userdetails" {
			permissions := []string{"READ_CLIENT"}
			if permitted.Load() {
				permissions = append(permissions, "CREATE_CLIENT")
			}
			json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": permissions})
			return
		}
		if r.URL.Path != "/clients" || r.Method != http.MethodPost {
			t.Error("unexpected Fineract operation")
		}
		writes.Add(1)
		data, _ := io.ReadAll(r.Body)
		mu.Lock()
		executedPayload = string(data)
		if r.Header.Get("Idempotency-Key") != expectedKey || r.Header.Get("X-Correlation-Id") != expectedCard {
			t.Error("action correlation or server idempotency key lost")
		}
		mu.Unlock()
		status := int(downstreamStatus.Load())
		if status == 0 {
			status = 200
		}
		w.WriteHeader(status)
		if status != 200 {
			io.WriteString(w, "Bearer original-officer-secret configured-password "+bridgeSecret)
			return
		}
		if echoCredential.Load() {
			io.WriteString(w, `{"echo":"original-\u006ffficer-secret"}`)
			return
		}
		io.WriteString(w, `{"resourceId":42}`)
	}))
	defer bank.Close()
	goServer := httptest.NewUnstartedServer(nil)
	goURL := "http://" + goServer.Listener.Addr().String()
	listener, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := listener.Addr().(*net.TCPAddr).Port
	listener.Close()
	gatewayURL := fmt.Sprintf("http://127.0.0.1:%d", port)
	logFile, err := os.CreateTemp(t.TempDir(), "gateway-*.log")
	if err != nil {
		t.Fatal(err)
	}
	defer logFile.Close()
	command := exec.Command("java", "-jar", jar, "--server.address=127.0.0.1", fmt.Sprintf("--server.port=%d", port))
	command.Env = append(os.Environ(), "MCP_APPROVAL_BRIDGE_TOKEN="+bridgeSecret, "MCP_CALLER_URL="+goURL, "COPILOT_APPROVAL_TTL_SECONDS=2", "COPILOT_LLM_PROVIDER=mock")
	command.Stdout, command.Stderr = logFile, logFile
	if err := command.Start(); err != nil {
		t.Fatal(err)
	}
	defer func() { _ = command.Process.Kill(); _ = command.Wait() }()
	t.Setenv("MIFOSX_APPROVAL_GATEWAY_URL", gatewayURL)
	t.Setenv("MCP_APPROVAL_BRIDGE_TOKEN", bridgeSecret)
	t.Setenv("MIFOSX_BASE_URL", bank.URL)
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	caller, err := adapter.NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	handler := NewHTTPServer(caller, "").Handler()
	goServer.Config.Handler = http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if strings.HasPrefix(r.URL.Path, "/internal/approved-create-client/") {
			mu.Lock()
			lastCallback = r.Header.Clone()
			mu.Unlock()
		}
		handler.ServeHTTP(w, r)
	})
	goServer.Start()
	defer goServer.Close()
	ready := false
	startupClient := &http.Client{Timeout: time.Second}
	for deadline := time.Now().Add(30 * time.Second); time.Now().Before(deadline); {
		response, err := startupClient.Get(gatewayURL + "/copilot/api/v1/meta")
		if err == nil {
			response.Body.Close()
			ready = true
			break
		}
		time.Sleep(50 * time.Millisecond)
	}
	if !ready {
		data, _ := os.ReadFile(logFile.Name())
		t.Fatalf("gateway did not start: %s", data)
	}
	post := func(url, auth, tenant, session, body string, headers http.Header) (int, string) {
		req, _ := http.NewRequest(http.MethodPost, url, strings.NewReader(body))
		req.Header = headers.Clone()
		if req.Header == nil {
			req.Header = make(http.Header)
		}
		req.Header.Set("Authorization", auth)
		req.Header.Set("Fineract-Platform-TenantId", tenant)
		req.Header.Set("X-MCP-Session-Id", session)
		req.Header.Set("Content-Type", "application/json")
		// Go uses its established tenant header; gateway uses the existing gateway header.
		req.Header.Set("X-Mifos-Tenant-Id", tenant)
		resp, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Error(err)
			return 0, ""
		}
		defer resp.Body.Close()
		data, _ := io.ReadAll(resp.Body)
		for _, secret := range []string{"original-officer-secret", "configured-password", bridgeSecret} {
			if strings.Contains(string(data), secret) {
				t.Error("response disclosed credential material")
			}
		}
		return resp.StatusCode, string(data)
	}
	propose := func(t *testing.T) (*client.Client, string, string, map[string]any) {
		t.Helper()
		c := connect(t, goServer.URL, "Bearer original-officer-secret", "tenant-X")
		assertToolList(t, c, []string{"create_client", "get_client", "get_client_accounts", "search_clients"})
		before := writes.Load()
		result, err := c.CallTool(context.Background(), mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "create_client", Arguments: clientCreationArgs()}})
		if err != nil || result.IsError {
			t.Fatalf("proposal failed: %v %v", result, err)
		}
		var response struct {
			Status string `json:"status"`
			Card   struct {
				ID   string         `json:"card_id"`
				Key  string         `json:"idempotency_key"`
				Args map[string]any `json:"args"`
			} `json:"action_card"`
		}
		if json.Unmarshal([]byte(result.Content[0].(mcp.TextContent).Text), &response) != nil || response.Status != "pending_approval" {
			t.Fatal("invalid pending card")
		}
		if writes.Load() != before {
			t.Fatal("proposal executed a write")
		}
		mu.Lock()
		expectedKey, expectedCard = response.Card.Key, response.Card.ID
		mu.Unlock()
		session := client.GetEndpoint(c).Query().Get("sessionId")
		return c, response.Card.ID, session, response.Card.Args
	}
	decide := func(id, session, decision string) (int, string) {
		return post(gatewayURL+"/copilot/api/v1/mcp/actions/"+id+"/decision", "Bearer original-officer-secret", "tenant-X", session, decision, nil)
	}
	t.Run("same caller exact payload and callback replay", func(t *testing.T) {
		_, id, session, reviewed := propose(t)
		before := writes.Load()
		// A tool cannot claim or approve its own proposal, even with a guessed card id.
		status, _ := post(goServer.URL+"/internal/approved-create-client/"+id, "Bearer original-officer-secret", "tenant-X", session, "", nil)
		if status != 403 {
			t.Fatalf("untrusted callback accepted: %d", status)
		}
		status, _ = post(gatewayURL+"/copilot/api/v1/internal/mcp/claims/"+id, "Bearer original-officer-secret", "tenant-X", session, "{}", http.Header{"X-Mcp-Approval-Bridge": {bridgeSecret}, "X-Mcp-Decision-Token": {"guessed"}})
		if status != 403 || writes.Load() != before {
			t.Fatal("claim without human confirmation succeeded")
		}
		for _, identity := range [][3]string{{"Bearer other-officer", "tenant-X", session}, {"Bearer original-officer-secret", "tenant-Y", session}, {"", "tenant-X", session}, {"Bearer original-officer-secret", "", session}, {"Bearer original-officer-secret", "tenant-X", "other-session"}, {"Bearer original-officer-secret", "tenant-X", ""}} {
			status, _ := post(gatewayURL+"/copilot/api/v1/mcp/actions/"+id+"/decision", identity[0], identity[1], identity[2], `{"decision":"approve"}`, nil)
			if status != 403 {
				t.Errorf("foreign or missing context accepted: %d", status)
			}
		}
		if writes.Load() != before {
			t.Fatal("foreign approval executed a write")
		}
		status, body := decide(id, session, `{"decision":"approve","args":{"officeId":99},"payload":"changed"}`)
		if status != 200 || !strings.Contains(body, `"resourceId":42`) {
			t.Fatalf("approval failed: %d %s", status, body)
		}
		mu.Lock()
		payload, callback := executedPayload, lastCallback.Clone()
		mu.Unlock()
		var executed map[string]any
		json.Unmarshal([]byte(payload), &executed)
		expected, _ := json.Marshal(reviewed)
		actual, _ := json.Marshal(executed)
		if string(expected) != string(actual) || writes.Load() != before+1 {
			t.Fatal("reviewed payload changed or write count incorrect")
		}
		status, _ = decide(id, session, `{"decision":"approve"}`)
		if status != 403 {
			t.Fatal("approval replay accepted")
		}
		status, _ = post(goServer.URL+"/internal/approved-create-client/"+id, "Bearer original-officer-secret", "tenant-X", session, "", callback)
		if status == 200 || writes.Load() != before+1 {
			t.Fatal("duplicate callback repeated client creation")
		}
	})
	t.Run("reject", func(t *testing.T) {
		_, id, session, _ := propose(t)
		before := writes.Load()
		status, _ := decide(id, session, `{"decision":"reject"}`)
		if status != 200 {
			t.Fatal("rejection failed")
		}
		status, _ = decide(id, session, `{"decision":"approve"}`)
		if status != 403 || writes.Load() != before {
			t.Fatal("rejected card executed")
		}
	})
	t.Run("expired", func(t *testing.T) {
		_, id, session, _ := propose(t)
		before := writes.Load()
		time.Sleep(2100 * time.Millisecond)
		status, _ := decide(id, session, `{"decision":"approve"}`)
		if status != 403 || writes.Load() != before {
			t.Fatal("expired card executed")
		}
	})
	t.Run("concurrent human approvals", func(t *testing.T) {
		_, id, session, _ := propose(t)
		before := writes.Load()
		var wg sync.WaitGroup
		var successes atomic.Int32
		for i := 0; i < 24; i++ {
			wg.Go(func() {
				status, _ := decide(id, session, `{"decision":"approve"}`)
				if status == 200 {
					successes.Add(1)
				} else if status != 403 {
					t.Errorf("unexpected decision status %d", status)
				}
			})
		}
		wg.Wait()
		if successes.Load() != 1 || writes.Load() != before+1 {
			t.Fatal("concurrent decisions executed more than once")
		}
	})
	t.Run("permission revoked before execution", func(t *testing.T) {
		c, id, session, _ := propose(t)
		before := writes.Load()
		permitted.Store(false)
		defer permitted.Store(true)
		assertToolList(t, c, clientReadNames)
		status, _ := decide(id, session, `{"decision":"approve"}`)
		if status == 200 || writes.Load() != before {
			t.Fatal("revoked permission executed a write")
		}
	})
	t.Run("originating session disconnected", func(t *testing.T) {
		c, id, session, _ := propose(t)
		before := writes.Load()
		c.Close()
		time.Sleep(50 * time.Millisecond)
		status, _ := decide(id, session, `{"decision":"approve"}`)
		if status == 200 || writes.Load() != before {
			t.Fatal("disconnected session executed a write")
		}
	})
	t.Run("credential echo on success is refused", func(t *testing.T) {
		_, id, session, _ := propose(t)
		before := writes.Load()
		echoCredential.Store(true)
		defer echoCredential.Store(false)
		status, _ := decide(id, session, `{"decision":"approve"}`)
		if status != 502 || writes.Load() != before+1 {
			t.Fatal("credential-bearing success was exposed or retried")
		}
	})
	for _, status := range []int32{400, 401, 403, 409, 422, 500} {
		t.Run(fmt.Sprintf("downstream HTTP %d remains failure", status), func(t *testing.T) {
			_, id, session, _ := propose(t)
			before := writes.Load()
			downstreamStatus.Store(status)
			defer downstreamStatus.Store(0)
			code, _ := decide(id, session, `{"decision":"approve"}`)
			if code != 502 || writes.Load() != before+1 {
				t.Fatal("downstream failure became success or retried")
			}
			code, _ = decide(id, session, `{"decision":"approve"}`)
			if code != 403 || writes.Load() != before+1 {
				t.Fatal("failed write retried")
			}
		})
	}
}
