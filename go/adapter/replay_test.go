// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"context"
	"crypto/tls"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// replayBank is a Fineract stand-in that can read a write and then drop the
// connection before any response byte reaches the client, like a backend that
// committed the transaction while the response was lost.
type replayBank struct {
	posts atomic.Int32
	gets  atomic.Int32
	proto atomic.Value // "HTTP/1.1" or "HTTP/2.0"

	mu          sync.Mutex
	keys        []string
	bodies      []string
	postRemotes []string
	getRemotes  []string

	// loseFirst drops the response to the first write; later writes succeed so
	// a replayed request is observable instead of looping forever.
	loseFirst bool
	// abortWithPanic drops the response by resetting the stream, which is the
	// only way an HTTP/2 handler can fail before writing headers.
	abortWithPanic bool
}

func (b *replayBank) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	b.proto.Store(r.Proto)
	if r.Method == http.MethodGet {
		b.gets.Add(1)
		b.mu.Lock()
		b.getRemotes = append(b.getRemotes, r.RemoteAddr)
		b.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"ok":true}`)
		return
	}

	body, err := io.ReadAll(r.Body)
	if err != nil {
		http.Error(w, "unreadable body", http.StatusBadRequest)
		return
	}
	n := int(b.posts.Add(1))
	b.mu.Lock()
	b.keys = append(b.keys, r.Header.Get("Idempotency-Key"))
	b.bodies = append(b.bodies, string(body))
	b.postRemotes = append(b.postRemotes, r.RemoteAddr)
	lose := b.loseFirst && n == 1
	b.mu.Unlock()

	if lose {
		if b.abortWithPanic {
			panic(http.ErrAbortHandler)
		}
		conn, _, err := w.(http.Hijacker).Hijack()
		if err != nil {
			panic(err)
		}
		// Close without writing a status line: the write was applied but the
		// client never learns the outcome.
		_ = conn.Close()
		return
	}

	w.Header().Set("Content-Type", "application/json")
	fmt.Fprint(w, `{"resourceId":42}`)
}

func (b *replayBank) snapshot() (keys, bodies, postRemotes, getRemotes []string) {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]string(nil), b.keys...), append([]string(nil), b.bodies...),
		append([]string(nil), b.postRemotes...), append([]string(nil), b.getRemotes...)
}

func newPlainReplayClient(t *testing.T, handler http.Handler) *FineractClient {
	t.Helper()
	server := httptest.NewServer(handler)
	t.Cleanup(server.Close)
	transport := &http.Transport{}
	t.Cleanup(transport.CloseIdleConnections)
	return &FineractClient{
		BaseURL:  server.URL,
		TenantID: "test-tenant",
		Username: "tester",
		Password: "secret",
		HTTP:     &http.Client{Transport: transport, Timeout: 10 * time.Second},
	}
}

// warmPool sends a read through the same client so the following write travels
// on a reused connection. net/http only retries a request whose connection has
// already carried traffic successfully.
func warmPool(t *testing.T, client *FineractClient) {
	t.Helper()
	response := client.DoRequestOutcome(context.Background(), http.MethodGet, "journalentries", nil, nil, nil)
	if response.Err != nil || response.StatusCode != http.StatusOK {
		t.Fatalf("warm-up read failed: status=%d err=%v", response.StatusCode, response.Err)
	}
}

// replayCases covers both body shapes net/http treats as replayable: a body it
// can recreate with GetBody, and no body at all.
var replayCases = []struct {
	name     string
	body     any
	wantBody string
}{
	{"json body", map[string]any{"officeId": 1, "amount": 10}, `{"amount":10,"officeId":1}`},
	{"empty body", nil, ""},
}

// TestUnverifiedDedupDoesNotReplayMoneyWrite reproduces the reported hazard:
// a money POST carrying an Idempotency-Key is applied by the backend, the
// response is lost on a reused connection, and net/http must not send the
// write again while backend deduplication is unproven.
func TestUnverifiedDedupDoesNotReplayMoneyWrite(t *testing.T) {
	for _, tc := range replayCases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "")
			bank := &replayBank{loseFirst: true}
			client := newPlainReplayClient(t, bank)
			warmPool(t, client)

			response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries", tc.body, nil,
				map[string]string{"Idempotency-Key": "money-key-0001"})

			if response.Err == nil {
				t.Fatal("a lost response must surface as a transport error")
			}
			if response.StatusCode != 0 {
				t.Fatalf("an unanswered write must not report an HTTP status, got %d", response.StatusCode)
			}
			if got := bank.posts.Load(); got != 1 {
				t.Fatalf("the money POST reached Fineract %d times, want 1", got)
			}

			keys, bodies, postRemotes, getRemotes := bank.snapshot()
			if len(keys) != 1 || keys[0] != "money-key-0001" {
				t.Fatalf("the idempotency key was not preserved on the wire: %v", keys)
			}
			if len(bodies) != 1 || bodies[0] != tc.wantBody {
				t.Fatalf("the request body changed: got %q want %q", bodies, tc.wantBody)
			}
			if len(postRemotes) != 1 || len(getRemotes) != 1 || postRemotes[0] != getRemotes[0] {
				t.Fatalf("the POST did not reuse the warmed connection: posts=%v gets=%v", postRemotes, getRemotes)
			}
			if proto, _ := bank.proto.Load().(string); proto != "HTTP/1.1" {
				t.Fatalf("expected HTTP/1.1, got %q", proto)
			}
		})
	}
}

// TestVerifiedDedupKeepsTransportReplay is the control for the test above. It
// proves the harness really does exercise net/http's automatic replay: with
// backend deduplication verified the same lost response is retried, so the
// unverified test would have counted two POSTs without the guard. It also pins
// the documented behaviour that verified deployments keep.
func TestVerifiedDedupKeepsTransportReplay(t *testing.T) {
	for _, tc := range replayCases {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
			bank := &replayBank{loseFirst: true}
			client := newPlainReplayClient(t, bank)
			warmPool(t, client)

			response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries", tc.body, nil,
				map[string]string{"Idempotency-Key": "money-key-0001"})

			if response.Err != nil || response.StatusCode != http.StatusOK {
				t.Fatalf("the retried write should succeed: status=%d err=%v", response.StatusCode, response.Err)
			}
			if got := bank.posts.Load(); got != 2 {
				t.Fatalf("expected the transport to replay once (2 POSTs), got %d", got)
			}
			keys, bodies, _, _ := bank.snapshot()
			if len(keys) != 2 || keys[0] != "money-key-0001" || keys[1] != "money-key-0001" {
				t.Fatalf("the replay did not reuse the key: %v", keys)
			}
			if len(bodies) != 2 || bodies[0] != tc.wantBody || bodies[1] != tc.wantBody {
				t.Fatalf("the replay did not resend the same body: %v", bodies)
			}
		})
	}
}

// TestUnverifiedDedupSingleSendSuccessPath proves the guard leaves the normal
// path untouched: one request, one response, headers and body intact.
func TestUnverifiedDedupSingleSendSuccessPath(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "false")
	bank := &replayBank{}
	client := newPlainReplayClient(t, bank)
	warmPool(t, client)

	payload := map[string]any{"officeId": 1, "amount": 25}
	response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries", payload,
		map[string]string{"command": "create"}, map[string]string{"Idempotency-Key": "money-key-0002"})

	if response.Err != nil {
		t.Fatalf("a healthy backend must not produce an error: %v", response.Err)
	}
	if response.StatusCode != http.StatusOK {
		t.Fatalf("unexpected status %d", response.StatusCode)
	}
	if string(response.Body) != `{"resourceId":42}` {
		t.Fatalf("the response body changed: %s", response.Body)
	}
	if got := bank.posts.Load(); got != 1 {
		t.Fatalf("the success path sent %d POSTs, want 1", got)
	}
	keys, bodies, _, _ := bank.snapshot()
	if len(keys) != 1 || keys[0] != "money-key-0002" {
		t.Fatalf("the idempotency key was not preserved: %v", keys)
	}
	if len(bodies) != 1 || bodies[0] != `{"amount":25,"officeId":1}` {
		t.Fatalf("the request body changed: %v", bodies)
	}
}

// TestUnverifiedDedupDoesNotReplayMoneyWriteOverHTTP2 exercises the same
// guard over HTTP/2. The stdlib HTTP/2 handler can only fail before writing
// headers by resetting the stream, which is not one of the errors net/http
// replays. The replayable HTTP/2 errors (GOAWAY, REFUSED_STREAM, a peer
// PROTOCOL_ERROR reset) are emitted by the connection or a proxy, not by this
// handler API, so a "processed then replayed" HTTP/2 case cannot be built with
// the stdlib server; this test pins the negotiated protocol, the single
// delivery and the ambiguous result instead.
func TestUnverifiedDedupDoesNotReplayMoneyWriteOverHTTP2(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "")
	bank := &replayBank{loseFirst: true, abortWithPanic: true}
	server := httptest.NewUnstartedServer(bank)
	server.EnableHTTP2 = true
	server.StartTLS()
	t.Cleanup(server.Close)

	transport := &http.Transport{
		TLSClientConfig:   &tls.Config{InsecureSkipVerify: true}, // test certificate
		ForceAttemptHTTP2: true,
	}
	t.Cleanup(transport.CloseIdleConnections)
	client := &FineractClient{
		BaseURL:  server.URL,
		TenantID: "test-tenant",
		Username: "tester",
		Password: "secret",
		HTTP:     &http.Client{Transport: transport, Timeout: 10 * time.Second},
	}
	warmPool(t, client)

	response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries",
		map[string]any{"amount": 10}, nil, map[string]string{"Idempotency-Key": "money-key-0003"})

	if response.Err == nil || response.StatusCode != 0 {
		t.Fatalf("a lost HTTP/2 response must be an unanswered error: status=%d err=%v", response.StatusCode, response.Err)
	}
	if got := bank.posts.Load(); got != 1 {
		t.Fatalf("the money POST reached Fineract %d times over HTTP/2, want 1", got)
	}
	if proto, _ := bank.proto.Load().(string); proto != "HTTP/2.0" {
		t.Fatalf("the test did not negotiate HTTP/2, got %q", proto)
	}
	keys, _, _, _ := bank.snapshot()
	if len(keys) != 1 || keys[0] != "money-key-0003" {
		t.Fatalf("the idempotency key was not preserved: %v", keys)
	}
}

// redirectGateways starts a destination and a redirecting gateway so a test can
// count whether the client followed the Location at all.
func redirectGateways(t *testing.T, status int) (client *FineractClient, targetHits *atomic.Int32) {
	t.Helper()
	hits := &atomic.Int32{}
	target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hits.Add(1)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"resourceId":99}`)
	}))
	t.Cleanup(target.Close)
	gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Location", target.URL+"/redirected")
		w.WriteHeader(status)
	}))
	t.Cleanup(gateway.Close)
	return &FineractClient{
		BaseURL:  gateway.URL,
		TenantID: "test-tenant",
		Username: "tester",
		Password: "secret",
		HTTP:     gateway.Client(),
	}, hits
}

