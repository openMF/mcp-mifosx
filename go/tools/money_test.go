// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/idempotency"
)

// testBank records what the tool layer actually sent, so a test can prove that
// a retry never reached Fineract.
type testBank struct {
	mu      sync.Mutex
	posts   []*http.Request
	gets    []string
	headers []string
	// handle, when set, overrides the default success response.
	handle func(w http.ResponseWriter, r *http.Request)
}

func (b *testBank) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet {
		b.mu.Lock()
		b.gets = append(b.gets, r.URL.String())
		b.mu.Unlock()
	} else {
		b.mu.Lock()
		b.posts = append(b.posts, r)
		b.headers = append(b.headers, r.Header.Get("Idempotency-Key"))
		b.mu.Unlock()
	}
	if b.handle != nil {
		b.handle(w, r)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	fmt.Fprint(w, `{"resourceId": 21, "transactionId": 21}`)
}

func (b *testBank) postCount() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.posts)
}

func (b *testBank) postPaths() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	paths := make([]string, 0, len(b.posts))
	for _, r := range b.posts {
		paths = append(paths, r.URL.Path)
	}
	return paths
}

func (b *testBank) backendKeys() []string {
	b.mu.Lock()
	defer b.mu.Unlock()
	return append([]string(nil), b.headers...)
}

// newMoneyRegistry wires a Registry onto a stub Fineract and an isolated
// on-disk ledger.
func newMoneyRegistry(t *testing.T, bank *testBank) *Registry {
	t.Helper()
	t.Setenv("MCP_IDEMPOTENCY_DIR", t.TempDir())
	t.Setenv("MIFOSX_TENANT_ID", "test-tenant")
	t.Setenv("MCP_IDEMPOTENCY_ACTOR", "test-teller")
	t.Setenv("MCP_IDEMPOTENCY_LOCK_WAIT_SECONDS", "5")
	// testBank records the default header name, so pin it: an inherited custom
	// MIFOSX_IDEMPOTENCY_HEADER would otherwise hide the backend key.
	t.Setenv("MIFOSX_IDEMPOTENCY_HEADER", "Idempotency-Key")
	// The default ledger is process-wide; reset it so each test reads its own
	// temporary directory.
	idempotency.ResetDefaultLedger()
	t.Cleanup(idempotency.ResetDefaultLedger)

	gateway := httptest.NewServer(bank)
	t.Cleanup(gateway.Close)
	client := &adapter.FineractClient{
		BaseURL:  gateway.URL,
		TenantID: "test-tenant",
		Username: "tester",
		Password: "secret",
		HTTP:     gateway.Client(),
	}
	return &Registry{Fineract: client}
}

// toolDef finds one catalogue entry by name, so the tests exercise the same
// definitions the server registers.
func toolDef(t *testing.T, defs []BaseToolDef, name string) BaseToolDef {
	t.Helper()
	for _, def := range defs {
		if def.Name == name {
			if def.MoneyMove == nil {
				t.Fatalf("%s is not registered as a money move", name)
			}
			return def
		}
	}
	t.Fatalf("%s is not in the catalogue", name)
	return BaseToolDef{}
}

func resultText(t *testing.T, result *mcp.CallToolResult) string {
	t.Helper()
	if result == nil || len(result.Content) == 0 {
		t.Fatal("tool returned no content")
	}
	text, ok := result.Content[0].(mcp.TextContent)
	if !ok {
		t.Fatalf("unexpected content type %T", result.Content[0])
	}
	return text.Text
}

func repaymentArgs(amount float64, key string) map[string]any {
	return map[string]any{
		"loanId":            8,
		"transactionAmount": amount,
		"transactionDate":   "2026-11-17",
		"idempotencyKey":    key,
	}
}

func TestLoanRepaymentSucceedsAndSendsBackendKey(t *testing.T) {
	bank := &testBank{}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if result.IsError {
		t.Fatalf("repayment failed: %s", resultText(t, result))
	}
	text := resultText(t, result)
	if !strings.Contains(text, "SUCCESS: Executed make_loan_repayment.") {
		t.Fatalf("unexpected success text: %s", text)
	}
	if !strings.Contains(text, `"resourceId":21`) {
		t.Fatalf("result body was not echoed: %s", text)
	}
	if bank.postCount() != 1 {
		t.Fatalf("expected exactly one POST, got %d", bank.postCount())
	}
	if !strings.HasSuffix(bank.postPaths()[0], "/loans/8/transactions") {
		t.Fatalf("unexpected endpoint: %s", bank.postPaths()[0])
	}

	keys := bank.backendKeys()
	if len(keys) != 1 || !regexp.MustCompile(`^mcp-[0-9a-f]{48}$`).MatchString(keys[0]) {
		t.Fatalf("unexpected backend idempotency key: %v", keys)
	}
	if strings.Contains(keys[0], "repayment-key-0001") {
		t.Fatal("the backend key must not expose the client key")
	}
}

func TestDuplicateRepaymentReplaysWithoutSecondPost(t *testing.T) {
	bank := &testBank{}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	first, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("first call: %v", err)
	}
	second, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if second.IsError {
		t.Fatalf("replay failed: %s", resultText(t, second))
	}
	if bank.postCount() != 1 {
		t.Fatalf("the duplicate reached Fineract %d times", bank.postCount())
	}
	if resultText(t, first) != resultText(t, second) {
		t.Fatalf("replay differs from the original:\n%s\n%s", resultText(t, first), resultText(t, second))
	}
}

func TestRepaymentKeyReuseWithDifferentAmountIsRejected(t *testing.T) {
	bank := &testBank{}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	if _, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001")); err != nil {
		t.Fatalf("first call: %v", err)
	}
	result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(999, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("second call: %v", err)
	}
	if !result.IsError {
		t.Fatal("a reused key with a different amount must be rejected")
	}
	body := resultText(t, result)
	if !strings.Contains(body, "idempotency_key_reused") {
		t.Fatalf("unexpected error body: %s", body)
	}
	if !strings.Contains(body, `"correlation_id"`) {
		t.Fatalf("rejection must carry a correlation id: %s", body)
	}
	if bank.postCount() != 1 {
		t.Fatalf("the mismatched payload was executed: %d POSTs", bank.postCount())
	}
}

