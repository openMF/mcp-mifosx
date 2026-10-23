// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"context"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/cookiejar"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync/atomic"
	"testing"
)

func TestPermissionLookupRequiresCaller(t *testing.T) {
	t.Setenv("MIFOSX_USERNAME", "configured-service")
	t.Setenv("MIFOSX_PASSWORD", "configured-password")
	t.Setenv("MIFOSX_TENANT_ID", "configured-tenant")
	var calls atomic.Int32
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		calls.Add(1)
	}))
	defer bank.Close()
	t.Setenv("MIFOSX_BASE_URL", bank.URL)
	c, err := NewCaller()
	if err != nil {
		t.Fatal(err)
	}
	contexts := []context.Context{context.Background()}
	for _, auth := range []string{"unknown", "Digest private-token", "Basic invalid-base64", "Basic " + base64.StdEncoding.EncodeToString([]byte("no-colon")), "Bearer "} {
		ctx, err := WithCallerContext(context.Background(), auth, "tenant")
		if err != nil {
			t.Fatal(err)
		}
		contexts = append(contexts, ctx)
	}
	for _, ctx := range contexts {
		if allowed, err := c.ClientReadPermitted(ctx); allowed || err == nil {
			t.Fatal("unverified identity granted permission")
		}
	}
	if calls.Load() != 0 {
		t.Fatal("missing or unsupported caller identity reached Fineract")
	}
}

func TestPermissionLookupUsesCallerAuthentication(t *testing.T) {
	for _, scheme := range []string{"Basic", "bAsIc", "Bearer"} {
		t.Run(scheme, func(t *testing.T) {
			auth := scheme + " " + base64.StdEncoding.EncodeToString([]byte("officer:private:password\"&"))
			var calls atomic.Int32
			bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls.Add(1)
				if r.Header.Get("Authorization") != auth || r.Header.Get("fineract-platform-tenantid") != "caller-tenant" || r.Header.Get("Cookie") != "" || r.URL.RawQuery != "" {
					t.Error("lookup changed caller identity or used shared cookies/query credentials")
				}
				if strings.EqualFold(scheme, "Basic") {
					var credentials map[string]string
					if r.Method != http.MethodPost || r.URL.Path != "/authentication" || r.Header.Get("Content-Type") != "application/json" || json.NewDecoder(r.Body).Decode(&credentials) != nil || credentials["username"] != "officer" || credentials["password"] != "private:password\"&" || len(credentials) != 2 {
						t.Error("Basic lookup did not use the caller JSON credentials")
					}
				} else if r.Method != http.MethodGet || r.URL.Path != "/userdetails" || r.ContentLength != 0 {
					t.Error("Bearer lookup did not use authenticated userdetails")
				}
				// Real authentication responses contain key/token material. Only
				// the permission projection may leave the adapter.
				json.NewEncoder(w).Encode(map[string]any{"authenticated": true, "permissions": []string{"READ_CLIENT"}, "base64EncodedAuthenticationKey": strings.SplitN(auth, " ", 2)[1], "accessToken": "private-token"})
			}))
			defer bank.Close()
			client := bank.Client()
			client.Jar, _ = cookiejar.New(nil)
			bankURL, _ := url.Parse(bank.URL)
			client.Jar.SetCookies(bankURL, []*http.Cookie{{Name: "session", Value: "other-caller"}})
			c := &CallerClient{baseURL: bank.URL, http: client}
			ctx, _ := WithCallerContext(context.Background(), auth, "caller-tenant")
			if allowed, err := c.ClientReadPermitted(ctx); !allowed || err != nil || calls.Load() != 1 {
				t.Fatalf("lookup failed: allowed=%v error=%v calls=%d", allowed, err, calls.Load())
			}
		})
	}
}

func TestPermissionLookupTransportFailures(t *testing.T) {
	ctx, _ := WithCallerContext(context.Background(), "Bearer private-token", "tenant")
	for _, mode := range []string{"redirect", "transport", "oversized", "read-error"} {
		t.Run(mode, func(t *testing.T) {
			const limit = 10 << 20
			body := &trackedResponseBody{Reader: strings.NewReader(`{"authenticated":true,"permissions":["READ_CLIENT"]}`)}
			c := &CallerClient{baseURL: "https://fineract.example", http: &http.Client{
				Transport: responseTransport(func(*http.Request) (*http.Response, error) {
					switch mode {
					case "transport":
						return nil, errors.New("private-token")
					case "oversized":
						body.Reader = strings.NewReader(strings.Repeat(" ", limit) + `{"authenticated":true,"permissions":["READ_CLIENT"]}`)
					case "read-error":
						body.Reader = io.MultiReader(body.Reader, failingPermissionReader{})
					case "redirect":
						return &http.Response{StatusCode: http.StatusTemporaryRedirect, Header: http.Header{"Location": {"https://fineract.example/redirect"}}, Body: body}, nil
					}
					return &http.Response{StatusCode: http.StatusOK, Body: body}, nil
				}),
			}}
			if allowed, err := c.ClientReadPermitted(ctx); allowed || err == nil || strings.Contains(err.Error(), "private-token") {
				t.Fatalf("lookup failure did not fail closed: %v %v", allowed, err)
			}
			if mode != "transport" && (!body.closed || body.read > limit+1) {
				t.Fatal("permission response was not bounded and closed")
			}
		})
	}
}

type failingPermissionReader struct{}

func (failingPermissionReader) Read([]byte) (int, error) { return 0, errors.New("private-token") }
