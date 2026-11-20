// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"context"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testScope = "tenant:test|actor:0000000000000000"

func testRequest(payload map[string]any) Request {
	return Request{
		Operation:      "test.money.move",
		Resource:       "savings:7",
		Payload:        payload,
		Reconciliation: map[string]any{"account_id": 7, "amount": 500},
	}
}

func defaultPayload() map[string]any {
	return map[string]any{"account_id": 7, "amount": 500, "payment_type_id": 1}
}

func opts(ledger *Ledger) Options {
	return Options{Ledger: ledger, Scope: testScope}
}

func successExecutor(calls *int32, resourceID int) Executor {
	return func(_ context.Context, _ string) (Attempt, error) {
		atomic.AddInt32(calls, 1)
		return Attempt{Outcome: OutcomeSuccess, Result: map[string]any{"resourceId": resourceID}}, nil
	}
}

// numberAsFloat normalises a decoded JSON number so a fresh int result and a
// replayed float64 result compare equal.
func numberAsFloat(t *testing.T, value any) float64 {
	t.Helper()
	switch typed := value.(type) {
	case int:
		return float64(typed)
	case int64:
		return float64(typed)
	case float64:
		return typed
	case json.Number:
		parsed, err := typed.Float64()
		if err != nil {
			t.Fatalf("invalid number %q", typed)
		}
		return parsed
	}
	t.Fatalf("value %v is not a number", value)
	return 0
}

type stubReconciler struct {
	snapshot      any
	snapshotErr   error
	result        ReconcileResult
	reconcileErr  error
	snapshotCalls *int32
	reconcileHits *int32
}

func (s *stubReconciler) Snapshot(_ context.Context, _ Request) (any, error) {
	if s.snapshotCalls != nil {
		atomic.AddInt32(s.snapshotCalls, 1)
	}
	return s.snapshot, s.snapshotErr
}

func (s *stubReconciler) Reconcile(_ context.Context, _ *Record) (ReconcileResult, error) {
	if s.reconcileHits != nil {
		atomic.AddInt32(s.reconcileHits, 1)
	}
	return s.result, s.reconcileErr
}

func ambiguousExecutor(calls *int32) Executor {
	return func(_ context.Context, _ string) (Attempt, error) {
		atomic.AddInt32(calls, 1)
		return Attempt{Outcome: OutcomeAmbiguous, Error: "Read timed out."}, nil
	}
}

func TestFirstExecutionSucceedsAndIsPersisted(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 1), nil, opts(ledger))
	if err != nil {
		t.Fatalf("execute: %v", err)
	}
	if result["resourceId"] != 1 {
		t.Fatalf("unexpected result: %v", result)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("expected one backend attempt, got %d", calls)
	}

	record, err := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if err != nil || record == nil {
		t.Fatalf("read record: %v, %v", record, err)
	}
	if record.State != StateSucceeded || record.Attempt != 1 {
		t.Fatalf("unexpected record state: %+v", record)
	}
	want, _ := Fingerprint("test.money.move", defaultPayload())
	if record.Fingerprint != want {
		t.Fatalf("fingerprint mismatch: got %s want %s", record.Fingerprint, want)
	}
}

func TestDuplicateReplaysStoredResult(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	executor := successExecutor(&calls, 1)
	first, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	second, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("the duplicate re-executed the operation (%d calls)", calls)
	}
	if numberAsFloat(t, first["resourceId"]) != numberAsFloat(t, second["resourceId"]) {
		t.Fatalf("replay returned a different result: %v vs %v", first, second)
	}
}

func TestRestartReplaysFromDisk(t *testing.T) {
	directory := t.TempDir()
	first := NewLedger(directory)
	var calls int32
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 11), nil, opts(first)); err != nil {
		t.Fatal(err)
	}

	// A new process has no in-memory state and must replay from the file.
	restarted := NewLedger(directory)
	mustNotRun := func(_ context.Context, _ string) (Attempt, error) {
		t.Fatal("the operation was executed again after a restart")
		return Attempt{}, nil
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", mustNotRun, nil, opts(restarted))
	if err != nil {
		t.Fatalf("replay after restart: %v", err)
	}
	if result["resourceId"] != float64(11) {
		t.Fatalf("unexpected replayed result: %v", result)
	}
}

