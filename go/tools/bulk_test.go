// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

// bulkBackend is a scripted Fineract stand-in. It records every request and
// tracks how many are in flight under one mutex, so tests can assert the
// concurrency bound without timing assumptions.
type bulkBackend struct {
	t           *testing.T
	server      *httptest.Server
	handler     func(*bulkBackend, http.ResponseWriter, *http.Request)
	mu          sync.Mutex
	requests    []bulkRequest
	inFlight    int
	maxInFlight int
}

type bulkRequest struct {
	Method string
	Path   string
	Query  string
	Body   string
}

func newBulkBackend(t *testing.T, handler func(*bulkBackend, http.ResponseWriter, *http.Request)) (*bulkBackend, *Registry) {
	t.Helper()
	backend := &bulkBackend{t: t, handler: handler}
	backend.server = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, err := io.ReadAll(r.Body)
		if err != nil {
			http.Error(w, "read failed", http.StatusInternalServerError)
			return
		}
		// Restore the body so handlers can decode it; recording it must not
		// change what the code under test sees.
		r.Body = io.NopCloser(strings.NewReader(string(body)))
		backend.mu.Lock()
		backend.requests = append(backend.requests, bulkRequest{
			Method: r.Method,
			Path:   r.URL.Path,
			Query:  r.URL.RawQuery,
			Body:   string(body),
		})
		backend.inFlight++
		if backend.inFlight > backend.maxInFlight {
			backend.maxInFlight = backend.inFlight
		}
		backend.mu.Unlock()

		defer func() {
			backend.mu.Lock()
			backend.inFlight--
			backend.mu.Unlock()
		}()

		backend.handler(backend, w, r)
	}))
	t.Cleanup(backend.server.Close)

	registry := &Registry{
		Fineract: &adapter.FineractClient{
			BaseURL: backend.server.URL,
			HTTP:    backend.server.Client(),
		},
	}
	return backend, registry
}

func (b *bulkBackend) requestCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.requests)
}

func (b *bulkBackend) recorded() []bulkRequest {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]bulkRequest(nil), b.requests...)
}

func (b *bulkBackend) peakInFlight() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.maxInFlight
}

func (b *bulkBackend) inFlightNow() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.inFlight
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func bulkToolHandler(t *testing.T, registry *Registry, name string) func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error) {
	t.Helper()
	defs := append(registry.GetBulkToolDefs(), registry.GetNativeBulkToolDefs()...)
	for _, def := range defs {
		if def.Name == name {
			return def.Handler
		}
	}
	t.Fatalf("bulk tool %q is not registered", name)
	return nil
}

func bulkRequestArgs(t *testing.T, payload any) map[string]any {
	t.Helper()
	encoded, err := json.Marshal(payload)
	if err != nil {
		t.Fatalf("marshal bulk payload: %v", err)
	}
	return map[string]any{"requests": string(encoded)}
}

// invokeBulk runs a handler and requires it to return a result, not an error.
func invokeBulk(t *testing.T, ctx context.Context, handler func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error), args map[string]any) *mcp.CallToolResult {
	t.Helper()
	result, err := handler(ctx, mcp.CallToolRequest{Params: mcp.CallToolParams{Name: "bulk", Arguments: args}})
	if err != nil {
		t.Fatalf("bulk handler returned an error: %v", err)
	}
	if result == nil {
		t.Fatal("bulk handler returned a nil result")
	}
	return result
}

// closeBarrier releases a test barrier exactly once, either explicitly or when
// the test ends. Cleanup runs even after t.Fatal, so handlers blocked on the
// barrier cannot keep httptest.Server.Close waiting.
func closeBarrier(t *testing.T, barrier chan struct{}) func() {
	t.Helper()
	var once sync.Once
	release := func() { once.Do(func() { close(barrier) }) }
	t.Cleanup(release)
	return release
}

func decodeBulkReport(t *testing.T, result *mcp.CallToolResult) bulkReport {
	t.Helper()
	if len(result.Content) != 1 {
		t.Fatalf("expected exactly one content block, got %d", len(result.Content))
	}
	text, ok := result.Content[0].(mcp.TextContent)
	if !ok {
		t.Fatalf("expected text content, got %T", result.Content[0])
	}
	var report bulkReport
	if err := json.Unmarshal([]byte(text.Text), &report); err != nil {
		t.Fatalf("bulk result is not a JSON report: %v\n%s", err, text.Text)
	}
	assertBulkReportConsistent(t, report)
	return report
}

// assertBulkReportConsistent enforces the contract shared by every bulk
// response: one result slot per input, in input order, with exactly one known
// outcome per index and counts that add up.
func assertBulkReportConsistent(t *testing.T, report bulkReport) {
	t.Helper()
	if len(report.Results) != report.Total {
		t.Fatalf("report has %d result slots for %d inputs", len(report.Results), report.Total)
	}
	if report.Succeeded+report.Failed+report.Cancelled != report.Total {
		t.Fatalf("counts do not add up to total: %+v", report)
	}
	seen := make(map[int]bool, report.Total)
	for position, item := range report.Results {
		if item.Index != position {
			t.Fatalf("result slot %d carries index %d", position, item.Index)
		}
		if seen[item.Index] {
			t.Fatalf("index %d has more than one outcome", item.Index)
		}
		seen[item.Index] = true
		switch item.Status {
		case bulkItemSuccess:
			if item.Error != "" {
				t.Fatalf("successful index %d carries error %q", item.Index, item.Error)
			}
		case bulkItemError, bulkItemCancelled:
			if item.Error == "" {
				t.Fatalf("%s index %d has no error detail", item.Status, item.Index)
			}
		default:
			t.Fatalf("index %d has unknown status %q", item.Index, item.Status)
		}
	}
}

func resultID(t *testing.T, value any) string {
	t.Helper()
	return fmt.Sprintf("%v", value)
}