func TestConcurrentIdenticalRepaymentsExecuteOnce(t *testing.T) {
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		// Hold the claim long enough for the duplicates to queue.
		time.Sleep(60 * time.Millisecond)
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"resourceId": 21}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	var wg sync.WaitGroup
	results := make([]*mcp.CallToolResult, 4)
	errs := make([]error, 4)
	for i := range results {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			results[index], errs[index] = registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
		}(i)
	}
	wg.Wait()

	for index := range results {
		if errs[index] != nil {
			t.Fatalf("call %d failed: %v", index, errs[index])
		}
		if results[index].IsError {
			t.Fatalf("call %d reported an error: %s", index, resultText(t, results[index]))
		}
	}
	if bank.postCount() != 1 {
		t.Fatalf("concurrent duplicates executed %d times", bank.postCount())
	}
}

func TestTimedOutResponseIsReconciledOnTheNextCall(t *testing.T) {
	var posted int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			atomic.StoreInt32(&posted, 1)
			// The transaction is applied, but the answer arrives far too late
			// for the caller: this is the dangerous timeout case.
			time.Sleep(600 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"resourceId": 21}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		transactions := `{"id": 10, "amount": 500.0, "reversed": false, "type": {"code": "loanTransactionType.repayment", "value": "Repayment"}}`
		if atomic.LoadInt32(&posted) == 1 {
			marker := idempotency.BackendKey(idempotency.DefaultScope(), "loan.repayment", "repayment-key-0001")
			transactions += fmt.Sprintf(`, {"id": 21, "amount": 150.0, "reversed": false, "externalId": %q, "type": {"code": "loanTransactionType.repayment", "value": "Repayment"}}`, marker)
		}
		fmt.Fprintf(w, `{"id": 8, "transactions": [%s]}`, transactions)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if _, err := registry.executeToolDef(ctx, def, repaymentArgs(150, "repayment-key-0001")); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected the timed-out call to surface the deadline, got %v", err)
	}
	if bank.postCount() != 1 {
		t.Fatalf("the timed-out call posted %d times", bank.postCount())
	}

	// The retry must not repeat the payment: it reconciles the earlier attempt.
	result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("retry: %v", err)
	}
	if result.IsError {
		t.Fatalf("the earlier outcome should be reconciled: %s", resultText(t, result))
	}
	text := resultText(t, result)
	if !strings.Contains(text, `"reconciled":true`) || !strings.Contains(text, `"transactionId":21`) {
		t.Fatalf("the outcome was not reconciled from Fineract: %s", text)
	}
	if bank.postCount() != 1 {
		t.Fatalf("the retry repeated the payment (%d POSTs)", bank.postCount())
	}

	// The confirmed outcome is durable: another call replays it.
	replay, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("replay: %v", err)
	}
	if bank.postCount() != 1 {
		t.Fatalf("the replay reached Fineract: %d POSTs", bank.postCount())
	}
	if resultText(t, replay) != text {
		t.Fatalf("replay differs: %s", resultText(t, replay))
	}
}

func TestUnconfirmedOutcomeStaysUnknownAndIsNotRepeated(t *testing.T) {
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprint(w, `{"defaultUserMessage": "Internal Server Error"}`)
			return
		}
		// Reconciliation itself is unavailable, so nothing can be proven.
		w.WriteHeader(http.StatusInternalServerError)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !result.IsError {
		t.Fatal("an unconfirmed outcome must be reported as an error")
	}
	body := resultText(t, result)
	if !strings.Contains(body, "reconciliation_required") || !strings.Contains(body, `"idempotency_state":"UNKNOWN"`) {
		t.Fatalf("unexpected body: %s", body)
	}
	if strings.Contains(body, `"safe_to_retry":true`) {
		t.Fatalf("an unknown outcome must not be marked retryable: %s", body)
	}

	retry, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("retry: %v", err)
	}
	if !retry.IsError {
		t.Fatal("a retry with an unresolved outcome must not succeed")
	}
	if bank.postCount() != 1 {
		t.Fatalf("the ambiguous operation was repeated (%d POSTs)", bank.postCount())
	}
}

func TestConfirmedAbsenceAllowsOneControlledRetry(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS", "1")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS", "0")
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
	var succeeding int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"id": 8, "transactions": []}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		if atomic.LoadInt32(&succeeding) == 0 {
			w.WriteHeader(http.StatusBadGateway)
			fmt.Fprint(w, `{"defaultUserMessage": "Gateway timeout"}`)
			return
		}
		fmt.Fprint(w, `{"resourceId": 22}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	first, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !first.IsError || !strings.Contains(resultText(t, first), "safe_retry_permitted") {
		t.Fatalf("a confirmed absence should permit a retry: %s", resultText(t, first))
	}
	if bank.postCount() != 1 {
		t.Fatalf("the first call posted %d times", bank.postCount())
	}

	atomic.StoreInt32(&succeeding, 1)
	second, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("controlled retry: %v", err)
	}
	if second.IsError {
		t.Fatalf("the controlled retry failed: %s", resultText(t, second))
	}
	if bank.postCount() != 2 {
		t.Fatalf("expected exactly one controlled retry, got %d POSTs", bank.postCount())
	}
}

func TestBackendRefusalIsARetryableFailure(t *testing.T) {
	var reject int32 = 1
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if atomic.LoadInt32(&reject) == 1 {
			// A conclusive 4xx means Fineract refused the request.
			w.WriteHeader(http.StatusBadRequest)
			fmt.Fprint(w, `{"defaultUserMessage": "Validation Error: amount is invalid"}`)
			return
		}
		fmt.Fprint(w, `{"resourceId": 23}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	first, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	body := resultText(t, first)
	if !first.IsError || !strings.Contains(body, "operation_failed") || !strings.Contains(body, `"safe_to_retry":true`) {
		t.Fatalf("a refused request should be a safe retry: %s", body)
	}
	if strings.Contains(body, "secret") {
		t.Fatalf("the error body leaked credentials: %s", body)
	}

	atomic.StoreInt32(&reject, 0)
	second, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("retry: %v", err)
	}
	if second.IsError {
		t.Fatalf("the retry should succeed: %s", resultText(t, second))
	}
	if bank.postCount() != 2 {
		t.Fatalf("expected the retry to execute, got %d POSTs", bank.postCount())
	}
}