func TestKeyReuseWithDifferentPayloadIsRejected(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	executor := successExecutor(&calls, 1)
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger)); err != nil {
		t.Fatal(err)
	}
	changed := defaultPayload()
	changed["amount"] = 501
	_, err := Execute(context.Background(), testRequest(changed), "client-key-0001", executor, nil, opts(ledger))
	typed, ok := err.(*Error)
	if !ok || typed.Code != "idempotency_key_reused" {
		t.Fatalf("expected idempotency_key_reused, got %v", err)
	}
	if typed.CorrelationID == "" {
		t.Fatal("the rejection must carry a correlation id")
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("a mismatched payload must not execute: %d calls", calls)
	}
}

func TestKeysAreScopedByOperation(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	executor := successExecutor(&calls, 1)
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger)); err != nil {
		t.Fatal(err)
	}
	other := testRequest(defaultPayload())
	other.Operation = "test.other"
	if _, err := Execute(context.Background(), other, "client-key-0001", executor, nil, opts(ledger)); err != nil {
		t.Fatalf("a different operation is a different key scope: %v", err)
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("expected two independent executions, got %d", calls)
	}
}

func TestInvalidKeyIsRejectedBeforeExecution(t *testing.T) {
	ledger := newTestLedger(t)
	for _, key := range []string{"short", "has space-key", "with|pipe-key", "-leading-dash"} {
		var calls int32
		_, err := Execute(context.Background(), testRequest(defaultPayload()), key, successExecutor(&calls, 1), nil, opts(ledger))
		typed, ok := err.(*Error)
		if !ok || typed.Code != "invalid_idempotency_key" {
			t.Fatalf("key %q: expected invalid_idempotency_key, got %v", key, err)
		}
		if calls != 0 {
			t.Fatalf("key %q: an invalid key must not execute", key)
		}
	}
}

func TestMissingKeyIsAllowedByDefaultAndCanBeStrict(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "", successExecutor(&calls, 1), nil, opts(ledger)); err != nil {
		t.Fatalf("a missing key should be allowed by default: %v", err)
	}
	required := true
	options := opts(ledger)
	options.Required = &required
	_, err := Execute(context.Background(), testRequest(defaultPayload()), "", successExecutor(&calls, 2), nil, options)
	typed, ok := err.(*Error)
	if !ok || typed.Code != "missing_idempotency_key" {
		t.Fatalf("expected missing_idempotency_key, got %v", err)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("a strict missing key must not execute: %d calls", calls)
	}
}

func TestUnprotectedAmbiguousOutcomeIsReportedUnknown(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "", ambiguousExecutor(&calls), nil, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	if result["error_code"] != "outcome_unknown" {
		t.Fatalf("expected outcome_unknown, got %v", result)
	}
	if result["safe_to_retry"] != false {
		t.Fatalf("an unconfirmed outcome must not be marked retryable: %v", result)
	}
}

func TestUnprotectedFailureKeepsLegacyBody(t *testing.T) {
	ledger := newTestLedger(t)
	raw := map[string]any{"error": "Validation Error: bad amount", "status_code": 400}
	executor := func(_ context.Context, _ string) (Attempt, error) {
		return Attempt{Outcome: OutcomeFailure, Error: "Validation Error: bad amount", Raw: raw}, nil
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "", executor, nil, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	if result["status_code"] != 400 || result["error"] != raw["error"] {
		t.Fatalf("the pre-idempotency failure body was not preserved: %v", result)
	}
}

func TestConcurrentDuplicatesExecuteOnce(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	executor := func(_ context.Context, _ string) (Attempt, error) {
		atomic.AddInt32(&calls, 1)
		time.Sleep(100 * time.Millisecond) // hold the claim while the duplicate waits
		return Attempt{Outcome: OutcomeSuccess, Result: map[string]any{"resourceId": 1}}, nil
	}

	var wg sync.WaitGroup
	results := make([]map[string]any, 2)
	errs := make([]error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func(index int) {
			defer wg.Done()
			results[index], errs[index] = Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger))
		}(i)
	}
	wg.Wait()

	for _, err := range errs {
		if err != nil {
			t.Fatalf("concurrent duplicate failed: %v", err)
		}
	}
	if got := atomic.LoadInt32(&calls); got != 1 {
		t.Fatalf("concurrent duplicates executed %d times", got)
	}
	for _, result := range results {
		if numberAsFloat(t, result["resourceId"]) != 1 {
			t.Fatalf("unexpected result: %v", result)
		}
	}
}