func TestBulkAllItemsSucceed(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 7}, {"loanId": 8}, {"loanId": 9},
	}))
	report := decodeBulkReport(t, result)

	if result.IsError {
		t.Fatalf("a batch that fully succeeded was reported as an error")
	}
	if report.Status != bulkStatusSuccess || report.Total != 3 || report.Succeeded != 3 || report.Failed != 0 || report.Cancelled != 0 {
		t.Fatalf("unexpected summary: %+v", report)
	}
	for i, want := range []string{"7", "8", "9"} {
		item := report.Results[i]
		if item.Index != i {
			t.Errorf("result %d carries index %d", i, item.Index)
		}
		if got := resultID(t, item.ID); got != want {
			t.Errorf("result %d identifies %s, want %s", i, got, want)
		}
		if item.Status != bulkItemSuccess || item.HTTPStatus != 0 || item.Error != "" {
			t.Errorf("result %d is not a clean success: %+v", i, item)
		}
		payload, ok := item.Result.(map[string]any)
		if !ok || payload["path"] != "/loans/"+want {
			t.Errorf("result %d lost its payload: %#v", i, item.Result)
		}
	}
	if backend.requestCount() != 3 {
		t.Errorf("expected 3 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkPartialFailureKeepsSuccesses(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		if strings.HasSuffix(r.URL.Path, "/2") {
			writeJSON(w, http.StatusBadRequest, map[string]any{"developerMessage": "amount exceeds balance"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 1}, {"loanId": 2}, {"loanId": 3},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError {
		t.Fatal("a partial failure must be reported as an MCP error")
	}
	if report.Status != bulkStatusPartial || report.Succeeded != 2 || report.Failed != 1 || report.Cancelled != 0 {
		t.Fatalf("unexpected summary: %+v", report)
	}
	failed := report.Results[1]
	if failed.Status != bulkItemError || failed.HTTPStatus != http.StatusBadRequest || failed.Error != "API Error 400" {
		t.Fatalf("failed item is not described accurately: %+v", failed)
	}
	detail, ok := failed.Result.(map[string]any)
	if !ok || detail["developerMessage"] != "amount exceeds balance" {
		t.Fatalf("failed item lost Fineract's response: %#v", failed.Result)
	}
	for _, i := range []int{0, 2} {
		if report.Results[i].Status != bulkItemSuccess || report.Results[i].Result == nil {
			t.Fatalf("successful item %d was not preserved: %+v", i, report.Results[i])
		}
	}
	if backend.requestCount() != 3 {
		t.Errorf("expected 3 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkCompleteFailure(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusServiceUnavailable, map[string]any{"error": "down"})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_savings_balances")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"savingsId": 1}, {"savingsId": 2}, {"savingsId": 3},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError {
		t.Fatal("a fully failed batch must be reported as an MCP error")
	}
	if report.Status != bulkStatusFailure || report.Failed != 3 || report.Succeeded != 0 {
		t.Fatalf("unexpected summary: %+v", report)
	}
	if !strings.Contains(report.Message, "all 3 operations failed") {
		t.Errorf("unexpected message: %q", report.Message)
	}
	for i, item := range report.Results {
		if item.Status != bulkItemError || item.HTTPStatus != http.StatusServiceUnavailable {
			t.Errorf("result %d is not a described failure: %+v", i, item)
		}
	}
	if backend.requestCount() != 3 {
		t.Errorf("expected 3 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkEmptyBatchIsRejectedBeforeAnyRequest(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{}))

	if !result.IsError {
		t.Fatal("an empty batch must be rejected")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "at least one operation") {
		t.Fatalf("unexpected error text: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("empty batch reached the backend %d times", backend.requestCount())
	}
}

func TestBulkOversizedBatchIsRejectedBeforeAnyRequest(t *testing.T) {
	t.Setenv(bulkMaxBatchSizeEnv, "2")

	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 1}, {"loanId": 2}, {"loanId": 3},
	}))

	if !result.IsError {
		t.Fatal("an oversized batch must be rejected")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "maximum is 2") {
		t.Fatalf("unexpected error text: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("oversized batch reached the backend %d times", backend.requestCount())
	}
}

func TestBulkBoundsConcurrentRequests(t *testing.T) {
	t.Setenv(bulkConcurrencyEnv, "2")

	release := make(chan struct{})
	arrived := make(chan struct{})
	var arriveOnce sync.Once
	backend, registry := newBulkBackend(t, func(b *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		if b.inFlightNow() >= 2 {
			arriveOnce.Do(func() { close(arrived) })
		}
		<-release
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	// Register the barrier cleanup after the server so it runs before
	// httptest.Server.Close when an assertion fails.
	releaseBarrier := closeBarrier(t, release)
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	payload := make([]map[string]any, 8)
	for i := range payload {
		payload[i] = map[string]any{"loanId": i + 1}
	}

	done := make(chan *mcp.CallToolResult, 1)
	go func() {
		result, err := handler(context.Background(), mcp.CallToolRequest{
			Params: mcp.CallToolParams{Name: "bulk", Arguments: bulkRequestArgs(t, payload)},
		})
		if err != nil || result == nil {
			done <- nil
			return
		}
		done <- result
	}()

	select {
	case <-arrived:
	case <-time.After(10 * time.Second):
		t.Fatal("the pool never reached the configured concurrency")
	}
	releaseBarrier()

	var result *mcp.CallToolResult
	select {
	case result = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("bulk call did not finish")
	}
	if result == nil {
		t.Fatal("bulk call failed")
	}

	report := decodeBulkReport(t, result)
	if report.Succeeded != 8 {
		t.Fatalf("expected 8 successes, got %+v", report)
	}
	if peak := backend.peakInFlight(); peak != 2 {
		t.Fatalf("peak concurrency was %d, want exactly 2", peak)
	}
	if backend.requestCount() != 8 {
		t.Fatalf("expected 8 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkCancellationStopsSchedulingNewWork(t *testing.T) {
	t.Setenv(bulkConcurrencyEnv, "1")

	started := make(chan struct{})
	requestCancelled := make(chan struct{})
	var startOnce, cancelOnce sync.Once
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, _ http.ResponseWriter, r *http.Request) {
		startOnce.Do(func() { close(started) })
		select {
		case <-r.Context().Done():
			cancelOnce.Do(func() { close(requestCancelled) })
		case <-time.After(10 * time.Second):
		}
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	payload := make([]map[string]any, 5)
	for i := range payload {
		payload[i] = map[string]any{"loanId": i + 1}
	}

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	done := make(chan *mcp.CallToolResult, 1)
	go func() {
		result, err := handler(ctx, mcp.CallToolRequest{
			Params: mcp.CallToolParams{Name: "bulk", Arguments: bulkRequestArgs(t, payload)},
		})
		if err != nil || result == nil {
			done <- nil
			return
		}
		done <- result
	}()

	select {
	case <-started:
	case <-time.After(10 * time.Second):
		t.Fatal("no request reached the backend")
	}
	cancel()

	select {
	case <-requestCancelled:
	case <-time.After(10 * time.Second):
		t.Fatal("cancellation never reached the HTTP request")
	}

	var result *mcp.CallToolResult
	select {
	case result = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("bulk call did not return after cancellation")
	}
	if result == nil {
		t.Fatal("bulk call failed")
	}
	if !result.IsError {
		t.Fatal("a cancelled batch must be reported as an MCP error")
	}

	report := decodeBulkReport(t, result)
	if report.Status != bulkStatusCancelled {
		t.Fatalf("unexpected status: %+v", report)
	}
	if report.Cancelled == 0 {
		t.Fatalf("no items were marked cancelled: %+v", report)
	}
	if report.Failed != 0 {
		t.Fatalf("cancellation was reported as failure: %+v", report)
	}
	if backend.requestCount() != 1 {
		t.Fatalf("work was scheduled after cancellation: %d requests", backend.requestCount())
	}
}

func TestBulkCancelledBeforeStartSendsNothing(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	t.Cleanup(cancel)

	result := invokeBulk(t, ctx, handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 1, "transactionAmount": 10},
		{"loanId": 2, "transactionAmount": 20},
		{"loanId": 3, "transactionAmount": 30},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusCancelled {
		t.Fatalf("pre-cancelled batch is not reported as cancelled: %+v", report)
	}
	if report.Cancelled != 3 || report.Succeeded != 0 || report.Failed != 0 {
		t.Fatalf("unexpected summary: %+v", report)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("pre-cancelled batch sent %d requests", backend.requestCount())
	}
}

func TestBulkResultsStayAlignedWithInputOrder(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 9}, {"loanId": 4}, {"loanId": 7},
	}))
	report := decodeBulkReport(t, result)

	want := []string{"9", "4", "7"}
	for i, id := range want {
		if report.Results[i].Index != i {
			t.Errorf("result %d carries index %d", i, report.Results[i].Index)
		}
		if got := resultID(t, report.Results[i].ID); got != id {
			t.Errorf("result %d identifies %s, want %s", i, got, id)
		}
		payload, _ := report.Results[i].Result.(map[string]any)
		if payload["path"] != "/loans/"+id {
			t.Errorf("result %d holds another item's payload: %#v", i, report.Results[i].Result)
		}
	}

	calls := backend.recorded()
	if len(calls) != 3 {
		t.Fatalf("expected 3 backend calls, got %d", len(calls))
	}
	for _, call := range calls {
		if call.Method != http.MethodGet {
			t.Errorf("GET bulk tool used %s", call.Method)
		}
	}
}

func TestBulkWriteBatchRejectsDuplicateIDs(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 7, "transactionAmount": 100},
		{"loanId": 8, "transactionAmount": 50},
		{"loanId": 7, "transactionAmount": 100},
	}))

	if !result.IsError {
		t.Fatal("a duplicate write target must be rejected")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "duplicate") || !strings.Contains(text, "loanId=7") {
		t.Fatalf("unexpected error text: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("duplicate batch sent %d requests", backend.requestCount())
	}
}