func TestCancelledContextDoesNotExecute(t *testing.T) {
	bank := &testBank{}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	_, err := registry.executeToolDef(ctx, def, repaymentArgs(150, "repayment-key-0001"))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	if bank.postCount() != 0 {
		t.Fatalf("a cancelled call reached Fineract %d times", bank.postCount())
	}
}

func TestLedgerFailurePreventsExecution(t *testing.T) {
	blocker := t.TempDir() + "/file"
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatalf("prepare blocker: %v", err)
	}
	bank := &testBank{}
	gateway := httptest.NewServer(bank)
	t.Cleanup(gateway.Close)
	t.Setenv("MCP_IDEMPOTENCY_DIR", blocker+"/ledger")
	idempotency.ResetDefaultLedger()
	t.Cleanup(idempotency.ResetDefaultLedger)

	registry := &Registry{Fineract: &adapter.FineractClient{
		BaseURL: gateway.URL, TenantID: "test", Username: "u", Password: "p", HTTP: gateway.Client(),
	}}
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !result.IsError || !strings.Contains(resultText(t, result), "ledger_unavailable") {
		t.Fatalf("expected ledger_unavailable, got %s", resultText(t, result))
	}
	if bank.postCount() != 0 {
		t.Fatal("the operation ran even though its claim could not be recorded")
	}
}

func TestJournalEntryWithoutReadEndpointStaysUnknown(t *testing.T) {
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `{"defaultUserMessage": "Internal Server Error"}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetAccountingToolDefs(), "create_journal_entry")

	args := map[string]any{
		"officeId": 1, "transactionDate": "2026-11-17", "currencyCode": "USD",
		"credits": `[{"glAccountId":2,"amount":10}]`, "debits": `[{"glAccountId":3,"amount":10}]`,
		"idempotencyKey": "journal-key-0001",
	}
	result, err := registry.executeToolDef(context.Background(), def, args)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !result.IsError || !strings.Contains(resultText(t, result), "reconciliation_required") {
		t.Fatalf("an unreconcilable outcome must stay UNKNOWN: %s", resultText(t, result))
	}
	if bank.postCount() != 1 {
		t.Fatalf("the entry was reposted: %d POSTs", bank.postCount())
	}
}

// TestJournalEntryLostResponseIsNotReplayedAndStaysUnknown exercises the whole
// path: a money POST carries the backend key over a reused connection, the
// backend applies it and loses the response, and the transport must not resend
// it while backend deduplication is unverified. The ledger must then record
// UNKNOWN instead of claiming the entry succeeded.
func TestJournalEntryLostResponseIsNotReplayedAndStaysUnknown(t *testing.T) {
	// The scenario depends on the transport guard: verified backend dedup would
	// deliberately permit the replay this test forbids, and a custom header name
	// would hide the key from the fixture.
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "false")
	t.Setenv("MIFOSX_IDEMPOTENCY_HEADER", "Idempotency-Key")
	var posts int32
	var mu sync.Mutex
	var getAddr, postAddr string
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet {
			mu.Lock()
			getAddr = r.RemoteAddr
			mu.Unlock()
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{}`)
			return
		}
		mu.Lock()
		postAddr = r.RemoteAddr
		mu.Unlock()
		if _, err := io.ReadAll(r.Body); err != nil {
			http.Error(w, "unreadable body", http.StatusBadRequest)
			return
		}
		if atomic.AddInt32(&posts, 1) == 1 {
			// Fineract committed the journal entry, then the connection failed
			// before a status line reached the client.
			conn, _, err := w.(http.Hijacker).Hijack()
			if err != nil {
				panic(err)
			}
			_ = conn.Close()
			return
		}
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"resourceId": 31}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetAccountingToolDefs(), "create_journal_entry")

	// net/http only retries a failed write whose connection has already been
	// used, so warm the pool and prove the POST reused it.
	if _, err := registry.Fineract.DoRequest(http.MethodGet, "journalentries", nil, nil); err != nil {
		t.Fatalf("warm-up read: %v", err)
	}

	args := map[string]any{
		"officeId": 1, "transactionDate": "2026-11-17", "currencyCode": "USD",
		"credits": `[{"glAccountId":2,"amount":10}]`, "debits": `[{"glAccountId":3,"amount":10}]`,
		"idempotencyKey": "journal-replay-key-0001",
	}
	result, err := registry.executeToolDef(context.Background(), def, args)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if !result.IsError {
		t.Fatalf("a lost response must be reported as an error, got: %s", resultText(t, result))
	}
	body := resultText(t, result)
	if !strings.Contains(body, "reconciliation_required") || !strings.Contains(body, `"idempotency_state":"UNKNOWN"`) {
		t.Fatalf("an unreconcilable outcome must stay UNKNOWN: %s", body)
	}
	if strings.Contains(body, `"safe_to_retry":true`) {
		t.Fatalf("a lost money response must not be declared safe to retry: %s", body)
	}
	if got := bank.postCount(); got != 1 {
		t.Fatalf("the lost money POST was replayed (%d POSTs)", got)
	}

	mu.Lock()
	reused := getAddr != "" && postAddr == getAddr
	mu.Unlock()
	if !reused {
		t.Fatalf("the POST did not reuse the warmed connection: get=%q post=%q", getAddr, postAddr)
	}
	keys := bank.backendKeys()
	if len(keys) != 1 || keys[0] == "" {
		t.Fatalf("the one POST did not carry a backend idempotency key: %v", keys)
	}
	want := idempotency.BackendKey(idempotency.DefaultScope(), "journal.entry", "journal-replay-key-0001")
	if keys[0] != want {
		t.Fatalf("unexpected backend key: got %q want %q", keys[0], want)
	}

	record, err := idempotency.DefaultLedger().Read(
		idempotency.KeyHash(idempotency.DefaultScope(), "journal.entry", "journal-replay-key-0001"))
	if err != nil {
		t.Fatalf("read ledger record: %v", err)
	}
	if record == nil {
		t.Fatal("the ambiguous attempt was not recorded in the ledger")
	}
	if record.State != idempotency.StateUnknown {
		t.Fatalf("the ledger marked the ambiguous write %s, want %s", record.State, idempotency.StateUnknown)
	}
	if _, succeeded := record.Result["resourceId"]; succeeded {
		t.Fatalf("the ledger recorded a success result for an unanswered write: %v", record.Result)
	}
}

