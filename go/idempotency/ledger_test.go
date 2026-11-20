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
	"testing"
	"time"
)

func newTestLedger(t *testing.T) *Ledger {
	t.Helper()
	ledger := NewLedger(t.TempDir())
	ledger.LockWait = 500 * time.Millisecond
	ledger.Poll = 5 * time.Millisecond
	return ledger
}

func sampleRecord(keyHash string) *Record {
	return &Record{
		Version:       LedgerVersion,
		KeyHash:       keyHash,
		Operation:     "loan.repayment",
		Scope:         "tenant:test|actor:abc",
		Fingerprint:   "f" + keyHash[:8],
		State:         StateInProgress,
		Attempt:       1,
		CreatedAt:     nowText(),
		UpdatedAt:     nowText(),
		CorrelationID: "corr-1",
		OwnerPID:      1,
		OwnerHost:     "host",
		Request:       map[string]any{"resource": "loans/8"},
		Reconcile:     map[string]any{"loan_id": 8},
	}
}

func TestLedgerWriteReadRoundTrip(t *testing.T) {
	ledger := newTestLedger(t)
	record := sampleRecord("0123456789abcdef")
	record.State = StateSucceeded
	record.Result = map[string]any{"resourceId": 21}
	record.BackendRef = map[string]any{"record_id": "id:21"}
	record.Error = nil
	if err := ledger.Write(record); err != nil {
		t.Fatalf("write: %v", err)
	}

	read, err := ledger.Read(record.KeyHash)
	if err != nil {
		t.Fatalf("read: %v", err)
	}
	if read == nil {
		t.Fatal("record was not read back")
	}
	if read.State != StateSucceeded || read.Operation != "loan.repayment" || read.Scope != record.Scope {
		t.Fatalf("unexpected record: %+v", read)
	}
	if read.Result["resourceId"] != float64(21) {
		t.Fatalf("result was not persisted: %+v", read.Result)
	}
	if read.BackendRef["record_id"] != "id:21" {
		t.Fatalf("backend ref was not persisted: %+v", read.BackendRef)
	}
}

func TestLedgerReadMissingReturnsNil(t *testing.T) {
	ledger := newTestLedger(t)
	record, err := ledger.Read("does-not-exist")
	if err != nil || record != nil {
		t.Fatalf("expected nil,nil; got %v,%v", record, err)
	}
}