func TestBulkReadBatchAllowsRepeatedIDs(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 7}, {"loanId": 7},
	}))
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess {
		t.Fatalf("repeated reads were rejected: %+v", report)
	}
	if backend.requestCount() != 2 {
		t.Fatalf("expected 2 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkWriteWithoutPathTargetAllowsRepeatedBodyIDs(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_create_savings_accounts")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"clientId": 7, "productId": 1},
		{"clientId": 7, "productId": 2},
	}))
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess {
		t.Fatalf("two accounts for one client were rejected as duplicates: %+v", report)
	}
	if backend.requestCount() != 2 {
		t.Fatalf("expected 2 backend calls, got %d", backend.requestCount())
	}
	products := make(map[float64]bool)
	for _, call := range backend.recorded() {
		var body map[string]any
		if err := json.Unmarshal([]byte(call.Body), &body); err != nil {
			t.Fatalf("request body is not JSON: %v", err)
		}
		if body["clientId"] != float64(7) {
			t.Errorf("clientId was dropped from the body: %v", body)
		}
		product, ok := body["productId"].(float64)
		if !ok {
			t.Fatalf("productId is missing from the body: %v", body)
		}
		products[product] = true
	}
	if len(products) != 2 {
		t.Errorf("expected two distinct accounts, got %v", products)
	}
}

func TestBulkMissingIdentifierBecomesItemError(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"transactionAmount": 100},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusFailure {
		t.Fatalf("a batch whose only item cannot be sent must fail: %+v", report)
	}
	item := report.Results[0]
	if item.Status != bulkItemError || !strings.Contains(item.Error, "requires loanId") {
		t.Fatalf("unexpected item: %+v", item)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("identifier-less item was sent %d times", backend.requestCount())
	}
}

func TestBulkWriteBodyKeepsOnlyOperationFields(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 7, "transactionAmount": 100, "note": "cash"},
	}))

	calls := backend.recorded()
	if len(calls) != 1 {
		t.Fatalf("expected one backend call, got %d", len(calls))
	}
	if calls[0].Path != "/loans/7" || calls[0].Method != http.MethodPost {
		t.Fatalf("unexpected request line: %s %s", calls[0].Method, calls[0].Path)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(calls[0].Body), &body); err != nil {
		t.Fatalf("request body is not JSON: %v", err)
	}
	if _, ok := body["loanId"]; ok {
		t.Errorf("identifier leaked into the body: %v", body)
	}
	if body["transactionAmount"] != float64(100) || body["note"] != "cash" {
		t.Errorf("operation fields were lost: %v", body)
	}
}

func TestBulkNativeClientProfiles(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_client_profiles")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"clientIds": []any{3, 5}})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 2 {
		t.Fatalf("unexpected report: %+v", report)
	}
	for i, want := range []string{"3", "5"} {
		if got := resultID(t, report.Results[i].ID); got != want {
			t.Errorf("result %d identifies %s, want %s", i, got, want)
		}
		payload, _ := report.Results[i].Result.(map[string]any)
		if payload["path"] != "/clients/"+want {
			t.Errorf("result %d holds %#v", i, report.Results[i].Result)
		}
	}
	if backend.requestCount() != 2 {
		t.Fatalf("expected 2 backend calls, got %d", backend.requestCount())
	}
}