// TestJournalEntryUnfollowedRedirectStaysUnknown proves that a keyed money POST
// which receives an unfollowed redirect is never treated as success. net/http
// returns 307/308 with a nil error when it cannot rewind the body, and a
// redirect target has not confirmed the write, so the ledger must stay UNKNOWN
// and nothing may be sent to the redirect target.
func TestJournalEntryUnfollowedRedirectStaysUnknown(t *testing.T) {
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

			var targetHits int32
			target := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				atomic.AddInt32(&targetHits, 1)
				w.Header().Set("Content-Type", "application/json")
				fmt.Fprint(w, `{"resourceId": 77}`)
			}))
			t.Cleanup(target.Close)

			bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost {
					http.NotFound(w, r)
					return
				}
				w.Header().Set("Location", target.URL+"/redirected/entries")
				w.WriteHeader(redirect.status)
			}}
			registry := newMoneyRegistry(t, bank)
			def := toolDef(t, GetAccountingToolDefs(), "create_journal_entry")

			key := "journal-redirect-" + redirect.name
			args := map[string]any{
				"officeId": 1, "transactionDate": "2026-11-17", "currencyCode": "USD",
				"credits": `[{"glAccountId":2,"amount":10}]`, "debits": `[{"glAccountId":3,"amount":10}]`,
				"idempotencyKey": key,
			}
			result, err := registry.executeToolDef(context.Background(), def, args)
			if err != nil {
				t.Fatalf("execute: %v", err)
			}
			if !result.IsError {
				t.Fatalf("a redirect must not be reported as success: %s", resultText(t, result))
			}
			body := resultText(t, result)
			if !strings.Contains(body, "reconciliation_required") || !strings.Contains(body, `"idempotency_state":"UNKNOWN"`) {
				t.Fatalf("an unfollowed redirect must stay UNKNOWN: %s", body)
			}
			if strings.Contains(body, `"safe_to_retry":true`) {
				t.Fatalf("a redirect must not be declared safe to retry: %s", body)
			}
			if got := bank.postCount(); got != 1 {
				t.Fatalf("the redirecting POST was sent %d times, want 1", got)
			}
			if got := atomic.LoadInt32(&targetHits); got != 0 {
				t.Fatalf("the redirect target received %d requests, want 0", got)
			}

			record, err := idempotency.DefaultLedger().Read(
				idempotency.KeyHash(idempotency.DefaultScope(), "journal.entry", key))
			if err != nil {
				t.Fatalf("read ledger record: %v", err)
			}
			if record == nil {
				t.Fatal("the redirect attempt was not recorded in the ledger")
			}
			if record.State != idempotency.StateUnknown {
				t.Fatalf("the ledger marked the redirect %s, want %s", record.State, idempotency.StateUnknown)
			}
			if _, succeeded := record.Result["resourceId"]; succeeded {
				t.Fatalf("the ledger recorded a success result for a redirect: %v", record.Result)
			}
		})
	}
}

// TestJournalEntryFollowedRedirectStaysUnknown proves a keyed money write is
// never treated as success when net/http follows a 301/302/303 by rewriting the
// POST to a GET. The final 200 belongs to a different request, so the ledger
// must stay UNKNOWN, reconciliation must be requested, and the write must not
// be retried.
func TestJournalEntryFollowedRedirectStaysUnknown(t *testing.T) {
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
				fmt.Fprint(w, `{"resourceId": 77}`)
			}))
			t.Cleanup(target.Close)

			bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
				if r.Method != http.MethodPost {
					http.NotFound(w, r)
					return
				}
				w.Header().Set("Location", target.URL+"/redirected/entries")
				w.WriteHeader(redirect.status)
			}}
			registry := newMoneyRegistry(t, bank)
			def := toolDef(t, GetAccountingToolDefs(), "create_journal_entry")

			key := "journal-followed-redirect-" + redirect.name
			args := map[string]any{
				"officeId": 1, "transactionDate": "2026-11-17", "currencyCode": "USD",
				"credits": `[{"glAccountId":2,"amount":10}]`, "debits": `[{"glAccountId":3,"amount":10}]`,
				"idempotencyKey": key,
			}
			result, err := registry.executeToolDef(context.Background(), def, args)
			if err != nil {
				t.Fatalf("execute: %v", err)
			}
			if !result.IsError {
				t.Fatalf("a followed redirect must not be reported as success: %s", resultText(t, result))
			}
			body := resultText(t, result)
			if !strings.Contains(body, "reconciliation_required") || !strings.Contains(body, `"idempotency_state":"UNKNOWN"`) {
				t.Fatalf("a followed redirect must stay UNKNOWN: %s", body)
			}
			if strings.Contains(body, `"safe_to_retry":true`) {
				t.Fatalf("a followed redirect must not be declared safe to retry: %s", body)
			}
			if got := bank.postCount(); got != 1 {
				t.Fatalf("the original POST was sent %d times, want 1", got)
			}

			mu.Lock()
			methods := append([]string(nil), targetMethods...)
			mu.Unlock()
			if len(methods) != 1 || methods[0] != http.MethodGet {
				t.Fatalf("the redirect was not followed as a GET: %v", methods)
			}

			record, err := idempotency.DefaultLedger().Read(
				idempotency.KeyHash(idempotency.DefaultScope(), "journal.entry", key))
			if err != nil {
				t.Fatalf("read ledger record: %v", err)
			}
			if record == nil {
				t.Fatal("the redirected attempt was not recorded in the ledger")
			}
			if record.State != idempotency.StateUnknown {
				t.Fatalf("the ledger marked the redirected write %s, want %s", record.State, idempotency.StateUnknown)
			}
			if _, succeeded := record.Result["resourceId"]; succeeded {
				t.Fatalf("the ledger recorded a success result for a redirected write: %v", record.Result)
			}
		})
	}
}

