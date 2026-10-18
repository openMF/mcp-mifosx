// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"log"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/client"
	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

const sessionToolMessage = `{"jsonrpc":"2.0","id":900,"method":"tools/call","params":{"name":"get_client_accounts","arguments":{"clientId":42,"Authorization":"Bearer forged","tenant":"forged"}}}`

func postSessionMessage(endpoint, auth, tenant, body string) (int, string, error) {
	req, err := http.NewRequest(http.MethodPost, endpoint, strings.NewReader(body))
	if err != nil {
		return 0, "", err
	}
	req.Header.Set("Content-Type", "application/json")
	if auth != "" {
		req.Header.Set("Authorization", auth)
	}
	if tenant != "" {
		req.Header.Set("X-Mifos-Tenant-Id", tenant)
	}
	httpClient := &http.Client{Timeout: 5 * time.Second}
	resp, err := httpClient.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer resp.Body.Close()
	data, err := io.ReadAll(resp.Body)
	return resp.StatusCode, string(data), err
}

func TestHTTPSessionBoundBeforeInitialization(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	c, err := client.NewSSEMCPClient(h.URL+"/sse", client.WithHeaders(map[string]string{
		"Authorization": "Bearer creator-secret", "X-Mifos-Tenant-Id": "creator-tenant",
	}))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = c.Close() })
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := c.Start(ctx); err != nil {
		t.Fatal(err)
	}
	endpoint := client.GetEndpoint(c).String()
	for _, identity := range [][2]string{
		{"Bearer different-creator-secret", "creator-tenant"},
		{"Bearer creator-secret", "different-creator-tenant"},
	} {
		status, _, err := postSessionMessage(endpoint, identity[0], identity[1], sessionToolMessage)
		if err != nil || status != http.StatusForbidden {
			t.Fatalf("session was not bound at creation: HTTP %d, error %v", status, err)
		}
	}
	status, _, err := postSessionMessage(endpoint, "Bearer creator-secret", "creator-tenant", `{"jsonrpc":"2.0","id":901,"method":"ping"}`)
	if err != nil || status != http.StatusAccepted {
		t.Fatalf("creator's first message rejected: HTTP %d, error %v", status, err)
	}
	if calls.Load() != 0 {
		t.Fatal("session takeover reached Fineract")
	}
}

func TestHTTPSessionCallerBinding(t *testing.T) {
	const auth, tenant = "Bearer session-owner-secret", "session-owner-tenant"
	ctx, err := adapter.WithCallerContext(context.Background(), auth, tenant)
	if err != nil {
		t.Fatal(err)
	}
	fingerprint, err := adapter.CallerFingerprint(ctx)
	if err != nil {
		t.Fatal(err)
	}
	var calls atomic.Int32
	var logs bytes.Buffer
	old := log.Writer()
	log.SetOutput(&logs)
	t.Cleanup(func() { log.SetOutput(old) })
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
		if r.Header.Get("Authorization") != auth || r.Header.Get("fineract-platform-tenantid") != tenant {
			t.Error("session caller identity changed")
		}
		w.Write([]byte(`{"loanAccounts":[]}`))
	})
	c := connect(t, h.URL, auth, tenant)
	endpoint := client.GetEndpoint(c).String()
	for _, tc := range []struct {
		name, auth, tenant string
		status             int
	}{
		{"different authorization", "Bearer other-session-secret", tenant, http.StatusForbidden},
		{"different tenant", auth, "other-session-tenant", http.StatusForbidden},
		{"different caller and tenant", "Bearer other-session-secret", "other-session-tenant", http.StatusForbidden},
		{"missing authorization", "", tenant, http.StatusUnauthorized},
		{"missing tenant", auth, "", http.StatusUnauthorized},
		{"missing identity", "", "", http.StatusUnauthorized},
	} {
		t.Run(tc.name, func(t *testing.T) {
			status, body, err := postSessionMessage(endpoint, tc.auth, tc.tenant, sessionToolMessage)
			if err != nil {
				t.Fatal(err)
			}
			if status != tc.status {
				t.Fatalf("got HTTP %d, want %d", status, tc.status)
			}
			if status == http.StatusForbidden && strings.TrimSpace(body) != "session does not belong to the authenticated caller" {
				t.Fatal("ownership error changed")
			}
			for _, secret := range []string{auth, tenant, "other-session-secret", "other-session-tenant", "configured-service", "configured-password", fmt.Sprintf("%x", fingerprint)} {
				if strings.Contains(body+logs.String(), secret) {
					t.Fatal("session ownership disclosed sensitive context")
				}
			}
			if calls.Load() != 0 {
				t.Fatal("rejected session reuse reached Fineract")
			}
		})
	}
	// Rejections must not replace the owner or break its existing SSE stream.
	for i := 0; i < 2; i++ {
		result, err := callAccounts(c, 42)
		if err != nil || result == nil || result.IsError {
			t.Fatalf("session owner could not continue: %v", err)
		}
	}
	if calls.Load() != 2 {
		t.Fatalf("got %d downstream requests, want 2", calls.Load())
	}
}

