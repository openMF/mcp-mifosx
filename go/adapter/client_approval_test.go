// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package adapter

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

type approvalRoundTripper func(*http.Request) (*http.Response, error)

func (f approvalRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestApprovedClientDisablesTransportBodyReplay(t *testing.T) {
	const card = "card-00000000-0000-0000-0000-000000000001"
	const key = "cop-00000000-0000-0000-0000-000000000002"
	const payload = `{"firstname":"Alice","lastname":"Officer","officeId":1,"active":true,"activationDate":"2026-10-23","dateFormat":"yyyy-MM-dd","locale":"en"}`
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/copilot/api/v1/internal/mcp/claims/"+card || r.Header.Get("X-MCP-Decision-Token") != "human-ticket" {
			t.Error("missing human decision claim")
		}
		json.NewEncoder(w).Encode(map[string]string{"payload": payload, "idempotency_key": key, "card_id": card})
	}))
	defer gateway.Close()
	writes := 0
	caller := &CallerClient{baseURL: "https://bank.invalid", http: &http.Client{Transport: approvalRoundTripper(func(r *http.Request) (*http.Response, error) {
		body := `{"authenticated":true,"permissions":["CREATE_CLIENT"]}`
		if r.URL.Path == "/clients" {
			writes++
			if r.Method != http.MethodPost || r.GetBody != nil || r.ContentLength == 0 {
				t.Error("approved POST permits automatic transport replay")
			}
			if r.Header.Get("Idempotency-Key") != key {
				t.Error("stored idempotency key lost")
			}
			data, _ := io.ReadAll(r.Body)
			if string(data) != payload {
				t.Error("immutable approved bytes changed")
			}
			body = `{"resourceId":42}`
		}
		if r.Header.Get("Authorization") != "Bearer officer-secret" || r.Header.Get("fineract-platform-tenantid") != "tenant-X" {
			t.Error("caller identity changed")
		}
		return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
	})}}
	ctx, err := WithCallerContext(context.Background(), "Bearer officer-secret", "tenant-X")
	if err != nil {
		t.Fatal(err)
	}
	bridge := &ApprovalBridge{baseURL: gateway.URL, token: "private-bridge", http: gateway.Client()}
	if _, err := caller.ExecuteApprovedClient(ctx, bridge, "session", card, "human-ticket"); err != nil {
		t.Fatal(err)
	}
	if writes != 1 {
		t.Fatal("incorrect number of write attempts")
	}
}