func TestSavingsDepositIsReconciledFromTransactions(t *testing.T) {
	var posted int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			atomic.StoreInt32(&posted, 1)
			time.Sleep(600 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"resourceId": 9}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		existing := `{"id": 4, "amount": 100.0, "reversed": false, "type": {"code": "savingsAccountTransactionType.deposit", "value": "Deposit"}}`
		created := ""
		if atomic.LoadInt32(&posted) == 1 {
			marker := idempotency.BackendKey(idempotency.DefaultScope(), "savings.deposit", "deposit-key-0001")
			created = fmt.Sprintf(`, {"id": 9, "amount": 500.5, "reversed": false, "externalId": %q, "type": {"code": "savingsAccountTransactionType.deposit", "value": "Deposit"}}`, marker)
		}
		fmt.Fprintf(w, `{"id": 7, "transactions": [%s%s]}`, existing, created)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetSavingsToolDefs(), "deposit_savings")

	args := map[string]any{
		"savingsId": 7, "transactionAmount": 500.5, "transactionDate": "2026-11-17",
		"idempotencyKey": "deposit-key-0001",
	}
	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if _, err := registry.executeToolDef(ctx, def, args); err == nil {
		t.Fatal("expected the timed-out deposit to report a deadline")
	}
	if bank.postCount() != 1 {
		t.Fatalf("expected one POST, got %d", bank.postCount())
	}

	result, err := registry.executeToolDef(context.Background(), def, args)
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if result.IsError {
		t.Fatalf("deposit should reconcile: %s", resultText(t, result))
	}
	text := resultText(t, result)
	if !strings.Contains(text, `"reconciled":true`) || !strings.Contains(text, `"transactionId":9`) {
		t.Fatalf("the deposit was not reconciled: %s", text)
	}
	if bank.postCount() != 1 {
		t.Fatalf("the retry repeated the deposit (%d POSTs)", bank.postCount())
	}
}

// ── reconciliation safety (F3) ───────────────────────────────────────

func TestAmountAndTypeAloneDoNotConfirmARepayment(t *testing.T) {
	var posted int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost {
			atomic.StoreInt32(&posted, 1)
			time.Sleep(600 * time.Millisecond)
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, `{"resourceId": 21}`)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		transactions := `{"id": 10, "amount": 500.0, "reversed": false, "type": {"code": "loanTransactionType.repayment", "value": "Repayment"}}`
		if atomic.LoadInt32(&posted) == 1 {
			// Same type and amount, but no unique request marker: another
			// channel could have created this transaction.
			transactions += `, {"id": 21, "amount": 150.0, "reversed": false, "type": {"code": "loanTransactionType.repayment", "value": "Repayment"}}`
		}
		fmt.Fprintf(w, `{"id": 8, "transactions": [%s]}`, transactions)
	}}
	registry := newMoneyRegistry(t, bank)
	def := toolDef(t, GetLoanToolDefs(), "make_loan_repayment")

	ctx, cancel := context.WithTimeout(context.Background(), 150*time.Millisecond)
	defer cancel()
	if _, err := registry.executeToolDef(ctx, def, repaymentArgs(150, "repayment-key-0001")); !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("expected the timed-out call to surface the deadline, got %v", err)
	}

	for attempt := 0; attempt < 2; attempt++ {
		result, err := registry.executeToolDef(context.Background(), def, repaymentArgs(150, "repayment-key-0001"))
		if err != nil {
			t.Fatalf("retry %d: %v", attempt, err)
		}
		if !result.IsError || !strings.Contains(resultText(t, result), "reconciliation_required") {
			t.Fatalf("a lookalike transaction must not confirm the operation: %s", resultText(t, result))
		}
	}
	if bank.postCount() != 1 {
		t.Fatalf("the ambiguous repayment was repeated (%d POSTs)", bank.postCount())
	}
}

func TestReadCollectionRejectsUnusableResponses(t *testing.T) {
	cases := map[string]func(w http.ResponseWriter){
		"missing collection": func(w http.ResponseWriter) {
			fmt.Fprint(w, `{"id": 8}`)
		},
		"not a list": func(w http.ResponseWriter) {
			fmt.Fprint(w, `{"id": 8, "transactions": "nope"}`)
		},
		"malformed entry": func(w http.ResponseWriter) {
			fmt.Fprint(w, `{"id": 8, "transactions": [1]}`)
		},
		"non-2xx": func(w http.ResponseWriter) {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprint(w, `{"defaultUserMessage": "down"}`)
		},
	}
	for name, respond := range cases {
		t.Run(name, func(t *testing.T) {
			bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				respond(w)
			}}
			registry := newMoneyRegistry(t, bank)
			if _, err := readCollection(context.Background(), registry.Fineract, "loans/8?associations=transactions", "transactions"); err == nil {
				t.Fatalf("%s must not be treated as a valid empty collection", name)
			}
		})
	}
}

func TestDisbursalSnapshotFailureIsNotConfirmed(t *testing.T) {
	var gets int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if atomic.AddInt32(&gets, 1) == 1 {
			w.WriteHeader(http.StatusInternalServerError)
			fmt.Fprint(w, `{"defaultUserMessage": "down"}`)
			return
		}
		fmt.Fprint(w, `{"id": 8, "status": {"value": "Active"}}`)
	}}
	registry := newMoneyRegistry(t, bank)
	reconciler := &loanDisbursalReconciler{client: registry.Fineract}
	request := idempotency.Request{
		Operation:      "loan.disburse",
		Resource:       "loans/8",
		Reconciliation: map[string]any{"loan_id": 8},
	}

	snapshot, err := reconciler.Snapshot(context.Background(), request)
	if err == nil && snapshot != nil {
		t.Fatalf("a failed status read must not be stored as a snapshot: %v", snapshot)
	}

	record := &idempotency.Record{Reconcile: map[string]any{"loan_id": 8}, Attempt: 1}
	result, err := reconciler.Reconcile(context.Background(), record)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if result.Status != idempotency.ReconcileUnknown {
		t.Fatalf("a disbursal with no snapshot must stay UNKNOWN, got %s", result.Status)
	}
}

// ── bulk ─────────────────────────────────────────────────────────────

func bulkItems(t *testing.T, requests string) []map[string]any {
	t.Helper()
	var items []map[string]any
	if err := json.Unmarshal([]byte(requests), &items); err != nil {
		t.Fatalf("parse bulk items: %v", err)
	}
	return items
}

func bulkDef(t *testing.T, name string) BulkDef {
	t.Helper()
	for _, def := range bulkToolDefs() {
		if def.Name == name {
			if def.Money == nil {
				t.Fatalf("%s is not a money bulk tool", name)
			}
			return def
		}
	}
	t.Fatalf("%s is not a bulk tool", name)
	return BulkDef{}
}