func TestInProgressIsReportedWithoutExecuting(t *testing.T) {
	ledger := newTestLedger(t)
	ledger.LockWait = 100 * time.Millisecond
	release, err := ledger.Claim(context.Background(), KeyHash(testScope, "test.money.move", "client-key-0001"), "holder")
	if err != nil {
		t.Fatal(err)
	}
	defer release()

	var calls int32
	_, err = Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 1), nil, opts(ledger))
	typed, ok := err.(*Error)
	if !ok || typed.Code != "operation_in_progress" || typed.State != StateInProgress {
		t.Fatalf("expected operation_in_progress, got %v", err)
	}
	if atomic.LoadInt32(&calls) != 0 {
		t.Fatal("an in-progress duplicate must not execute")
	}
}

func TestLedgerFailurePreventsExecution(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	ledger := NewLedger(filepath.Join(blocker, "ledger"))
	var calls int32
	_, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 1), nil, opts(ledger))
	typed, ok := err.(*Error)
	if !ok || typed.Code != "ledger_unavailable" {
		t.Fatalf("expected ledger_unavailable, got %v", err)
	}
	if atomic.LoadInt32(&calls) != 0 {
		t.Fatal("the operation must not run when its claim cannot be recorded")
	}
}

func TestAmbiguousOutcomeConfirmedByReconciliationIsSucceeded(t *testing.T) {
	ledger := newTestLedger(t)
	var calls, reconcileHits int32
	reconciler := &stubReconciler{
		snapshot:      []string{"id:1"},
		result:        ReconcileResult{Status: ReconcileConfirmed, Result: map[string]any{"resourceId": 99, "reconciled": true}},
		reconcileHits: &reconcileHits,
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	if err != nil {
		t.Fatalf("confirmed reconciliation should succeed: %v", err)
	}
	if result["reconciled"] != true {
		t.Fatalf("unexpected result: %v", result)
	}

	record, err := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if err != nil || record == nil || record.State != StateSucceeded {
		t.Fatalf("record was not persisted as SUCCEEDED: %+v %v", record, err)
	}
	if _, ok := record.Reconcile["snapshot"]; !ok {
		t.Fatalf("snapshot was not persisted: %+v", record.Reconcile)
	}

	// A later retry replays the stored success without touching the backend.
	mustNotRun := func(_ context.Context, _ string) (Attempt, error) {
		t.Fatal("a stored success must not be executed again")
		return Attempt{}, nil
	}
	replayed, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", mustNotRun, reconciler, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	if replayed["resourceId"] != float64(99) {
		t.Fatalf("unexpected replay: %v", replayed)
	}
	if atomic.LoadInt32(&reconcileHits) != 1 {
		t.Fatalf("reconciliation ran %d times for one stored success", reconcileHits)
	}
}

func TestUnconfirmedOutcomeStaysUnknownAndIsNeverRepeated(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileUnknown, Detail: "nothing to see"},
	}
	_, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	typed, ok := err.(*Error)
	if !ok || typed.Code != "reconciliation_required" || typed.State != StateUnknown {
		t.Fatalf("expected reconciliation_required, got %v", err)
	}

	_, err = Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	if _, ok := err.(*Error); !ok {
		t.Fatalf("the retry must keep refusing, got %v", err)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("an unknown outcome was repeated: %d backend attempts", calls)
	}

	record, err := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if err != nil || record == nil || record.State != StateUnknown {
		t.Fatalf("record should stay UNKNOWN: %+v %v", record, err)
	}
}

func TestConfirmedAbsenceWithoutVerifiedDedupStaysUnknown(t *testing.T) {
	t.Setenv(backendDedupEnv, "")
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileAbsent, Detail: "no record"},
	}
	_, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	if err == nil {
		t.Fatal("absence without verified backend dedup must not authorise a retry")
	}
	typed, ok := err.(*Error)
	if !ok || typed.State != StateUnknown {
		t.Fatalf("expected UNKNOWN, got %v", err)
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("the operation was executed %d times", calls)
	}
}