func TestBulkNativeRepaymentKeepsValidItems(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_make_repayments_native")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"repayments": []any{
		map[string]any{"loanId": 1, "amount": 100},
		map[string]any{"loanId": 2, "amount": "not-a-number"},
		map[string]any{"loanId": 3, "amount": 50},
	}})
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusPartial {
		t.Fatalf("unexpected report: %+v", report)
	}
	if report.Succeeded != 2 || report.Failed != 1 {
		t.Fatalf("unexpected counts: %+v", report)
	}
	invalid := report.Results[1]
	if invalid.Status != bulkItemError || !strings.Contains(invalid.Error, "invalid amount") {
		t.Fatalf("invalid item was not described: %+v", invalid)
	}
	if backend.requestCount() != 2 {
		t.Fatalf("expected 2 backend calls, got %d", backend.requestCount())
	}
	var body map[string]any
	for _, call := range backend.recorded() {
		if call.Path != "/loans/1/transactions" {
			continue
		}
		if err := json.Unmarshal([]byte(call.Body), &body); err != nil {
			t.Fatalf("request body is not JSON: %v", err)
		}
	}
	if body == nil {
		t.Fatalf("the valid repayment was not sent: %+v", backend.recorded())
	}
	if body["transactionAmount"] != "100.00" {
		t.Errorf("amount was not normalised: %v", body)
	}
	if body["dateFormat"] != "yyyy-MM-dd" || body["locale"] != "en" {
		t.Errorf("date metadata is missing: %v", body)
	}
}

func TestBulkNativeRepaymentRejectsMissingLoanID(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_make_repayments_native")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"repayments": []any{
		map[string]any{"amount": 100},
	}})
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusFailure {
		t.Fatalf("a repayment without loanId must fail: %+v", report)
	}
	item := report.Results[0]
	if item.Status != bulkItemError || !strings.Contains(item.Error, "missing loanId") {
		t.Fatalf("unexpected item: %+v", item)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("identifier-less repayment was sent %d times", backend.requestCount())
	}
}

func TestBulkRejectsMalformedRequestsJSON(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_search_clients")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"requests": "{not-json"})

	if !result.IsError {
		t.Fatal("malformed requests JSON must be rejected")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "Failed to parse") {
		t.Fatalf("unexpected error text: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("malformed batch sent %d requests", backend.requestCount())
	}
}

func TestBulkLimitsFallBackToDefaults(t *testing.T) {
	for _, tc := range []struct {
		name  string
		value string
		want  int
	}{
		{name: "unset", value: "", want: defaultBulkConcurrency},
		{name: "malformed", value: "many", want: defaultBulkConcurrency},
		{name: "zero", value: "0", want: defaultBulkConcurrency},
		{name: "negative", value: "-4", want: defaultBulkConcurrency},
		{name: "valid", value: "3", want: 3},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Setenv(bulkConcurrencyEnv, tc.value)
			if got := loadBulkLimits().Concurrency; got != tc.want {
				t.Fatalf("concurrency limit %d, want %d", got, tc.want)
			}
		})
	}

	t.Setenv(bulkMaxBatchSizeEnv, "250")
	if got := loadBulkLimits().MaxBatchSize; got != 250 {
		t.Fatalf("batch limit %d, want 250", got)
	}
}

func TestRunBulkPoolExecutesEveryIndexOnce(t *testing.T) {
	counts := make([]int, 50)
	runBulkPool(context.Background(), len(counts), 7, func(_ context.Context, index int) {
		counts[index]++
	})
	for index, count := range counts {
		if count != 1 {
			t.Fatalf("index %d ran %d times", index, count)
		}
	}
}

func TestRunBulkPoolStopsAfterCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	var mu sync.Mutex
	ran := 0
	runBulkPool(ctx, 10, 1, func(_ context.Context, _ int) {
		mu.Lock()
		ran++
		mu.Unlock()
		cancel()
	})

	mu.Lock()
	defer mu.Unlock()
	if ran != 1 {
		t.Fatalf("pool started %d items after the first cancellation, want 1", ran)
	}
}

func TestBulkDuplicateTargetPolicy(t *testing.T) {
	repeatedLoan := []bulkJob{
		{Index: 0, ID: float64(7), IDKey: "loanId"},
		{Index: 1, ID: float64(7), IDKey: "loanId"},
	}
	if err := rejectDuplicateBulkIDs("bulk_disburse_loans", true, repeatedLoan); err == nil {
		t.Fatal("disbursing one loan twice must still be rejected")
	}
	for _, tool := range []string{"bulk_make_repayments", "bulk_make_repayments_native"} {
		if err := rejectDuplicateBulkIDs(tool, true, repeatedLoan); err != nil {
			t.Fatalf("%s rejected repeated repayments against one loan: %v", tool, err)
		}
	}

	mixedKinds := []bulkJob{
		{Index: 0, ID: float64(7), IDKey: "loanId"},
		{Index: 1, ID: float64(7), IDKey: "clientId"},
	}
	if err := rejectDuplicateBulkIDs("bulk_apply_fees", true, mixedKinds); err != nil {
		t.Fatalf("different resource kinds collided: %v", err)
	}

	mixedTypes := []bulkJob{
		{Index: 0, ID: float64(7), IDKey: "loanId"},
		{Index: 1, ID: "7", IDKey: "loanId"},
	}
	if err := rejectDuplicateBulkIDs("bulk_disburse_loans", true, mixedTypes); err == nil {
		t.Fatal("numeric and numeric-string spellings of one target must collide")
	}

	invalid := []bulkJob{
		{Index: 0, ID: true, IDKey: "loanId", Err: fmt.Errorf("bad")},
		{Index: 1, ID: true, IDKey: "loanId", Err: fmt.Errorf("bad")},
	}
	if err := rejectDuplicateBulkIDs("bulk_disburse_loans", true, invalid); err != nil {
		t.Fatalf("invalid items must not mask their own validation errors: %v", err)
	}
}

func TestBulkGenericRepaymentsAllowRepeatedLoanIDs(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, http.StatusOK, body)
	})
	handler := bulkToolHandler(t, registry, "bulk_make_repayments")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 5, "transactionAmount": 100},
		{"loanId": 5, "transactionAmount": 40},
	}))
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 2 {
		t.Fatalf("repeated repayments against one loan were not both executed: %+v", report)
	}
	for i, want := range []float64{100, 40} {
		if got := resultID(t, report.Results[i].ID); got != "5" {
			t.Errorf("result %d identifies %s, want 5", i, got)
		}
		payload, ok := report.Results[i].Result.(map[string]any)
		if !ok || payload["transactionAmount"] != want {
			t.Errorf("result %d does not match its input: %#v", i, report.Results[i].Result)
		}
	}
	calls := backend.recorded()
	if len(calls) != 2 {
		t.Fatalf("expected 2 backend calls, got %d", len(calls))
	}
	for _, call := range calls {
		if call.Path != "/loans/5/transactions" {
			t.Errorf("unexpected path %s", call.Path)
		}
	}
}