func TestHTTPSessionConcurrentOwners(t *testing.T) {
	var callsA, callsB atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) {
		switch r.Header.Get("Authorization") {
		case "Bearer session-A-secret":
			callsA.Add(1)
			if r.Header.Get("fineract-platform-tenantid") != "session-tenant-A" || r.URL.Path != "/clients/42/accounts" {
				t.Error("caller A context leaked")
			}
			w.Write([]byte(`{"clientId":42}`))
		case "Bearer session-B-secret":
			callsB.Add(1)
			if r.Header.Get("fineract-platform-tenantid") != "session-tenant-B" || r.URL.Path != "/clients/73/accounts" {
				t.Error("caller B context leaked")
			}
			w.Write([]byte(`{"clientId":73}`))
		default:
			t.Error("unexpected downstream caller")
		}
	})
	a := connect(t, h.URL, "Bearer session-A-secret", "session-tenant-A")
	b := connect(t, h.URL, "Bearer session-B-secret", "session-tenant-B")
	var wg sync.WaitGroup
	for _, tc := range []struct {
		caller                 *client.Client
		id                     int
		otherAuth, otherTenant string
	}{
		{a, 42, "Bearer session-B-secret", "session-tenant-B"},
		{b, 73, "Bearer session-A-secret", "session-tenant-A"},
	} {
		for i := 0; i < 10; i++ {
			wg.Go(func() {
				status, _, err := postSessionMessage(client.GetEndpoint(tc.caller).String(), tc.otherAuth, tc.otherTenant, sessionToolMessage)
				if err != nil || status != http.StatusForbidden {
					t.Errorf("concurrent session reuse: HTTP %d, error %v", status, err)
				}
			})
			wg.Go(func() {
				result, err := callAccounts(tc.caller, tc.id)
				if err != nil || result == nil || result.IsError {
					t.Errorf("concurrent owner call failed: %v", err)
					return
				}
				if len(result.Content) != 1 {
					t.Error("missing owner response")
					return
				}
				content, ok := result.Content[0].(mcp.TextContent)
				if !ok || content.Text != fmt.Sprintf(`{"clientId":%d}`, tc.id) {
					t.Error("response leaked across sessions")
				}
			})
		}
	}
	wg.Wait()
	if callsA.Load() != 10 || callsB.Load() != 10 {
		t.Fatalf("unexpected downstream counts: A=%d B=%d", callsA.Load(), callsB.Load())
	}
}

func TestHTTPSessionOwnershipRemovedOnDisconnect(t *testing.T) {
	var calls atomic.Int32
	h, _ := pilot(t, func(w http.ResponseWriter, r *http.Request) { calls.Add(1) })
	c := connect(t, h.URL, "Bearer closing-session-secret", "closing-session-tenant")
	endpoint := client.GetEndpoint(c).String()
	if err := c.Close(); err != nil {
		t.Fatal(err)
	}
	// Transport teardown is asynchronous. Ping avoids tool execution while
	// waiting for the unregister hook to remove the ownership entry.
	deadline := time.Now().Add(5 * time.Second)
	for {
		status, _, err := postSessionMessage(endpoint, "Bearer closing-session-secret", "closing-session-tenant", `{"jsonrpc":"2.0","id":901,"method":"ping"}`)
		if err != nil {
			t.Fatal(err)
		}
		if status == http.StatusForbidden {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("closed session ownership was retained")
		}
		time.Sleep(10 * time.Millisecond)
	}
	if calls.Load() != 0 {
		t.Fatal("session cleanup reached Fineract")
	}
}