func TestConclusiveAbsenceAllowsAControlledRetry(t *testing.T) {
	t.Setenv(backendDedupEnv, "true")
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileAbsent, Detail: "no record"},
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	if err != nil {
		t.Fatalf("a conclusively absent attempt should return a retryable body: %v", err)
	}
	if result["error_code"] != "safe_retry_permitted" || result["safe_to_retry"] != true {
		t.Fatalf("expected safe_retry_permitted, got %v", result)
	}
	if result["idempotency_state"] != StateRetryableAbsent {
		t.Fatalf("expected RETRYABLE_ABSENT, got %v", result["idempotency_state"])
	}

	// The controlled retry reconciles once more before executing.
	retried, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 7), reconciler, opts(ledger))
	if err != nil {
		t.Fatalf("controlled retry: %v", err)
	}
	if retried["resourceId"] != 7 {
		t.Fatalf("unexpected retry result: %v", retried)
	}
	record, _ := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if record == nil || record.Attempt != 2 {
		t.Fatalf("retry should increment the attempt counter: %+v", record)
	}
	if record.AttemptStartedAt == "" || record.AttemptEndedAt == "" {
		t.Fatalf("attempt timestamps must be persisted: %+v", record)
	}
	if _, present := record.Reconcile["absence_reads"]; present {
		t.Fatalf("a new attempt must reset absence evidence: %+v", record.Reconcile)
	}
}

func TestConclusiveFailureIsRetryableWithTheSameKey(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	rejected := func(_ context.Context, _ string) (Attempt, error) {
		atomic.AddInt32(&calls, 1)
		return Attempt{Outcome: OutcomeFailure, Error: "Validation Error: amount too large"}, nil
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", rejected, nil, opts(ledger))
	if err != nil {
		t.Fatal(err)
	}
	if result["error_code"] != "operation_failed" || result["safe_to_retry"] != true {
		t.Fatalf("expected operation_failed, got %v", result)
	}
	retried, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 3), nil, opts(ledger))
	if err != nil {
		t.Fatalf("retry after a conclusive failure: %v", err)
	}
	if retried["resourceId"] != 3 {
		t.Fatalf("unexpected retry result: %v", retried)
	}
}

func TestContextCancellationPropagatesAndRecordsUnknown(t *testing.T) {
	ledger := newTestLedger(t)
	ctx, cancel := context.WithCancel(context.Background())
	executor := func(ctx context.Context, _ string) (Attempt, error) {
		cancel()
		<-ctx.Done()
		return Attempt{Outcome: OutcomeAmbiguous}, ctx.Err()
	}
	_, err := Execute(ctx, testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger))
	if !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}
	record, readErr := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if readErr != nil || record == nil || record.State != StateUnknown {
		t.Fatalf("a cancelled attempt must be UNKNOWN, not FAILED: %+v %v", record, readErr)
	}
}

func TestScopeSeparatesAuthenticatedActors(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	executor := successExecutor(&calls, 1)
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, opts(ledger)); err != nil {
		t.Fatal(err)
	}
	other := opts(ledger)
	other.Scope = "tenant:test|actor:another0000000"
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", executor, nil, other); err != nil {
		t.Fatal(err)
	}
	if atomic.LoadInt32(&calls) != 2 {
		t.Fatalf("different actors must not share a key scope: %d calls", calls)
	}
}

func TestBrokenSnapshotNeverPermitsAGuess(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshotErr: errors.New("snapshot unavailable"),
		result:      ReconcileResult{Status: ReconcileUnknown, Detail: "no snapshot"},
	}
	_, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger))
	if _, ok := err.(*Error); !ok {
		t.Fatalf("a failed snapshot must leave the outcome UNKNOWN, got %v", err)
	}
	record, _ := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if record == nil || record.State != StateUnknown {
		t.Fatalf("unexpected record: %+v", record)
	}
	if _, ok := record.Reconcile["snapshot"]; ok {
		t.Fatalf("a failed snapshot must not be persisted: %+v", record.Reconcile)
	}
}

// TestCrossLanguageReplay proves a record written by the Python implementation
// (the shared fixture) is recognised and replayed by this one.
func TestCrossLanguageReplay(t *testing.T) {
	fixture := loadVectors(t)
	directory := t.TempDir()
	ledger := NewLedger(directory)
	if err := ledger.EnsureReady(); err != nil {
		t.Fatal(err)
	}

	vector := fixture.Records[0]
	if vector.Record.State != StateSucceeded {
		t.Fatalf("fixture record should be a success, got %s", vector.Record.State)
	}
	encoded, err := json.Marshal(vector.Record)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(ledger.RecordPath(vector.Record.KeyHash), encoded, 0o600); err != nil {
		t.Fatal(err)
	}

	mustNotRun := func(_ context.Context, _ string) (Attempt, error) {
		t.Fatal("a Python-written success must be replayed, not executed")
		return Attempt{}, nil
	}
	options := Options{Ledger: ledger, Scope: vector.Scope}
	request := Request{Operation: vector.Operation, Resource: "loans/8", Payload: vector.Payload}
	result, err := Execute(context.Background(), request, vector.Key, mustNotRun, nil, options)
	if err != nil {
		t.Fatalf("cross-language replay: %v", err)
	}
	if result["transactionId"] != float64(21) {
		t.Fatalf("unexpected replayed result: %v", result)
	}
}