// TestKeyedWriteUnfollowedRedirectIsAnError proves a keyed write never reports
// an unfollowed redirect as a successful response. net/http returns 307/308
// with a nil error when the body cannot be replayed, so the adapter must keep
// the status but surface the transport-level ambiguity.
func TestKeyedWriteUnfollowedRedirectIsAnError(t *testing.T) {
	for _, redirect := range []struct {
		name   string
		status int
	}{
		{"temporary_307", http.StatusTemporaryRedirect},
		{"permanent_308", http.StatusPermanentRedirect},
	} {
		t.Run(redirect.name, func(t *testing.T) {
			t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "false")
			t.Setenv("MIFOSX_IDEMPOTENCY_HEADER", "Idempotency-Key")
			client, targetHits := redirectGateways(t, redirect.status)

			response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries",
				map[string]any{"amount": 10}, nil, map[string]string{"Idempotency-Key": "money-key-0004"})

			if response.Err == nil {
				t.Fatal("an unfollowed redirect must not be reported as success")
			}
			if response.StatusCode != redirect.status {
				t.Fatalf("the redirect status was lost: got %d want %d", response.StatusCode, redirect.status)
			}
			if response.RedirectStatus != 0 {
				t.Fatalf("an unfollowed redirect was reported as followed: %d", response.RedirectStatus)
			}
			if len(response.Body) != 0 {
				t.Fatalf("unexpected redirect body: %s", response.Body)
			}
			if got := targetHits.Load(); got != 0 {
				t.Fatalf("the redirect target received %d requests, want 0", got)
			}
		})
	}
}