func TestBulkNativeRepaymentsAllowRepeatedLoanIDs(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, http.StatusOK, body)
	})
	handler := bulkToolHandler(t, registry, "bulk_make_repayments_native")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"repayments": []any{
		map[string]any{"loanId": 5, "amount": 100},
		map[string]any{"loanId": 5, "amount": 25},
	}})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 2 {
		t.Fatalf("repeated native repayments against one loan were not both executed: %+v", report)
	}
	for i, want := range []string{"100.00", "25.00"} {
		if got := resultID(t, report.Results[i].ID); got != "5" {
			t.Errorf("result %d identifies %s, want 5", i, got)
		}
		payload, ok := report.Results[i].Result.(map[string]any)
		if !ok || payload["transactionAmount"] != want {
			t.Errorf("result %d does not match its input: %#v", i, report.Results[i].Result)
		}
	}
	calls := backend.recorded()
	if len(calls) != 2 {
		t.Fatalf("expected 2 backend calls, got %d", len(calls))
	}
	for _, call := range calls {
		if call.Path != "/loans/5/transactions" {
			t.Errorf("unexpected path %s", call.Path)
		}
	}
}

func TestBulkNativeIdentifierValidation(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_client_profiles")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"clientIds": []any{
		nil, "", "  ", 0, -3, 2.5, true, []any{1}, map[string]any{},
	}})
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusFailure || report.Failed != 9 || report.Succeeded != 0 {
		t.Fatalf("invalid identifiers were not rejected per item: %+v", report)
	}
	for i, item := range report.Results {
		if item.Status != bulkItemError || item.Error == "" {
			t.Errorf("invalid identifier %d was not described: %+v", i, item)
		}
	}
	if backend.requestCount() != 0 {
		t.Fatalf("invalid identifiers reached the backend %d times", backend.requestCount())
	}
}

func TestBulkNativeIdentifiersAcceptNumericStrings(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_client_profiles")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"clientIds": []any{"7", 8}})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 2 {
		t.Fatalf("numeric identifiers were not normalised: %+v", report)
	}
	paths := make(map[string]bool)
	for _, call := range backend.recorded() {
		paths[call.Path] = true
	}
	for _, want := range []string{"/clients/7", "/clients/8"} {
		if !paths[want] {
			t.Errorf("expected path %s, saw %v", want, paths)
		}
	}
}

func TestBulkNativeRepaymentFieldValidation(t *testing.T) {
	cases := []struct {
		name      string
		repayment map[string]any
		want      string
	}{
		{"nil loanId", map[string]any{"loanId": nil, "amount": 10}, "missing loanId"},
		{"empty loanId", map[string]any{"loanId": "", "amount": 10}, "invalid loanId"},
		{"fractional loanId", map[string]any{"loanId": 1.5, "amount": 10}, "invalid loanId"},
		{"zero loanId", map[string]any{"loanId": 0, "amount": 10}, "invalid loanId"},
		{"unsupported loanId", map[string]any{"loanId": true, "amount": 10}, "invalid loanId"},
		{"malformed amount", map[string]any{"loanId": 1, "amount": "abc"}, "invalid amount"},
		{"zero amount", map[string]any{"loanId": 1, "amount": 0}, "positive"},
		{"negative amount", map[string]any{"loanId": 1, "amount": -1}, "positive"},
		{"malformed date", map[string]any{"loanId": 1, "amount": 10, "transactionDate": "15/11/2026"}, "yyyy-MM-dd"},
		{"non-string date", map[string]any{"loanId": 1, "amount": 10, "transactionDate": 20261115}, "must be a string"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
				writeJSON(w, http.StatusOK, map[string]any{"ok": true})
			})
			handler := bulkToolHandler(t, registry, "bulk_make_repayments_native")

			result := invokeBulk(t, context.Background(), handler, map[string]any{"repayments": []any{tc.repayment}})
			report := decodeBulkReport(t, result)

			if !result.IsError || report.Status != bulkStatusFailure {
				t.Fatalf("invalid repayment was not rejected: %+v", report)
			}
			item := report.Results[0]
			if item.Status != bulkItemError || !strings.Contains(item.Error, tc.want) {
				t.Fatalf("unexpected item error %q, want substring %q", item.Error, tc.want)
			}
			if backend.requestCount() != 0 {
				t.Fatalf("invalid repayment reached the backend %d times", backend.requestCount())
			}
		})
	}
}

func TestBulkNativeRepaymentKeepsExactAmountAndDate(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		var body map[string]any
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		writeJSON(w, http.StatusOK, body)
	})
	handler := bulkToolHandler(t, registry, "bulk_make_repayments_native")

	result := invokeBulk(t, context.Background(), handler, map[string]any{"repayments": []any{
		map[string]any{"loanId": 9, "amount": 123.45, "transactionDate": "2025-01-02"},
	}})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 1 {
		t.Fatalf("valid repayment was rejected: %+v", report)
	}
	payload, ok := report.Results[0].Result.(map[string]any)
	if !ok {
		t.Fatalf("repayment payload is missing: %#v", report.Results[0].Result)
	}
	if payload["transactionAmount"] != "123.45" {
		t.Errorf("amount was altered: %v", payload["transactionAmount"])
	}
	if payload["transactionDate"] != "2025-01-02" {
		t.Errorf("date was not preserved: %v", payload["transactionDate"])
	}
	if backend.requestCount() != 1 {
		t.Fatalf("expected 1 backend call, got %d", backend.requestCount())
	}
}

func TestBulkNativeChargesValidatesIdentifiers(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_apply_charges_to_batch")

	result := invokeBulk(t, context.Background(), handler, map[string]any{
		"clientIds": []any{5, nil, "bad"},
		"chargeId":  float64(3),
	})
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusPartial || report.Succeeded != 1 || report.Failed != 2 {
		t.Fatalf("invalid client ids were not isolated: %+v", report)
	}
	calls := backend.recorded()
	if len(calls) != 1 {
		t.Fatalf("expected 1 backend call, got %d", len(calls))
	}
	if calls[0].Path != "/clients/5/charges" {
		t.Fatalf("unexpected path %s", calls[0].Path)
	}
	var body map[string]any
	if err := json.Unmarshal([]byte(calls[0].Body), &body); err != nil {
		t.Fatalf("charge body is not JSON: %v", err)
	}
	if body["chargeId"] != float64(3) {
		t.Errorf("chargeId was not preserved: %v", body["chargeId"])
	}
}