func TestErrorBodyIsJsonSafeAndCarriesCorrelation(t *testing.T) {
	body := ErrReconciliationRequired("corr-9", "two matches").Body()
	if body["error_code"] != "reconciliation_required" {
		t.Fatalf("unexpected body: %v", body)
	}
	if body["correlation_id"] != "corr-9" {
		t.Fatalf("correlation id missing: %v", body)
	}
	details, ok := body["details"].(map[string]any)
	if !ok || details["reason"] != "two matches" {
		t.Fatalf("details missing: %v", body)
	}
	if _, err := json.Marshal(body); err != nil {
		t.Fatalf("error body is not JSON-safe: %v", err)
	}
}

// ── attempt-scoped absence evidence (F1) ─────────────────────────────

func TestRepeatedAbsenceNeverExecutesASecondAttempt(t *testing.T) {
	t.Setenv(backendDedupEnv, "")
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileAbsent, Detail: "no record"},
	}
	for attempt := 0; attempt < 4; attempt++ {
		if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger)); err == nil {
			t.Fatalf("request %d must not execute without verified backend dedup", attempt)
		}
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("empty reads authorised %d backend attempts", calls)
	}
	record, err := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if err != nil || record == nil || record.State != StateUnknown {
		t.Fatalf("record should stay UNKNOWN: %+v %v", record, err)
	}
}

func TestLegacyAbsenceFailureIsReconciledNotRetried(t *testing.T) {
	ledger := newTestLedger(t)
	ledger.LockWait = 500 * time.Millisecond
	ledger.Poll = 5 * time.Millisecond
	scope := testScope
	key := "client-key-0001"
	fingerprint, err := Fingerprint("test.money.move", defaultPayload())
	if err != nil {
		t.Fatal(err)
	}
	write := func(state string) {
		if err := ledger.Write(&Record{
			Version:          LedgerVersion,
			KeyHash:          KeyHash(scope, "test.money.move", key),
			Operation:        "test.money.move",
			Scope:            scope,
			Fingerprint:      fingerprint,
			State:            state,
			Attempt:          1,
			CreatedAt:        nowText(),
			UpdatedAt:        nowText(),
			CorrelationID:    "legacy",
			AttemptStartedAt: nowText(),
			AttemptEndedAt:   nowText(),
			Request:          map[string]any{"resource": "savings/7"},
			Reconcile:        map[string]any{"absence_reads": 2},
			Error: map[string]any{
				"code":      "safe_retry_permitted",
				"message":   "The backend has no record of this operation.",
				"retryable": true,
			},
		}); err != nil {
			t.Fatal(err)
		}
	}

	// A legacy absence-derived FAILED record must reconcile, never retry.
	write(StateFailed)
	var calls int32
	unknown := &stubReconciler{snapshot: []string{}, result: ReconcileResult{Status: ReconcileUnknown, Detail: "still cannot tell"}}
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), key, successExecutor(&calls, 1), unknown, opts(ledger)); err == nil {
		t.Fatal("a legacy absence failure must not be retried blind")
	}
	if atomic.LoadInt32(&calls) != 0 {
		t.Fatalf("the legacy record was executed: %d attempts", calls)
	}

	// A confirmed reconciliation is adopted instead of executing again.
	if err := os.Remove(ledger.RecordPath(KeyHash(scope, "test.money.move", key))); err != nil {
		t.Fatal(err)
	}
	write(StateFailed)
	confirmed := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileConfirmed, Result: map[string]any{"resourceId": 5}},
	}
	result, err := Execute(context.Background(), testRequest(defaultPayload()), key, successExecutor(&calls, 1), confirmed, opts(ledger))
	if err != nil {
		t.Fatalf("legacy reconcile: %v", err)
	}
	if result["resourceId"] != 5 {
		t.Fatalf("unexpected reconciled result: %v", result)
	}
	if atomic.LoadInt32(&calls) != 0 {
		t.Fatalf("the legacy record was executed: %d attempts", calls)
	}
}