// TestKeyedWriteFollowedRedirectIsAnError proves a keyed write is never
// reported as success when net/http follows a 301/302/303 by rewriting the POST
// to a GET. The final 200 belongs to a different request, so the adapter must
// surface the redirect instead of returning the target's body as a result.
func TestKeyedWriteFollowedRedirectIsAnError(t *testing.T) {
	for _, redirect := range []struct {
		name   string
		status int
	}{
		{"moved_permanently_301", http.StatusMovedPermanently},
		{"found_302", http.StatusFound},
		{"see_other_303", http.StatusSeeOther},
	} {
		t.Run(redirect.name, func(t *testing.T) {
			t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "false")
			t.Setenv("MIFOSX_IDEMPOTENCY_HEADER", "Idempotency-Key")

			var mu sync.Mutex
			var targetMethods []string
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				mu.Lock()
				targetMethods = append(targetMethods, r.Method)
				mu.Unlock()
				w.Header().Set("Content-Type", "application/json")
				fmt.Fprint(w, `{"resourceId":99}`)
			}))
			t.Cleanup(target.Close)

			var posts atomic.Int32
			gateway := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				posts.Add(1)
				w.Header().Set("Location", target.URL+"/redirected")
				w.WriteHeader(redirect.status)
			}))
			t.Cleanup(gateway.Close)

			client := &FineractClient{
				BaseURL:  gateway.URL,
				TenantID: "test-tenant",
				Username: "tester",
				Password: "secret",
				HTTP:     gateway.Client(),
			}
			response := client.DoRequestOutcome(context.Background(), http.MethodPost, "journalentries",
				map[string]any{"amount": 10}, nil, map[string]string{"Idempotency-Key": "money-key-0005"})

			if response.Err == nil {
				t.Fatal("a followed redirect must not be reported as success")
			}
			if response.RedirectStatus != redirect.status {
				t.Fatalf("the followed redirect was not detected: got %d want %d", response.RedirectStatus, redirect.status)
			}
			if response.StatusCode != http.StatusOK {
				t.Fatalf("unexpected final status: %d", response.StatusCode)
			}
			if got := posts.Load(); got != 1 {
				t.Fatalf("the original POST was sent %d times, want 1", got)
			}

			mu.Lock()
			methods := append([]string(nil), targetMethods...)
			mu.Unlock()
			if len(methods) != 1 || methods[0] != http.MethodGet {
				t.Fatalf("the redirect was not followed as a GET: %v", methods)
			}
		})
	}
}

// TestReadRedirectIsStillFollowed pins the existing behaviour for reads: the
// redirect protection is scoped to keyed writes.
func TestReadRedirectIsStillFollowed(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "false")
	for _, redirect := range []struct {
		name   string
		status int
	}{
		{"moved_permanently_301", http.StatusMovedPermanently},
		{"found_302", http.StatusFound},
		{"see_other_303", http.StatusSeeOther},
		{"temporary_307", http.StatusTemporaryRedirect},
		{"permanent_308", http.StatusPermanentRedirect},
	} {
		t.Run(redirect.name, func(t *testing.T) {
			client, targetHits := redirectGateways(t, redirect.status)

			response := client.DoRequestOutcome(context.Background(), http.MethodGet, "journalentries", nil, nil, nil)

			if response.Err != nil || response.StatusCode != http.StatusOK {
				t.Fatalf("a read redirect should be followed: status=%d err=%v", response.StatusCode, response.Err)
			}
			if response.RedirectStatus != redirect.status {
				t.Fatalf("the read redirect was not detected: got %d want %d", response.RedirectStatus, redirect.status)
			}
			if got := targetHits.Load(); got != 1 {
				t.Fatalf("the read redirect reached its target %d times, want 1", got)
			}
		})
	}
}