func TestLedgerReadCorruptRecordFailsClosed(t *testing.T) {
	ledger := newTestLedger(t)
	if err := ledger.EnsureReady(); err != nil {
		t.Fatal(err)
	}
	path := ledger.RecordPath("corrupt")
	if err := os.WriteFile(path, []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ledger.Read("corrupt"); err == nil {
		t.Fatal("a corrupt record must not be ignored")
	} else if typed, ok := err.(*Error); !ok || typed.Code != "ledger_unavailable" {
		t.Fatalf("expected a typed ledger_unavailable error, got %v", err)
	}
}

func TestLedgerRejectsUnknownVersionOrState(t *testing.T) {
	ledger := newTestLedger(t)
	if err := ledger.EnsureReady(); err != nil {
		t.Fatal(err)
	}
	for name, record := range map[string]map[string]any{
		"version": {"version": 99, "key_hash": "k", "operation": "op", "scope": "s",
			"fingerprint": "f", "state": StateSucceeded, "created_at": nowText(), "updated_at": nowText()},
		"state": {"version": 1, "key_hash": "k", "operation": "op", "scope": "s",
			"fingerprint": "f", "state": "DONE", "created_at": nowText(), "updated_at": nowText()},
	} {
		data, _ := json.Marshal(record)
		if err := os.WriteFile(ledger.RecordPath("k"), data, 0o600); err != nil {
			t.Fatal(err)
		}
		if _, err := ledger.Read("k"); err == nil {
			t.Fatalf("%s should be rejected", name)
		}
	}
}

func TestLedgerUnavailableWhenPathIsAFile(t *testing.T) {
	blocker := filepath.Join(t.TempDir(), "file")
	if err := os.WriteFile(blocker, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	ledger := NewLedger(filepath.Join(blocker, "ledger"))
	if err := ledger.EnsureReady(); err == nil {
		t.Fatal("a non-directory ledger path must fail closed")
	}
	ledger.LockWait = 20 * time.Millisecond
	if _, err := ledger.Claim(context.Background(), "key", "corr"); err == nil {
		t.Fatal("claiming an unavailable ledger must fail")
	}
}

func TestClaimIsExclusiveUntilReleased(t *testing.T) {
	ledger := newTestLedger(t)
	release, err := ledger.Claim(context.Background(), "key", "corr-1")
	if err != nil {
		t.Fatalf("first claim: %v", err)
	}

	ledger.LockWait = 100 * time.Millisecond
	if _, err := ledger.Claim(context.Background(), "key", "corr-2"); err == nil {
		t.Fatal("a second claim must not be granted while the first is held")
	} else if typed, ok := err.(*Error); !ok || typed.Code != "operation_in_progress" {
		t.Fatalf("expected operation_in_progress, got %v", err)
	}

	release()
	again, err := ledger.Claim(context.Background(), "key", "corr-3")
	if err != nil {
		t.Fatalf("claim after release: %v", err)
	}
	again()
}

func TestAbandonedLockFileIsReacquiredWithoutDeletion(t *testing.T) {
	ledger := newTestLedger(t)
	if err := ledger.EnsureReady(); err != nil {
		t.Fatal(err)
	}
	stale := map[string]any{
		"claim_token":      "dead-owner",
		"owner_pid":        1,
		"owner_host":       "gone",
		"acquired_at":      "2000-01-01T00:00:00.000000Z",
		"lease_expires_at": "2000-01-01T00:00:00.000000Z",
	}
	encoded, _ := json.Marshal(stale)
	if err := os.WriteFile(ledger.LockPath("key"), encoded, 0o600); err != nil {
		t.Fatal(err)
	}
	handle, err := ledger.Acquire(context.Background(), "key", "corr")
	if err != nil {
		t.Fatalf("an abandoned lock file should be reacquired: %v", err)
	}
	if handle.token == "dead-owner" {
		t.Fatal("a new owner must use a fresh crypto-random claim token")
	}
	handle.Release()
	if _, err := os.Stat(ledger.LockPath("key")); err != nil {
		t.Fatalf("the lock file must never be deleted: %v", err)
	}
}

func TestUnsafeLockTTLIsRejected(t *testing.T) {
	for _, value := range []string{"0", "-1", "not-a-number"} {
		t.Setenv(lockTTLEnv, value)
		ledger := NewLedger(t.TempDir())
		if err := ledger.EnsureReady(); err == nil {
			t.Fatalf("a lease of %q must be rejected", value)
		} else if typed, ok := err.(*Error); !ok || typed.Code != "ledger_unavailable" {
			t.Fatalf("expected ledger_unavailable for %q, got %v", value, err)
		}
	}
}

func TestClaimTokensAreFreshAndTheLockFilePersists(t *testing.T) {
	ledger := newTestLedger(t)
	first, err := ledger.Acquire(context.Background(), "key", "corr-1")
	if err != nil {
		t.Fatalf("first acquire: %v", err)
	}
	firstToken := first.token
	first.Release()

	second, err := ledger.Acquire(context.Background(), "key", "corr-2")
	if err != nil {
		t.Fatalf("second acquire: %v", err)
	}
	if second.token == firstToken {
		t.Fatal("every acquisition must use a fresh crypto-random claim token")
	}
	second.Release()

	if _, err := os.Stat(ledger.LockPath("key")); err != nil {
		t.Fatalf("the lock file must never be deleted: %v", err)
	}
}

func TestLiveOwnerIsNotPreemptedByLeaseExpiry(t *testing.T) {
	ledger := newTestLedger(t)
	ledger.LockTTL = 50 * time.Millisecond
	ledger.LockWait = 30 * time.Millisecond

	handle, err := ledger.Acquire(context.Background(), "key", "corr-1")
	if err != nil {
		t.Fatalf("acquire: %v", err)
	}
	// The heartbeat renews the lease; a slow execution is not preempted.
	time.Sleep(200 * time.Millisecond)
	if err := handle.EnsureHeld(); err != nil {
		t.Fatalf("the live owner lost its lease: %v", err)
	}
	if _, err := ledger.Acquire(context.Background(), "key", "corr-2"); err == nil {
		t.Fatal("a second acquisition must not preempt a live owner")
	} else if typed, ok := err.(*Error); !ok || typed.Code != "operation_in_progress" {
		t.Fatalf("expected operation_in_progress, got %v", err)
	}
	handle.Release()

	if _, err := os.Stat(ledger.LockPath("key")); err != nil {
		t.Fatalf("the lock file must survive release: %v", err)
	}
}

func TestClaimHonoursContextCancellation(t *testing.T) {
	ledger := newTestLedger(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := ledger.Claim(ctx, "key", "corr"); !errors.Is(err, context.Canceled) {
		t.Fatalf("expected context.Canceled, got %v", err)
	}

	release, err := ledger.Claim(context.Background(), "key", "corr-1")
	if err != nil {
		t.Fatal(err)
	}
	defer release()

	// A waiter blocked on a held claim also stops when its context ends.
	ctx, cancel = context.WithCancel(context.Background())
	ledger.LockWait = 5 * time.Second
	done := make(chan error, 1)
	go func() {
		_, err := ledger.Claim(ctx, "key", "corr-2")
		done <- err
	}()
	time.Sleep(20 * time.Millisecond)
	cancel()
	select {
	case err := <-done:
		if !errors.Is(err, context.Canceled) {
			t.Fatalf("expected context.Canceled while waiting, got %v", err)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("claim did not stop when its context was cancelled")
	}
}

func TestLedgerWriteLeavesNoTemporaryFiles(t *testing.T) {
	ledger := newTestLedger(t)
	record := sampleRecord("abcdef0123456789")
	if err := ledger.Write(record); err != nil {
		t.Fatal(err)
	}
	entries, err := os.ReadDir(ledger.KeysDir)
	if err != nil {
		t.Fatal(err)
	}
	for _, entry := range entries {
		if filepath.Ext(entry.Name()) == ".tmp" {
			t.Fatalf("temporary file left behind: %s", entry.Name())
		}
	}
}

func TestLedgerWriteRejectsUnencodableResult(t *testing.T) {
	ledger := newTestLedger(t)
	record := sampleRecord("0011223344556677")
	record.Result = map[string]any{"bad": func() {}}
	if err := ledger.Write(record); err == nil {
		t.Fatal("an unencodable record must not be silently dropped")
	}
}
