// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package adapter

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestClientUpdateValidation(t *testing.T) {
	for _, raw := range []string{
		`{}`, `{"clientId":123}`, `{"clientId":0,"firstname":"A"}`, `{"clientId":-1,"firstname":"A"}`,
		`{"clientId":1.5,"firstname":"A"}`, `{"clientId":"123","firstname":"A"}`,
		`{"clientId":9223372036854775808,"firstname":"A"}`, `{"clientId":123,"firstname":""}`,
		`{"clientId":123,"firstname":" "}`, `{"clientId":123,"firstname":true}`,
		`{"clientId":123,"firstname":null,"lastname":"B"}`, `{"clientId":123,"firstname":"A\nB"}`,
		`{"clientId":123,"firstname":"A","approve":true}`, `{"clientId":123,"firstname":"A","officeId":2}`,
		`{"clientId":123,"firstname":"A","Authorization":"injected"}`, `{"clientId":123,"firstname":"A"} {}`,
		`{"clientId":123,"firstname":"` + strings.Repeat("A", 201) + `"}`,
	} {
		if _, err := ParseClientUpdate([]byte(raw)); err == nil {
			t.Errorf("invalid update accepted: %s", raw)
		}
	}
	for _, raw := range []string{`{"clientId":123,"firstname":"A"}`, `{"clientId":123,"lastname":"B"}`, `{"clientId":123,"firstname":"A","lastname":"B"}`} {
		if _, err := ParseClientUpdate([]byte(raw)); err != nil {
			t.Fatal(err)
		}
	}
}

func TestApprovedUpdateExecutionAndFailures(t *testing.T) {
	const card = "card-00000000-0000-0000-0000-000000000001"
	const key = "cop-00000000-0000-0000-0000-000000000002"
	for _, status := range []int{200, 400, 401, 403, 404, 409, 422, 500} {
		t.Run(fmt.Sprint(status), func(t *testing.T) {
			var writes, outcomes int
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.Contains(r.URL.Path, "/claims/") {
					json.NewEncoder(w).Encode(map[string]string{"tool": "update_client", "payload": `{"clientId":123,"lastname":"B"}`, "card_id": card, "idempotency_key": key})
				} else {
					outcomes++
					var result clientAuditOutcome
					if json.NewDecoder(r.Body).Decode(&result) != nil || result.HTTPStatus != status || result.Success != (status == 200) {
						t.Error("incorrect audit outcome")
					}
					io.WriteString(w, `{}`)
				}
			}))
			defer gateway.Close()
			caller := &CallerClient{baseURL: "https://bank.invalid", http: &http.Client{Transport: approvalRoundTripper(func(r *http.Request) (*http.Response, error) {
				if r.Header.Get("Authorization") != "Bearer officer-secret" || r.Header.Get("Fineract-Platform-TenantId") != "tenant-X" {
					t.Error("caller changed")
				}
				code, body := 200, `{"authenticated":true,"permissions":["UPDATE_CLIENT"]}`
				if r.URL.Path != "/userdetails" {
					writes++
					data, _ := io.ReadAll(r.Body)
					if r.Method != http.MethodPut || r.URL.Path != "/clients/123" || string(data) != `{"lastname":"B"}` || r.GetBody != nil || r.Header.Get("X-Correlation-Id") != card || r.Header.Get("Idempotency-Key") != key {
						t.Error("approved update or replay protection changed")
					}
					code, body = status, `{"resourceId":123}`
				}
				return &http.Response{StatusCode: code, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(body))}, nil
			})}}
			ctx, _ := WithCallerContext(context.Background(), "Bearer officer-secret", "tenant-X")
			bridge := &ApprovalBridge{baseURL: gateway.URL, token: "bridge", http: gateway.Client()}
			if _, err := caller.ExecuteApprovedClientUpdate(ctx, bridge, "session", card, "ticket"); (err != nil) != (status != 200) {
				t.Fatalf("unexpected result: %v", err)
			}
			if writes != 1 || outcomes != 1 {
				t.Fatal("update or outcome repeated")
			}
		})
	}
}

func TestUpdateMissingCallerNeverFallsBack(t *testing.T) {
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	caller := &CallerClient{}
	bridge := &ApprovalBridge{}
	if _, err := caller.ProposeClientUpdate(context.Background(), bridge, "session", map[string]any{"clientId": 123, "firstname": "A"}); err == nil {
		t.Fatal("missing caller proposal accepted")
	}
	if _, err := caller.ExecuteApprovedClientUpdate(context.Background(), bridge, "session", "card-00000000-0000-0000-0000-000000000001", "ticket"); err == nil {
		t.Fatal("missing caller execution accepted")
	}
}

func TestApprovedClientCannotSubstituteOperation(t *testing.T) {
	const card = "card-00000000-0000-0000-0000-000000000001"
	for _, tool := range []string{"create_client", "update_client"} {
		t.Run(tool, func(t *testing.T) {
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				if strings.Contains(r.URL.Path, "/claims/") {
					json.NewEncoder(w).Encode(map[string]string{"tool": tool, "payload": `{"clientId":123,"firstname":"A"}`, "card_id": card, "idempotency_key": "cop-00000000-0000-0000-0000-000000000002"})
				} else {
					var outcome clientAuditOutcome
					if json.NewDecoder(r.Body).Decode(&outcome) != nil || outcome.Success || outcome.FailureClass != "invalid_claim" {
						t.Error("operation mismatch not audited")
					}
					io.WriteString(w, `{}`)
				}
			}))
			defer gateway.Close()
			caller := &CallerClient{baseURL: "https://bank.invalid", http: &http.Client{Transport: approvalRoundTripper(func(r *http.Request) (*http.Response, error) {
				if r.URL.Path != "/userdetails" {
					t.Error("substituted operation reached Fineract")
				}
				return &http.Response{StatusCode: 200, Header: make(http.Header), Body: io.NopCloser(strings.NewReader(`{"authenticated":true,"permissions":["ALL_FUNCTIONS"]}`))}, nil
			})}}
			ctx, _ := WithCallerContext(context.Background(), "Bearer officer-secret", "tenant-X")
			bridge := &ApprovalBridge{baseURL: gateway.URL, token: "bridge", http: gateway.Client()}
			execute := caller.ExecuteApprovedClient
			if tool == "create_client" {
				execute = caller.ExecuteApprovedClientUpdate
			}
			if _, err := execute(ctx, bridge, "session", card, "ticket"); err == nil {
				t.Fatal("operation substitution accepted")
			}
		})
	}
}

func TestClientUpdateUnicodeCharacterLimit(t *testing.T) {
	for _, character := range []string{"A", "é", "𐐀"} {
		for _, field := range []string{"firstname", "lastname"} {
			for _, count := range []int{150, 200, 201} {
				t.Run(fmt.Sprintf("%s/%s/%d", character, field, count), func(t *testing.T) {
					raw, err := json.Marshal(map[string]any{"clientId": 123, field: strings.Repeat(character, count)})
					if err != nil {
						t.Fatal(err)
					}
					_, err = ParseClientUpdate(raw)
					if (err != nil) != (count > 200) {
						t.Fatalf("%d code points: %v", count, err)
					}
				})
			}
		}
	}
}

func TestClientUpdateMarshalErrorIsWrapped(t *testing.T) {
	caller := &CallerClient{}
	_, err := caller.ProposeClientUpdate(context.Background(), &ApprovalBridge{}, "session", map[string]any{"firstname": make(chan int)})
	var marshalErr *json.UnsupportedTypeError
	if !errors.As(err, &marshalErr) {
		t.Fatalf("marshal error was not wrapped: %v", err)
	}
}