func TestBulkNativeChargesRejectsInvalidChargeID(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_apply_charges_to_batch")

	result := invokeBulk(t, context.Background(), handler, map[string]any{
		"clientIds": []any{5},
		"chargeId":  nil,
	})

	if !result.IsError {
		t.Fatal("a missing chargeId must reject the request")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "chargeId") {
		t.Fatalf("unexpected error text: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("missing chargeId reached the backend %d times", backend.requestCount())
	}
}

func TestBulkCancellationPreservesCompletedResults(t *testing.T) {
	t.Setenv(bulkConcurrencyEnv, "1")

	firstDone := make(chan struct{})
	secondStarted := make(chan struct{})
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/loans/1":
			writeJSON(w, http.StatusOK, map[string]any{"loanId": 1})
			close(firstDone)
		case "/loans/2":
			close(secondStarted)
			<-r.Context().Done()
		default:
			writeJSON(w, http.StatusInternalServerError, map[string]any{"unexpected": r.URL.Path})
		}
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	payload := make([]map[string]any, 5)
	for i := range payload {
		payload[i] = map[string]any{"loanId": i + 1}
	}

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	done := make(chan *mcp.CallToolResult, 1)
	go func() {
		result, err := handler(ctx, mcp.CallToolRequest{
			Params: mcp.CallToolParams{Name: "bulk", Arguments: bulkRequestArgs(t, payload)},
		})
		if err != nil || result == nil {
			done <- nil
			return
		}
		done <- result
	}()

	select {
	case <-firstDone:
	case <-time.After(10 * time.Second):
		t.Fatal("the first item never completed")
	}
	select {
	case <-secondStarted:
	case <-time.After(10 * time.Second):
		t.Fatal("the second item never started")
	}
	cancel()

	var result *mcp.CallToolResult
	select {
	case result = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("bulk call did not return after cancellation")
	}
	if result == nil {
		t.Fatal("bulk call failed")
	}

	report := decodeBulkReport(t, result)
	if !result.IsError || report.Status != bulkStatusCancelled {
		t.Fatalf("cancelled batch is not reported as cancelled: %+v", report)
	}
	if report.Succeeded != 1 || report.Failed != 0 || report.Cancelled != 4 {
		t.Fatalf("completed and cancelled outcomes are not separated: %+v", report)
	}
	if report.Results[0].Status != bulkItemSuccess || report.Results[0].Result == nil {
		t.Fatalf("completed result was lost: %+v", report.Results[0])
	}
	for i := 1; i < len(report.Results); i++ {
		if report.Results[i].Status != bulkItemCancelled {
			t.Fatalf("result %d is not a cancellation outcome: %+v", i, report.Results[i])
		}
	}
	if backend.requestCount() != 2 {
		t.Fatalf("jobs started after cancellation: %d requests", backend.requestCount())
	}
}

func TestRunBulkPoolStopsSchedulingAtCancellation(t *testing.T) {
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	const concurrency = 3
	const total = 12

	var mu sync.Mutex
	started := make(map[int]int)
	allStarted := make(chan struct{})
	release := make(chan struct{})
	releaseBarrier := closeBarrier(t, release)
	var allOnce sync.Once

	done := make(chan struct{})
	go func() {
		defer close(done)
		runBulkPool(ctx, total, concurrency, func(_ context.Context, index int) {
			mu.Lock()
			started[index]++
			if len(started) == concurrency {
				allOnce.Do(func() { close(allStarted) })
			}
			mu.Unlock()
			<-release
		})
	}()

	select {
	case <-allStarted:
	case <-time.After(10 * time.Second):
		cancel()
		t.Fatal("the pool never filled its worker slots")
	}
	cancel()
	releaseBarrier()

	select {
	case <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("the pool did not drain after cancellation")
	}

	mu.Lock()
	defer mu.Unlock()
	if len(started) != concurrency {
		t.Fatalf("pool started %d indexes after cancellation, want %d", len(started), concurrency)
	}
	for index := 0; index < concurrency; index++ {
		if started[index] != 1 {
			t.Fatalf("index %d ran %d times, want exactly once", index, started[index])
		}
	}
}

func TestBulkIdentifierValidation(t *testing.T) {
	cases := []struct {
		name    string
		value   any
		want    string
		wantErr string
	}{
		{name: "positive int", value: 7, want: "7"},
		{name: "positive float", value: float64(7), want: "7"},
		{name: "numeric string", value: " 7 ", want: "7"},
		{name: "json number", value: json.Number("7"), want: "7"},
		{name: "json integral decimal", value: json.Number("7.0"), want: "7"},
		{name: "json integral decimal trailing zeros", value: json.Number("7.0000000000000000"), want: "7"},
		{name: "json integral exponent", value: json.Number("7e0"), want: "7"},
		{name: "json integral positive exponent", value: json.Number("1e3"), want: "1000"},
		{name: "json fractional decimal", value: json.Number("7.5"), wantErr: "whole number"},
		// 7.0000000000000001 is not an integer, but converting it to float64
		// rounds it to 7. Exact integrality must be checked first.
		{name: "json fractional decimal below float precision", value: json.Number("7.0000000000000001"), wantErr: "whole number"},
		{name: "json fractional exponent", value: json.Number("1E-3"), wantErr: "whole number"},
		{name: "json negative integral decimal", value: json.Number("-7.0"), wantErr: "positive"},
		{name: "json zero decimal", value: json.Number("0.0"), wantErr: "positive"},
		{name: "json fraction syntax", value: json.Number("1/1"), wantErr: "whole number"},
		{name: "json hex syntax", value: json.Number("0x10"), wantErr: "whole number"},
		{name: "json NaN", value: json.Number("NaN"), wantErr: "whole number"},
		{name: "json Inf", value: json.Number("Inf"), wantErr: "whole number"},
		{name: "json trailing dot", value: json.Number("7."), wantErr: "whole number"},
		{name: "json leading dot", value: json.Number(".7"), wantErr: "whole number"},
		{name: "max safe integer", value: int64(9007199254740991), want: "9007199254740991"},
		{name: "max safe float", value: float64(1<<53 - 1), want: "9007199254740991"},
		{name: "max safe integral decimal", value: json.Number("9007199254740991.0"), want: "9007199254740991"},
		{name: "exact large json number", value: json.Number("9007199254740993"), want: "9007199254740993"},
		{name: "exact large numeric string", value: "9007199254740993", want: "9007199254740993"},
		{name: "float above safe integer", value: float64(1 << 53), wantErr: "safe integer"},
		{name: "rounded float identifier", value: 9007199254740993.0, wantErr: "safe integer"},
		{name: "decimal above safe integer", value: json.Number("9007199254740993.0"), wantErr: "safe integer"},
		{name: "decimal just above safe integer", value: json.Number("9007199254740992.0"), wantErr: "safe integer"},
		{name: "nil", value: nil, wantErr: "null"},
		{name: "empty string", value: "", wantErr: "empty"},
		{name: "blank string", value: "   ", wantErr: "empty"},
		{name: "fractional", value: 7.5, wantErr: "whole number"},
		{name: "zero", value: 0, wantErr: "positive"},
		{name: "negative", value: -2, wantErr: "positive"},
		{name: "bool", value: true, wantErr: "unsupported"},
		{name: "map", value: map[string]any{}, wantErr: "unsupported"},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			got, err := bulkIdentifier(tc.value)
			if tc.wantErr != "" {
				if err == nil || !strings.Contains(err.Error(), tc.wantErr) {
					t.Fatalf("error is %v, want substring %q", err, tc.wantErr)
				}
				return
			}
			if err != nil {
				t.Fatalf("unexpected error: %v", err)
			}
			if got != tc.want {
				t.Fatalf("canonical id is %q, want %q", got, tc.want)
			}
		})
	}
}

