// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter_test

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

func newAPIErrorTestClient(t *testing.T, handler http.HandlerFunc) *adapter.FineractClient {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	return &adapter.FineractClient{
		BaseURL:  server.URL,
		TenantID: "default",
		Username: "user",
		Password: "pass",
		HTTP:     server.Client(),
	}
}

func TestAPIErrorPreservesStatusCode(t *testing.T) {
	err := &adapter.APIError{StatusCode: http.StatusUnprocessableEntity}

	if err.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("status code is %d, want %d", err.StatusCode, http.StatusUnprocessableEntity)
	}
	if err.Error() != "API Error 422" {
		t.Fatalf("message is %q, want the unchanged form %q", err.Error(), "API Error 422")
	}
}

func TestAPIErrorCanBeRecoveredWithErrorsAs(t *testing.T) {
	wrapped := fmt.Errorf("repay loan: %w", &adapter.APIError{StatusCode: http.StatusConflict})

	var apiErr *adapter.APIError
	if !errors.As(wrapped, &apiErr) {
		t.Fatalf("errors.As did not recover APIError from %v", wrapped)
	}
	if apiErr.StatusCode != http.StatusConflict {
		t.Fatalf("status code is %d, want %d", apiErr.StatusCode, http.StatusConflict)
	}
}

func TestDoRequestContextKeepsBodyOnAPIError(t *testing.T) {
	const response = `{"developerMessage":"insufficient balance"}`
	client := newAPIErrorTestClient(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusUnprocessableEntity)
		_, _ = w.Write([]byte(response))
	})

	body, err := client.DoRequestContext(context.Background(), http.MethodPost,
		"loans/9/transactions?command=repayment", map[string]any{"transactionAmount": "10.00"}, nil)

	if string(body) != response {
		t.Fatalf("response body is %q, want %q", string(body), response)
	}
	if err == nil {
		t.Fatal("expected an API error")
	}
	if err.Error() != "API Error 422" {
		t.Fatalf("error message is %q, want %q", err.Error(), "API Error 422")
	}
	var apiErr *adapter.APIError
	if !errors.As(err, &apiErr) {
		t.Fatalf("errors.As did not recover APIError from %v", err)
	}
	if apiErr.StatusCode != http.StatusUnprocessableEntity {
		t.Fatalf("status code is %d, want %d", apiErr.StatusCode, http.StatusUnprocessableEntity)
	}
}

func TestDoRequestRemainsBackwardCompatible(t *testing.T) {
	t.Run("success", func(t *testing.T) {
		const response = `{"ok":true}`
		client := newAPIErrorTestClient(t, func(w http.ResponseWriter, r *http.Request) {
			_, _ = w.Write([]byte(response))
		})

		body, err := client.DoRequest(http.MethodGet, "clients/1", nil, nil)
		if err != nil {
			t.Fatalf("DoRequest returned an error: %v", err)
		}
		if string(body) != response {
			t.Fatalf("response body is %q, want %q", string(body), response)
		}
	})

	t.Run("api error", func(t *testing.T) {
		const response = `{"error":"bad request"}`
		client := newAPIErrorTestClient(t, func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = w.Write([]byte(response))
		})

		body, err := client.DoRequest(http.MethodGet, "clients/1", nil, nil)
		if string(body) != response {
			t.Fatalf("response body is %q, want %q", string(body), response)
		}
		if err == nil || err.Error() != "API Error 400" {
			t.Fatalf("unexpected error: %v", err)
		}
		var apiErr *adapter.APIError
		if !errors.As(err, &apiErr) {
			t.Fatalf("errors.As did not recover APIError from %v", err)
		}
		if apiErr.StatusCode != http.StatusBadRequest {
			t.Fatalf("status code is %d, want %d", apiErr.StatusCode, http.StatusBadRequest)
		}
	})
}
