// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"context"
	"encoding/base64"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestCallerFingerprint(t *testing.T) {
	if _, err := CallerFingerprint(context.Background()); err == nil {
		t.Fatal("fingerprint accepted missing caller context")
	}
	fingerprint := func(auth, tenant string) [32]byte {
		t.Helper()
		ctx, err := WithCallerContext(context.Background(), auth, tenant)
		if err != nil {
			t.Fatal(err)
		}
		value, err := CallerFingerprint(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return value
	}
	owner := fingerprint("Bearer secret", "tenant")
	if owner != fingerprint("Bearer secret", "tenant") {
		t.Fatal("same caller fingerprint changed")
	}
	if owner == fingerprint("Bearer other-secret", "tenant") || owner == fingerprint("Bearer secret", "other-tenant") {
		t.Fatal("fingerprint did not include both authorization and tenant")
	}
	if fingerprint("Bearer ab", "c") == fingerprint("Bearer a", "bc") {
		t.Fatal("fingerprint pair encoding is ambiguous")
	}
}

func TestCallerRequestRequiresContextAndAllowedEndpoint(t *testing.T) {
	calls := 0
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { calls++ }))
	defer bank.Close()
	c := &FineractClient{BaseURL: bank.URL, Username: "service", Password: "service-secret", TenantID: "default", HTTP: bank.Client()}
	if _, err := c.DoCallerRequest(context.Background(), "clients/42"); err == nil {
		t.Fatal("missing context accepted")
	}
	for _, values := range [][2]string{{"", "tenant"}, {"Bearer secret", ""}, {" \t", "tenant"}, {"Bearer secret", " \t"}, {"Bearer secret\r\n", "tenant"}} {
		if _, err := WithCallerContext(context.Background(), values[0], values[1]); err == nil {
			t.Fatal("invalid identity accepted")
		}
	}
	ctx, err := WithCallerContext(context.Background(), "Bearer secret", "tenant")
	if err != nil {
		t.Fatal(err)
	}
	for _, endpoint := range []string{"loans/42", "clients/42?command=delete", "clients/../loans", "clients/0"} {
		if _, err := c.DoCallerRequest(ctx, endpoint); err == nil {
			t.Fatal("unsupported endpoint accepted")
		}
	}
	if calls != 0 {
		t.Fatal("invalid request reached Fineract")
	}
	call, _ := callerContext(ctx)
	if strings.Contains(fmt.Sprint(call), "secret") {
		t.Fatal("context formatting disclosed authorization")
	}
}

type responseTransport func(*http.Request) (*http.Response, error)

func (f responseTransport) RoundTrip(r *http.Request) (*http.Response, error) { return f(r) }

type trackedResponseBody struct {
	io.Reader
	read   int
	closed bool
}

func (b *trackedResponseBody) Read(p []byte) (int, error) {
	n, err := b.Reader.Read(p)
	b.read += n
	return n, err
}

func (b *trackedResponseBody) Close() error { b.closed = true; return nil }

func TestCallerResponseSizeLimit(t *testing.T) {
	const limit = 10 << 20
	for _, size := range []int{limit - 1, limit, limit + 1, 2 * limit} {
		t.Run(fmt.Sprint(size), func(t *testing.T) {
			body := &trackedResponseBody{Reader: strings.NewReader(strings.Repeat("x", size))}
			c := &FineractClient{BaseURL: "https://fineract.example", HTTP: &http.Client{
				Transport: responseTransport(func(*http.Request) (*http.Response, error) {
					return &http.Response{StatusCode: http.StatusOK, Body: body}, nil
				}),
			}}
			ctx, _ := WithCallerContext(context.Background(), "Bearer private-token", "tenant")
			data, err := c.DoCallerRequest(ctx, "clients/42")
			if size <= limit {
				if err != nil || len(data) != size {
					t.Fatalf("allowed response rejected: length=%d error=%v", len(data), err)
				}
			} else if err == nil || len(data) != 0 || err.Error() != "could not read Fineract response" {
				t.Fatalf("oversized response not safely rejected: length=%d error=%v", len(data), err)
			}
			if body.read > limit+1 {
				t.Fatalf("read %d bytes past limit", body.read)
			}
			if !body.closed {
				t.Fatal("response body not closed")
			}
		})
	}
}

func TestCallerRequestDoesNotFollowRedirects(t *testing.T) {
	redirected := 0
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { redirected++ }))
	defer target.Close()
	bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Redirect(w, r, target.URL, http.StatusTemporaryRedirect)
	}))
	defer bank.Close()
	c := &FineractClient{BaseURL: bank.URL, HTTP: bank.Client()}
	ctx, _ := WithCallerContext(context.Background(), "Bearer private-token", "tenant")
	data, err := c.DoCallerRequest(ctx, "clients/42")
	if err == nil || !strings.Contains(err.Error(), "307") || len(data) != 0 || redirected != 0 {
		t.Fatalf("redirect not refused: %s %v %d", data, err, redirected)
	}
}

func TestCallerResponseDoesNotEchoCredentials(t *testing.T) {
	auth := "Basic " + base64.StdEncoding.EncodeToString([]byte("private-user:private-password"))
	for _, secret := range []string{auth, strings.TrimPrefix(auth, "Basic "), "private-user:private-password", "private-user", "private-password"} {
		t.Run(secret, func(t *testing.T) {
			bank := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(secret)) }))
			defer bank.Close()
			c := &FineractClient{BaseURL: bank.URL, HTTP: bank.Client()}
			ctx, _ := WithCallerContext(context.Background(), auth, "tenant")
			data, err := c.DoCallerRequest(ctx, "clients/42")
			if err == nil || len(data) != 0 || strings.Contains(err.Error(), secret) {
				t.Fatalf("credential echo escaped: %s %v", data, err)
			}
		})
	}
}