func TestBulkGenericRejectsMalformedIdentifier(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": "not-an-id"},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusFailure {
		t.Fatalf("malformed identifier was not rejected: %+v", report)
	}
	item := report.Results[0]
	if item.Status != bulkItemError || !strings.Contains(item.Error, "invalid loanId") {
		t.Fatalf("unexpected item: %+v", item)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("malformed identifier reached the backend %d times", backend.requestCount())
	}
}

func TestCloseBarrierIsIdempotent(t *testing.T) {
	barrier := make(chan struct{})
	release := closeBarrier(t, barrier)

	release()
	release()

	select {
	case <-barrier:
	default:
		t.Fatal("barrier was not released")
	}
}

func TestBulkTargetKeyMappingCoversEveryOperation(t *testing.T) {
	want := map[string]string{
		"bulk_search_clients":               "",
		"bulk_get_loan_status":              "loanId",
		"bulk_disburse_loans":               "loanId",
		"bulk_make_repayments":              "loanId",
		"bulk_activate_clients":             "clientId",
		"bulk_get_savings_balances":         "savingsId",
		"bulk_apply_fees":                   "loanId",
		"bulk_close_accounts":               "savingsId",
		"bulk_create_savings_accounts":      "",
		"bulk_approve_and_activate_savings": "savingsId",
		"bulk_deposit_savings":              "savingsId",
	}

	registry := &Registry{}
	defs := registry.GetBulkToolDefs()
	if len(defs) != len(want) {
		t.Fatalf("expected %d bulk operations, got %d", len(want), len(defs))
	}
	for _, def := range defs {
		expected, ok := want[def.Name]
		if !ok {
			t.Fatalf("%s has no expected target mapping", def.Name)
		}
		got, _ := bulkTargetKey(def.EndpointURL)
		if got != expected {
			t.Errorf("%s targets %q, want %q", def.Name, got, expected)
		}
	}
}

func TestBulkTargetIdentifierFollowsOperation(t *testing.T) {
	t.Run("apply fees requires loan id", func(t *testing.T) {
		backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		})
		handler := bulkToolHandler(t, registry, "bulk_apply_fees")

		result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
			{"clientId": 7, "chargeId": 3},
		}))
		report := decodeBulkReport(t, result)

		if !result.IsError || report.Status != bulkStatusFailure {
			t.Fatalf("a client id must not satisfy a loan target: %+v", report)
		}
		item := report.Results[0]
		if item.Status != bulkItemError || !strings.Contains(item.Error, "requires loanId") {
			t.Fatalf("unexpected item: %+v", item)
		}
		if backend.requestCount() != 0 {
			t.Fatalf("wrong identifier reached the backend %d times", backend.requestCount())
		}
	})

	t.Run("apply fees prefers loan id over client id", func(t *testing.T) {
		backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		})
		handler := bulkToolHandler(t, registry, "bulk_apply_fees")

		result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
			{"clientId": 7, "loanId": 9, "chargeId": 3},
		}))
		report := decodeBulkReport(t, result)

		if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 1 {
			t.Fatalf("unexpected report: %+v", report)
		}
		calls := backend.recorded()
		if len(calls) != 1 || calls[0].Path != "/loans/9/charges" {
			t.Fatalf("unexpected request: %+v", calls)
		}
		var body map[string]any
		if err := json.Unmarshal([]byte(calls[0].Body), &body); err != nil {
			t.Fatalf("request body is not JSON: %v", err)
		}
		for _, field := range []string{"loanId", "clientId"} {
			if _, ok := body[field]; ok {
				t.Errorf("identifier %s leaked into the body: %v", field, body)
			}
		}
	})

	t.Run("activate clients prefers client id over loan id", func(t *testing.T) {
		backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		})
		handler := bulkToolHandler(t, registry, "bulk_activate_clients")

		result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
			{"loanId": 9, "clientId": 4},
		}))
		report := decodeBulkReport(t, result)

		if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 1 {
			t.Fatalf("unexpected report: %+v", report)
		}
		calls := backend.recorded()
		if len(calls) != 1 || calls[0].Path != "/clients/4" {
			t.Fatalf("unexpected request: %+v", calls)
		}
	})

	t.Run("savings operation requires savings id", func(t *testing.T) {
		backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
			writeJSON(w, http.StatusOK, map[string]any{"ok": true})
		})
		handler := bulkToolHandler(t, registry, "bulk_close_accounts")

		result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
			{"loanId": 9},
		}))
		report := decodeBulkReport(t, result)

		if !result.IsError || report.Status != bulkStatusFailure {
			t.Fatalf("a loan id must not satisfy a savings target: %+v", report)
		}
		if !strings.Contains(report.Results[0].Error, "requires savingsId") {
			t.Fatalf("unexpected item: %+v", report.Results[0])
		}
		if backend.requestCount() != 0 {
			t.Fatalf("wrong identifier reached the backend %d times", backend.requestCount())
		}
	})
}

func TestBulkDuplicateTargetsResolveToSameEndpoint(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_apply_fees")

	result := invokeBulk(t, context.Background(), handler, bulkRequestArgs(t, []map[string]any{
		{"loanId": 7, "clientId": 1, "chargeId": 3},
		{"loanId": "7", "clientId": 2, "chargeId": 4},
	}))

	if !result.IsError {
		t.Fatal("two payloads targeting the same loan must be rejected")
	}
	text := result.Content[0].(mcp.TextContent).Text
	if !strings.Contains(text, "loanId=7") {
		t.Fatalf("duplicate target was not identified by the loan: %q", text)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("duplicate batch sent %d requests", backend.requestCount())
	}
}

