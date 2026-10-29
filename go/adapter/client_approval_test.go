// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package adapter

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync/atomic"
	"testing"
)

type approvalRoundTripper func(*http.Request) (*http.Response, error)

func (f approvalRoundTripper) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

func TestApprovedClientDisablesTransportBodyReplay(t *testing.T) {
	const card = "card-00000000-0000-0000-0000-000000000001"
	const key = "cop-00000000-0000-0000-0000-000000000002"
	const payload = `{"firstname":"Alice","lastname":"Officer","officeId":1,"active":true,"activationDate":"2026-10-23","dateFormat":"yyyy-MM-dd","locale":"en"}`
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/copilot/api/v1/internal/mcp/results/"+card {
			io.WriteString(w, `{}`)
			return
		}
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

func TestApprovedClientAuditOutcomes(t *testing.T) {
	const card = "card-00000000-0000-0000-0000-000000000001"
	const key = "cop-00000000-0000-0000-0000-000000000002"
	const payload = `{"firstname":"Alice","lastname":"Officer","officeId":1,"active":true,"activationDate":"2026-10-23","dateFormat":"yyyy-MM-dd","locale":"en"}`
	for _, tc := range []struct {
		name          string
		status        int
		transportFail bool
		claimFail     bool
		resultFail    bool
		cancel        bool
		wantSuccess   bool
		wantClass     string
	}{
		{name: "created", status: 201, wantSuccess: true},
		{name: "bank rejected", status: 403, wantClass: "http_error"},
		{name: "bank failure", status: 500, wantClass: "http_error"},
		{name: "transport failure", transportFail: true, wantClass: "transport_error"},
		{name: "audit claim unavailable", claimFail: true},
		{name: "result persistence unavailable", status: 200, resultFail: true, wantSuccess: true},
		{name: "cancelled callback still reports", status: 200, cancel: true, wantSuccess: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			var writes, reports atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if r.Header.Get("Authorization") != "Bearer officer-secret" || r.Header.Get("Fineract-Platform-TenantId") != "tenant-X" || r.Header.Get("X-MCP-Session-Id") != "session" {
					t.Error("audit report changed security context")
				}
				switch r.URL.Path {
				case "/copilot/api/v1/internal/mcp/claims/" + card:
					if tc.claimFail {
						w.WriteHeader(503)
						return
					}
					json.NewEncoder(w).Encode(map[string]string{"payload": payload, "idempotency_key": key, "card_id": card})
				case "/copilot/api/v1/internal/mcp/results/" + card:
					reports.Add(1)
					data, _ := io.ReadAll(r.Body)
					var result clientAuditOutcome
					if json.Unmarshal(data, &result) != nil || result.Success != tc.wantSuccess || result.FailureClass != tc.wantClass || result.HTTPStatus != tc.status {
						t.Errorf("unexpected safe outcome: %s", data)
					}
					for _, secret := range []string{"officer-secret", "Authorization", "Alice", "payload", key} {
						if strings.Contains(string(data), secret) {
							t.Error("outcome persisted secret or unnecessary payload")
						}
					}
					if tc.resultFail {
						w.WriteHeader(503)
						return
					}
					io.WriteString(w, `{}`)
				default:
					t.Error("unexpected bridge operation")
				}
			}))
			defer gateway.Close()
			ctx, err := WithCallerContext(context.Background(), "Bearer officer-secret", "tenant-X")
			if err != nil {
				t.Fatal(err)
			}
			ctx, cancel := context.WithCancel(ctx)
			defer cancel()
			caller := &CallerClient{baseURL: "https://bank.invalid", http: &http.Client{Transport: approvalRoundTripper(func(r *http.Request) (*http.Response, error) {
				body := `{"authenticated":true,"permissions":["CREATE_CLIENT"]}`
				status := 200
				if r.URL.Path == "/clients" {
					writes.Add(1)
					if r.Header.Get("X-Correlation-Id") != card {
						t.Error("correlation changed during execution")
					}
					if tc.transportFail {
						return nil, errors.New("transport echoed officer-secret")
					}
					if tc.cancel {
						cancel()
					}
					status = tc.status
					body = `{"resourceId":42,"ignored":"unnecessary PII"}`
				}
				return &http.Response{StatusCode: status, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
			})}}
			bridge := &ApprovalBridge{baseURL: gateway.URL, token: "private-bridge", http: gateway.Client()}
			data, err := caller.ExecuteApprovedClient(ctx, bridge, "session", card, "ticket")
			wantErr := !tc.wantSuccess || tc.resultFail
			if (err != nil) != wantErr {
				t.Fatalf("unexpected result: %v", err)
			}
			if tc.claimFail {
				if writes.Load() != 0 || reports.Load() != 0 {
					t.Fatal("failed audit claim performed a write")
				}
			} else if writes.Load() != 1 || reports.Load() != 1 {
				t.Fatal("write or result was retried or omitted")
			}
			if tc.resultFail && (data != nil || !strings.Contains(err.Error(), "do not retry")) {
				t.Fatal("audit result failure was hidden")
			}
			if err != nil && strings.Contains(err.Error(), "officer-secret") {
				t.Fatal("error exposed credentials")
			}
		})
	}
}