func TestBulkRepaymentsReplayEveryItem(t *testing.T) {
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		id := strings.TrimSuffix(strings.TrimPrefix(r.URL.Path, "/loans/"), "/transactions")
		fmt.Fprintf(w, `{"resourceId": %s, "transactionId": %s}`, id, id)
	}}
	registry := newMoneyRegistry(t, bank)
	def := bulkDef(t, "bulk_make_repayments")
	items := bulkItems(t, `[
		{"loanId": 1, "amount": 100, "transactionDate": "2026-11-17"},
		{"loanId": 2, "amount": 250, "transactionDate": "2026-11-17"}
	]`)

	first := registry.runBulkMoney(context.Background(), def, items, "batch-key-0001")
	if first.IsError {
		t.Fatalf("bulk run failed: %s", resultText(t, first))
	}
	if bank.postCount() != 2 {
		t.Fatalf("expected two item posts, got %d", bank.postCount())
	}
	firstText := resultText(t, first)
	if strings.Count(firstText, `"status":"succeeded"`) != 2 {
		t.Fatalf("unexpected per-item statuses: %s", firstText)
	}

	second := registry.runBulkMoney(context.Background(), def, items, "batch-key-0001")
	if second.IsError {
		t.Fatalf("bulk replay failed: %s", resultText(t, second))
	}
	if bank.postCount() != 2 {
		t.Fatalf("retrying the batch repeated %d items", bank.postCount()-2)
	}
	if resultText(t, second) != firstText {
		t.Fatalf("the replay is not deterministic:\n%s\n%s", firstText, resultText(t, second))
	}

	// A different parent key is a different batch and must execute.
	third := registry.runBulkMoney(context.Background(), def, items, "batch-key-0002")
	if third.IsError {
		t.Fatalf("new batch failed: %s", resultText(t, third))
	}
	if bank.postCount() != 4 {
		t.Fatalf("a new parent key should execute both items, got %d POSTs", bank.postCount())
	}
}

// ── cross-language contract ──────────────────────────────────────────

// moneyVectors is the subset of the shared fixture this package needs.
type moneyVectors struct {
	Fingerprints []struct {
		Name        string `json:"name"`
		Operation   string `json:"operation"`
		Fingerprint string `json:"fingerprint"`
	} `json:"fingerprints"`
}

// TestToolPayloadsMatchPythonFingerprints proves that one logical money move
// hashes identically here and in python/tools/domains/*.py: same operation
// name, same payload keys, same canonical JSON. Without this, a key claimed by
// one server could be rejected as "reused" by the other.
func TestToolPayloadsMatchPythonFingerprints(t *testing.T) {
	path := filepath.Join("..", "idempotency", "testdata", "idempotency_vectors.json")
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read shared vectors: %v", err)
	}
	var vectors moneyVectors
	if err := json.Unmarshal(data, &vectors); err != nil {
		t.Fatalf("parse shared vectors: %v", err)
	}
	expected := map[string]string{}
	for _, vector := range vectors.Fingerprints {
		expected[vector.Name] = vector.Operation + "|" + vector.Fingerprint
	}

	cases := []struct {
		vector string
		defs   []BaseToolDef
		tool   string
		args   map[string]any
	}{
		{
			vector: "money_loan_repayment", defs: GetLoanToolDefs(), tool: "make_loan_repayment",
			args: map[string]any{"loanId": 8, "transactionAmount": 150, "paymentTypeId": 4},
		},
		{
			vector: "money_loan_waive_interest", defs: GetLoanToolDefs(), tool: "waive_interest",
			args: map[string]any{"loanId": 12, "transactionAmount": 50},
		},
		{
			vector: "money_loan_charge", defs: GetLoanToolDefs(), tool: "apply_late_fee",
			args: map[string]any{"loanId": 8, "chargeId": 2, "amount": 25},
		},
		{
			vector: "money_savings_deposit", defs: GetSavingsToolDefs(), tool: "deposit_savings",
			args: map[string]any{"savingsId": 7, "transactionAmount": 500.5, "paymentTypeId": 3},
		},
		{
			vector: "money_savings_withdrawal", defs: GetSavingsToolDefs(), tool: "withdraw_savings",
			args: map[string]any{"savingsId": 7, "transactionAmount": 100, "paymentTypeId": 3},
		},
		{
			vector: "money_savings_charge", defs: GetSavingsToolDefs(), tool: "apply_savings_charge",
			args: map[string]any{"savingsId": 7, "chargeId": 1, "amount": 15},
		},
		{
			vector: "money_savings_post_interest", defs: GetSavingsToolDefs(), tool: "calculate_and_post_interest",
			args: map[string]any{"savingsId": 7},
		},
		{
			vector: "money_journal_entry", defs: GetAccountingToolDefs(), tool: "create_journal_entry",
			args: map[string]any{
				"officeId": 1, "transactionDate": "10 March 2026", "currencyCode": "USD",
				"credits": `[{"glAccountId": 2, "amount": 10.5}]`,
				"debits":  `[{"glAccountId": 3, "amount": 10.5}]`,
				"comment": "",
			},
		},
	}

	for _, testCase := range cases {
		testCase := testCase
		t.Run(testCase.vector, func(t *testing.T) {
			want, ok := expected[testCase.vector]
			if !ok {
				t.Fatalf("the shared fixture has no vector %q", testCase.vector)
			}
			def := toolDef(t, testCase.defs, testCase.tool)
			fingerprint, err := idempotency.Fingerprint(def.MoneyMove.Operation, def.MoneyMove.Payload(testCase.args))
			if err != nil {
				t.Fatalf("fingerprint: %v", err)
			}
			if got := def.MoneyMove.Operation + "|" + fingerprint; got != want {
				t.Fatalf("payload does not match the Python implementation\n got: %s\nwant: %s", got, want)
			}
		})
	}
}