func TestBulkKeepsExactLargeJSONIdentifiers(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_get_loan_status")

	// 9007199254740993 is not representable as a float64 and would silently
	// become 9007199254740992 without lossless number decoding.
	result := invokeBulk(t, context.Background(), handler, map[string]any{
		"requests": `[{"loanId":9007199254740993},{"loanId":9007199254740991}]`,
	})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 2 {
		t.Fatalf("exact large identifiers were rejected: %+v", report)
	}
	paths := make(map[string]bool)
	for _, call := range backend.recorded() {
		paths[call.Path] = true
	}
	for _, want := range []string{"/loans/9007199254740993", "/loans/9007199254740991"} {
		if !paths[want] {
			t.Errorf("expected exact path %s, saw %v", want, paths)
		}
	}
}

func TestBulkApplyFeesRejectsFractionalJSONIdentifier(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_apply_fees")

	// The raw JSON is kept exactly as written because marshalling the value
	// would already round 7.0000000000000001 to 7.
	result := invokeBulk(t, context.Background(), handler, map[string]any{
		"requests": `[{"loanId":7.0000000000000001,"chargeId":3}]`,
	})
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusFailure || report.Failed != 1 || report.Succeeded != 0 {
		t.Fatalf("fractional identifier was not rejected: %+v", report)
	}
	item := report.Results[0]
	if item.Status != bulkItemError || !strings.Contains(item.Error, "invalid loanId") {
		t.Fatalf("validation error was not preserved: %+v", item)
	}
	if backend.requestCount() != 0 {
		t.Fatalf("fractional identifier sent %d requests: %v", backend.requestCount(), backend.recorded())
	}
	for _, call := range backend.recorded() {
		if call.Method == http.MethodPost && call.Path == "/loans/7/charges" {
			t.Fatalf("fractional identifier reached %s %s", call.Method, call.Path)
		}
	}
}

func TestBulkApplyFeesAcceptsIntegralDecimalJSONIdentifier(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"path": r.URL.Path})
	})
	handler := bulkToolHandler(t, registry, "bulk_apply_fees")

	// 7.0 is mathematically integral and must keep working through the exact
	// decimal path.
	result := invokeBulk(t, context.Background(), handler, map[string]any{
		"requests": `[{"loanId":7.0,"chargeId":3}]`,
	})
	report := decodeBulkReport(t, result)

	if result.IsError || report.Status != bulkStatusSuccess || report.Succeeded != 1 {
		t.Fatalf("integral decimal identifier was rejected: %+v", report)
	}
	calls := backend.recorded()
	if len(calls) != 1 {
		t.Fatalf("expected one backend request, got %d: %v", len(calls), calls)
	}
	if calls[0].Method != http.MethodPost || calls[0].Path != "/loans/7/charges" {
		t.Fatalf("unexpected request: %s %s", calls[0].Method, calls[0].Path)
	}
}

func TestBulkCancelledContextPreservesValidationErrors(t *testing.T) {
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, _ *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	t.Cleanup(cancel)

	result := invokeBulk(t, ctx, handler, bulkRequestArgs(t, []map[string]any{
		{"transactionAmount": 10},
		{"loanId": 2, "transactionAmount": 20},
	}))
	report := decodeBulkReport(t, result)

	if !result.IsError || report.Status != bulkStatusCancelled {
		t.Fatalf("pre-cancelled batch is not reported as cancelled: %+v", report)
	}
	if report.Succeeded != 0 || report.Failed != 1 || report.Cancelled != 1 {
		t.Fatalf("validation and cancellation outcomes were mixed up: %+v", report)
	}
	invalid := report.Results[0]
	if invalid.Status != bulkItemError || !strings.Contains(invalid.Error, "requires loanId") {
		t.Fatalf("validation error was replaced by cancellation: %+v", invalid)
	}
	if strings.Contains(invalid.Error, "not started") {
		t.Fatalf("invalid job was reported as not started: %+v", invalid)
	}
	if report.Results[1].Status != bulkItemCancelled {
		t.Fatalf("valid job was not cancelled: %+v", report.Results[1])
	}
	if backend.requestCount() != 0 {
		t.Fatalf("pre-cancelled batch sent %d requests", backend.requestCount())
	}
}

func TestBulkCancellationKeepsValidationErrorDuringExecution(t *testing.T) {
	t.Setenv(bulkConcurrencyEnv, "1")

	firstDone := make(chan struct{})
	secondStarted := make(chan struct{})
	backend, registry := newBulkBackend(t, func(_ *bulkBackend, w http.ResponseWriter, r *http.Request) {
		switch r.URL.Path {
		case "/loans/1":
			writeJSON(w, http.StatusOK, map[string]any{"loanId": 1})
			close(firstDone)
		case "/loans/2":
			close(secondStarted)
			<-r.Context().Done()
		default:
			writeJSON(w, http.StatusInternalServerError, map[string]any{"unexpected": r.URL.Path})
		}
	})
	handler := bulkToolHandler(t, registry, "bulk_disburse_loans")

	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	done := make(chan *mcp.CallToolResult, 1)
	go func() {
		result, err := handler(ctx, mcp.CallToolRequest{
			Params: mcp.CallToolParams{Name: "bulk", Arguments: bulkRequestArgs(t, []map[string]any{
				{"loanId": 1, "transactionAmount": 10},
				{"transactionAmount": 20},
				{"loanId": 2, "transactionAmount": 30},
			})},
		})
		if err != nil || result == nil {
			done <- nil
			return
		}
		done <- result
	}()

	select {
	case <-firstDone:
	case <-time.After(10 * time.Second):
		t.Fatal("the first item never completed")
	}
	select {
	case <-secondStarted:
	case <-time.After(10 * time.Second):
		t.Fatal("the second valid item never started")
	}
	cancel()

	var result *mcp.CallToolResult
	select {
	case result = <-done:
	case <-time.After(10 * time.Second):
		t.Fatal("bulk call did not return after cancellation")
	}
	if result == nil {
		t.Fatal("bulk call failed")
	}

	report := decodeBulkReport(t, result)
	if !result.IsError || report.Status != bulkStatusCancelled {
		t.Fatalf("cancelled batch is not reported as cancelled: %+v", report)
	}
	if report.Succeeded != 1 || report.Failed != 1 || report.Cancelled != 1 {
		t.Fatalf("completed, invalid, and cancelled outcomes were not separated: %+v", report)
	}
	invalid := report.Results[1]
	if invalid.Status != bulkItemError || !strings.Contains(invalid.Error, "requires loanId") {
		t.Fatalf("validation error was replaced during cancellation: %+v", invalid)
	}
	if report.Results[2].Status != bulkItemCancelled {
		t.Fatalf("in-flight valid job was not cancelled: %+v", report.Results[2])
	}
	if backend.requestCount() != 2 {
		t.Fatalf("jobs started after cancellation: %d requests", backend.requestCount())
	}
}
