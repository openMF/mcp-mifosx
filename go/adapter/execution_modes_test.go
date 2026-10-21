// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter_test

import (
	"context"
	"net/http"
	"net/http/httptest"
	"sync/atomic"
	"testing"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

func TestExecutionModesUseSeparateIdentities(t *testing.T) {
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	var configuredCalls, callerCalls atomic.Int32
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") == "Bearer caller-secret" {
			callerCalls.Add(1)
			if r.Header.Get("fineract-platform-tenantid") != "caller-tenant" {
				t.Error("caller mode used configured tenant")
			}
		} else {
			configuredCalls.Add(1)
			user, password, ok := r.BasicAuth()
			if !ok || user != "configured-service" || password != "configured-password" ||
				r.Header.Get("fineract-platform-tenantid") != "configured-tenant" {
				t.Error("configured mode did not use configured identity")
			}
		}
		w.Write([]byte(`{"id":42}`))
	}))
	defer bank.Close()
	t.Setenv("MIFOSX_BASE_URL", bank.URL)

	// Both explicit local construction and the legacy constructor need no context.
	for _, newClient := range []func() (*adapter.FineractClient, error){adapter.NewConfigured, adapter.New} {
		configured, err := newClient()
		if err != nil {
			t.Fatal(err)
		}
		if _, err := configured.DoRequest(http.MethodGet, "clients/42", nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	caller, err := adapter.NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	if _, err := caller.DoCallerRequest(context.Background(), "clients/42"); err == nil {
		t.Fatal("configured environment rescued a missing caller context")
	}
	ctx, err := adapter.WithCallerContext(context.Background(), "Bearer caller-secret", "caller-tenant")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := caller.DoCallerRequest(ctx, "clients/42"); err != nil {
		t.Fatal(err)
	}
	if configuredCalls.Load() != 2 || callerCalls.Load() != 1 {
		t.Fatalf("unexpected requests: configured=%d caller=%d", configuredCalls.Load(), callerCalls.Load())
	}
}

func TestCallerCannotExecuteConfiguredRequests(t *testing.T) {
	// Guard the public method set against future embedding or forwarding that
	// would let an HTTP handler reach configured-auth execution.
	caller, err := adapter.NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := any(caller).(interface {
		DoRequest(string, string, interface{}, map[string]string) ([]byte, error)
	}); ok {
		t.Fatal("caller executor exposes configured request execution")
	}
	if _, ok := any(caller).(interface {
		DoMultipartRequest(string, string, map[string]string) ([]byte, error)
	}); ok {
		t.Fatal("caller executor exposes configured multipart execution")
	}
}