func TestBulkItemsReportIndividualStatusesAndReconcileIndividually(t *testing.T) {
	var failingItem int32 = 1
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if strings.Contains(r.URL.Path, "/loans/1/") {
			if atomic.LoadInt32(&failingItem) == 1 {
				w.WriteHeader(http.StatusBadRequest)
				fmt.Fprint(w, `{"defaultUserMessage": "Validation Error: charge not allowed"}`)
				return
			}
			fmt.Fprint(w, `{"resourceId": 31}`)
			return
		}
		// Loan 2 fails ambiguously and its charges cannot be read back.
		if r.Method == http.MethodGet {
			w.WriteHeader(http.StatusInternalServerError)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `{"defaultUserMessage": "Internal Server Error"}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := bulkDef(t, "bulk_apply_fees")
	items := bulkItems(t, `[
		{"loanId": 1, "chargeId": 3, "amount": 25},
		{"loanId": 2, "chargeId": 4, "amount": 40}
	]`)

	first := registry.runBulkMoney(context.Background(), def, items, "batch-key-0001")
	if !first.IsError {
		t.Fatal("a batch with failed items must report an error")
	}
	text := resultText(t, first)
	if !strings.Contains(text, `"index":0`) || !strings.Contains(text, `"status":"failed"`) {
		t.Fatalf("item 0 should be failed: %s", text)
	}
	if !strings.Contains(text, `"index":1`) || !strings.Contains(text, `"status":"unknown"`) {
		t.Fatalf("item 1 should be unknown: %s", text)
	}

	atomic.StoreInt32(&failingItem, 0)
	before := bank.postCount()
	second := registry.runBulkMoney(context.Background(), def, items, "batch-key-0001")
	// Item 1 is still unresolved, so the batch keeps reporting an error while
	// exposing both per-item outcomes.
	if !second.IsError {
		t.Fatal("an unresolved item must keep the batch in error")
	}
	if delta := bank.postCount() - before; delta != 1 {
		t.Fatalf("expected one retried item, got %d", delta)
	}
	retried := resultText(t, second)
	if !strings.Contains(retried, `"status":"succeeded"`) || !strings.Contains(retried, `"status":"unknown"`) {
		t.Fatalf("per-item statuses were not preserved: %s", retried)
	}
}

// ── disbursal status vocabulary (F1) ─────────────────────────────────

func TestDisbursalUnusableStatusIsUnknown(t *testing.T) {
	cases := map[string]string{
		"missing status":      `{"id": 8}`,
		"null status":         `{"id": 8, "status": null}`,
		"string status":       `{"id": 8, "status": "Active"}`,
		"numeric status":      `{"id": 8, "status": 7}`,
		"null value":          `{"id": 8, "status": {"value": null}}`,
		"numeric value":       `{"id": 8, "status": {"value": 7}}`,
		"object value":        `{"id": 8, "status": {"value": {"id": 1}}}`,
		"unknown label":       `{"id": 8, "status": {"value": "Teleported"}}`,
		"empty status object": `{"id": 8, "status": {}}`,
		"array payload":       `[]`,
	}
	for name, body := range cases {
		t.Run(name, func(t *testing.T) {
			bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
				w.Header().Set("Content-Type", "application/json")
				fmt.Fprint(w, body)
			}}
			registry := newMoneyRegistry(t, bank)
			reconciler := &loanDisbursalReconciler{client: registry.Fineract}
			request := idempotency.Request{
				Operation:      "loan.disburse",
				Resource:       "loans/8",
				Reconciliation: map[string]any{"loan_id": 8},
			}

			snapshot, _ := reconciler.Snapshot(context.Background(), request)
			if snapshot != nil {
				t.Fatalf("an unusable status must not be stored as a snapshot: %v", snapshot)
			}

			// A usable pre-execution status plus an unusable current read must
			// never become absence evidence for a retry.
			record := &idempotency.Record{
				Attempt:        1,
				AttemptEndedAt: time.Now().UTC().Add(-time.Minute).Format("2006-01-02T15:04:05.000000Z"),
				Reconcile: map[string]any{
					"loan_id":  8,
					"snapshot": map[string]any{"status": "Approved"},
				},
			}
			result, err := reconciler.Reconcile(context.Background(), record)
			if err != nil {
				t.Fatalf("reconcile: %v", err)
			}
			if result.Status != idempotency.ReconcileUnknown {
				t.Fatalf("%s must be UNKNOWN, got %s (%s)", name, result.Status, result.Detail)
			}
		})
	}
}

func TestDisbursalFailedStatusReadIsUnknown(t *testing.T) {
	var gets int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if atomic.AddInt32(&gets, 1) == 1 {
			fmt.Fprint(w, `{"id": 8, "status": {"value": "Approved"}}`)
			return
		}
		w.WriteHeader(http.StatusInternalServerError)
		fmt.Fprint(w, `{"defaultUserMessage": "down"}`)
	}}
	registry := newMoneyRegistry(t, bank)
	reconciler := &loanDisbursalReconciler{client: registry.Fineract}
	record := &idempotency.Record{
		Attempt:        1,
		AttemptEndedAt: time.Now().UTC().Add(-time.Minute).Format("2006-01-02T15:04:05.000000Z"),
		Reconcile: map[string]any{
			"loan_id":  8,
			"snapshot": map[string]any{"status": "Approved"},
		},
	}
	result, err := reconciler.Reconcile(context.Background(), record)
	if err != nil {
		t.Fatalf("reconcile: %v", err)
	}
	if result.Status != idempotency.ReconcileUnknown {
		t.Fatalf("a failed status read must be UNKNOWN, got %s", result.Status)
	}
}

func TestDisbursalUnusableStatusNeverBecomesAbsence(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS", "1")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS", "0")

	var gets int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if atomic.AddInt32(&gets, 1) == 1 {
			// The snapshot is usable...
			fmt.Fprint(w, `{"id": 8, "status": {"value": "Approved"}}`)
			return
		}
		// ...but every later read is unusable.
		fmt.Fprint(w, `{"id": 8}`)
	}}
	registry := newMoneyRegistry(t, bank)
	ledger := idempotency.NewLedger(t.TempDir())
	ledger.LockWait = 5 * time.Second
	options := idempotency.Options{Ledger: ledger, Scope: "tenant:test|actor:test-teller"}
	reconciler := &loanDisbursalReconciler{client: registry.Fineract}
	request := idempotency.Request{
		Operation:      "loan.disburse",
		Resource:       "loans/8",
		Reconciliation: map[string]any{"loan_id": 8},
	}

	var calls int32
	ambiguous := func(_ context.Context, _ string) (idempotency.Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return idempotency.Attempt{Outcome: idempotency.OutcomeAmbiguous, Error: "Read timed out."}, nil
	}
	success := func(_ context.Context, _ string) (idempotency.Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return idempotency.Attempt{Outcome: idempotency.OutcomeSuccess, Result: map[string]any{"resourceId": 8}}, nil
	}

	if _, err := idempotency.Execute(context.Background(), request, "disburse-key-0001", ambiguous, reconciler, options); err == nil {
		t.Fatal("a disbursal whose current status cannot be read must stay UNKNOWN")
	}
	for attempt := 0; attempt < 2; attempt++ {
		if _, err := idempotency.Execute(context.Background(), request, "disburse-key-0001", success, reconciler, options); err == nil {
			t.Fatalf("request %d executed despite an unusable loan status", attempt)
		}
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("the disbursal was executed %d times", calls)
	}
}

func TestDisbursalClosedStatusIsNotAbsence(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS", "1")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS", "0")

	var gets int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if atomic.AddInt32(&gets, 1) == 1 {
			fmt.Fprint(w, `{"id": 8, "status": {"value": "Approved"}}`)
			return
		}
		fmt.Fprint(w, `{"id": 8, "status": {"value": "Closed (obligations met)"}}`)
	}}
	registry := newMoneyRegistry(t, bank)
	ledger := idempotency.NewLedger(t.TempDir())
	ledger.LockWait = 5 * time.Second
	options := idempotency.Options{Ledger: ledger, Scope: "tenant:test|actor:test-teller"}
	reconciler := &loanDisbursalReconciler{client: registry.Fineract}
	request := idempotency.Request{
		Operation:      "loan.disburse",
		Resource:       "loans/8",
		Reconciliation: map[string]any{"loan_id": 8},
	}
	var calls int32
	executor := func(_ context.Context, _ string) (idempotency.Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return idempotency.Attempt{Outcome: idempotency.OutcomeAmbiguous, Error: "Read timed out."}, nil
	}

	if _, err := idempotency.Execute(context.Background(), request, "disburse-key-0001", executor, reconciler, options); err == nil {
		t.Fatal("a status that proves the loan existed must not authorise a retry")
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("the disbursal was executed %d times", calls)
	}
}

func TestDisbursalPreDisbursementAbsenceAllowsControlledRetry(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS", "1")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS", "0")

	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		fmt.Fprint(w, `{"id": 8, "status": {"value": "Approved"}}`)
	}}
	registry := newMoneyRegistry(t, bank)
	ledger := idempotency.NewLedger(t.TempDir())
	ledger.LockWait = 5 * time.Second
	options := idempotency.Options{Ledger: ledger, Scope: "tenant:test|actor:test-teller"}
	reconciler := &loanDisbursalReconciler{client: registry.Fineract}
	request := idempotency.Request{
		Operation:      "loan.disburse",
		Resource:       "loans/8",
		Reconciliation: map[string]any{"loan_id": 8},
	}
	var calls int32
	ambiguous := func(_ context.Context, _ string) (idempotency.Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return idempotency.Attempt{Outcome: idempotency.OutcomeAmbiguous, Error: "Read timed out."}, nil
	}
	success := func(_ context.Context, _ string) (idempotency.Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return idempotency.Attempt{Outcome: idempotency.OutcomeSuccess, Result: map[string]any{"resourceId": 8}}, nil
	}

	first, err := idempotency.Execute(context.Background(), request, "disburse-key-0001", ambiguous, reconciler, options)
	if err != nil {
		t.Fatalf("a pre-disbursement absence should be reported as retryable: %v", err)
	}
	if first["error_code"] != "safe_retry_permitted" || first["idempotency_state"] != idempotency.StateRetryableAbsent {
		t.Fatalf("unexpected absence body: %v", first)
	}
	second, err := idempotency.Execute(context.Background(), request, "disburse-key-0001", success, reconciler, options)
	if err != nil {
		t.Fatalf("controlled retry: %v", err)
	}
	if second["resourceId"] != 8 {
		t.Fatalf("unexpected retry result: %v", second)
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("expected one ambiguous attempt and one retry, got %d executions", calls)
	}
}

// TestDisbursalUnusableStatusNeverPostsTwiceWithDedupVerified proves at the tool
// layer that an unreadable loan status cannot authorise a second backend POST,
// even when the operator has asserted that Fineract deduplicates money POSTs.
func TestDisbursalUnusableStatusNeverPostsTwiceWithDedupVerified(t *testing.T) {
	t.Setenv("MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED", "true")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS", "1")
	t.Setenv("MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS", "0")

	var gets, posts int32
	bank := &testBank{handle: func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		if r.Method == http.MethodPost {
			atomic.AddInt32(&posts, 1)
			// Hold the response past the caller's deadline so the tool cannot
			// tell whether the disbursement happened.
			time.Sleep(800 * time.Millisecond)
			fmt.Fprint(w, `{"resourceId": 8}`)
			return
		}
		if atomic.AddInt32(&gets, 1) == 1 {
			// The pre-execution snapshot is usable...
			fmt.Fprint(w, `{"id": 8, "status": {"value": "Approved"}}`)
			return
		}
		// ...but every reconciliation read is unusable.
		fmt.Fprint(w, `{"id": 8}`)
	}}
	registry := newMoneyRegistry(t, bank)
	def := bulkDef(t, "bulk_disburse_loans")
	items := bulkItems(t, `[{"loanId": 8, "idempotencyKey": "disbursal-key-0001"}]`)

	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()
	first := registry.runBulkMoney(ctx, def, items, "")
	if !first.IsError {
		t.Fatalf("a timed-out disbursal must not report success: %s", resultText(t, first))
	}
	if atomic.LoadInt32(&posts) != 1 {
		t.Fatalf("expected exactly one POST for the first attempt, got %d", posts)
	}

	for attempt := 0; attempt < 2; attempt++ {
		result := registry.runBulkMoney(context.Background(), def, items, "")
		text := resultText(t, result)
		if !result.IsError || !strings.Contains(text, "reconciliation_required") {
			t.Fatalf("request %d must stay unknown, got: %s", attempt, text)
		}
		if !strings.Contains(text, `"status":"unknown"`) {
			t.Fatalf("request %d should report the item as unknown: %s", attempt, text)
		}
	}
	if got := atomic.LoadInt32(&posts); got != 1 {
		t.Fatalf("an unusable loan status authorised %d POSTs despite verified dedup", got)
	}
}