func TestAmbiguousOutcomeSurvivesRestart(t *testing.T) {
	ledger := newTestLedger(t)
	var calls int32
	reconciler := &stubReconciler{
		snapshot: []string{},
		result:   ReconcileResult{Status: ReconcileUnknown, Detail: "cannot tell"},
	}
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger)); err == nil {
		t.Fatal("an ambiguous outcome must be reported as unknown")
	}

	// A new process opens the same directory and must reconcile, not repeat.
	restarted := NewLedger(ledger.Dir)
	restarted.LockWait = 500 * time.Millisecond
	restarted.Poll = 5 * time.Millisecond
	if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(restarted)); err == nil {
		t.Fatal("the restarted process must still refuse to repeat")
	}
	if atomic.LoadInt32(&calls) != 1 {
		t.Fatalf("the operation was repeated after a restart: %d attempts", calls)
	}
	record, err := restarted.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
	if err != nil || record == nil {
		t.Fatalf("read record: %v %v", record, err)
	}
	if record.State != StateUnknown || record.AttemptStartedAt == "" || record.AttemptEndedAt == "" {
		t.Fatalf("attempt state was not preserved: %+v", record)
	}
}

// ── invalid reconciliation statuses (F7) ─────────────────────────────

func TestInvalidReconciliationStatusNeverRetries(t *testing.T) {
	cases := map[string]ReconcileStatus{
		"unknown label":       ReconcileStatus("MAYBE"),
		"empty status":        ReconcileStatus(""),
		"wrong case":          ReconcileStatus("confirmed"),
	}
	for name, status := range cases {
		t.Run(name, func(t *testing.T) {
			t.Setenv(backendDedupEnv, "true")
			ledger := newTestLedger(t)
			var calls int32
			reconciler := &stubReconciler{
				snapshot: []string{},
				result:   ReconcileResult{Status: status, Detail: "bogus"},
			}
			if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", ambiguousExecutor(&calls), reconciler, opts(ledger)); err == nil {
				t.Fatal("an unrecognised reconciliation status must fail closed")
			}
			for attempt := 0; attempt < 2; attempt++ {
				if _, err := Execute(context.Background(), testRequest(defaultPayload()), "client-key-0001", successExecutor(&calls, 9), reconciler, opts(ledger)); err == nil {
					t.Fatalf("request %d executed after an unrecognised status", attempt)
				}
			}
			if atomic.LoadInt32(&calls) != 1 {
				t.Fatalf("an unrecognised status authorised %d executions", calls)
			}
			record, err := ledger.Read(KeyHash(testScope, "test.money.move", "client-key-0001"))
			if err != nil || record == nil || record.State != StateUnknown {
				t.Fatalf("record should stay UNKNOWN: %+v %v", record, err)
			}
		})
	}
}

// ── legacy records with unusable persisted fields (F3) ───────────────

func TestLegacyFailedRecordWithUnusableErrorFailsClosed(t *testing.T) {
	cases := map[string]map[string]any{
		"nil error":         nil,
		"no classification": {"message": "boom"},
		"non-string code":   {"code": 7},
		"unrecognised code": {"code": "something_new"},
	}
	for name, failure := range cases {
		t.Run(name, func(t *testing.T) {
			ledger := newTestLedger(t)
			ledger.LockWait = 500 * time.Millisecond
			ledger.Poll = 5 * time.Millisecond
			key := "client-key-0001"
			fingerprint, err := Fingerprint("test.money.move", defaultPayload())
			if err != nil {
				t.Fatal(err)
			}
			if err := ledger.Write(&Record{
				Version:          LedgerVersion,
				KeyHash:          KeyHash(testScope, "test.money.move", key),
				Operation:        "test.money.move",
				Scope:            testScope,
				Fingerprint:      fingerprint,
				State:            StateFailed,
				Attempt:          1,
				CreatedAt:        nowText(),
				UpdatedAt:        nowText(),
				CorrelationID:    "legacy",
				AttemptStartedAt: nowText(),
				AttemptEndedAt:   nowText(),
				Request:          map[string]any{"resource": "savings/7"},
				Reconcile:        map[string]any{"absence_reads": 2},
				Error:            failure,
			}); err != nil {
				t.Fatal(err)
			}

			var calls int32
			_, err = Execute(context.Background(), testRequest(defaultPayload()), key, successExecutor(&calls, 1), nil, opts(ledger))
			if err == nil {
				t.Fatal("a corrupt legacy FAILED record must fail closed")
			}
			var typed *Error
			if !errors.As(err, &typed) || typed.Code != "ledger_unavailable" {
				t.Fatalf("expected a typed ledger error, got %v", err)
			}
			if atomic.LoadInt32(&calls) != 0 {
				t.Fatalf("the record was executed %d times", calls)
			}
		})
	}
}
