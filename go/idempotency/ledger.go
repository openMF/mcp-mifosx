// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"math"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"
)

// LedgerVersion is the shared on-disk schema version.
const LedgerVersion = 1

const (
	ledgerDirEnv     = "MCP_IDEMPOTENCY_DIR"
	lockTTLEnv       = "MCP_IDEMPOTENCY_LOCK_TTL_SECONDS"
	lockWaitEnv      = "MCP_IDEMPOTENCY_LOCK_WAIT_SECONDS"
	requiredEnv      = "MCP_IDEMPOTENCY_REQUIRED"
	absenceReadEnv   = "MCP_IDEMPOTENCY_ABSENCE_CONFIRMATIONS"
	absenceWindowEnv = "MCP_IDEMPOTENCY_ABSENCE_SAFETY_WINDOW_SECONDS"
	backendDedupEnv  = "MCP_IDEMPOTENCY_BACKEND_DEDUP_VERIFIED"

	defaultLockTTL    = 900 * time.Second
	defaultLockWait   = 5 * time.Second
	defaultAbsWindow  = 30 * time.Second
	defaultPoll       = 50 * time.Millisecond
	defaultAbsenceMsg = "No record of this operation exists in Fineract."

	// A rename can fail briefly while a concurrent reader holds the target on
	// Windows; a short retry turns that into a normal write.
	retryAttempts = 50
	retryPause    = 5 * time.Millisecond
)

// Record is one idempotency key's durable state. The JSON field names are
// part of the cross-language contract with python/core/idempotency.py.
type Record struct {
	Version          int            `json:"version"`
	KeyHash          string         `json:"key_hash"`
	Operation        string         `json:"operation"`
	Scope            string         `json:"scope"`
	Fingerprint      string         `json:"fingerprint"`
	State            string         `json:"state"`
	Attempt          int            `json:"attempt"`
	CreatedAt        string         `json:"created_at"`
	UpdatedAt        string         `json:"updated_at"`
	CorrelationID    string         `json:"correlation_id"`
	AttemptStartedAt string         `json:"attempt_started_at"`
	AttemptEndedAt   string         `json:"attempt_ended_at"`
	OwnerPID         int            `json:"owner_pid"`
	OwnerHost        string         `json:"owner_host"`
	Request          map[string]any `json:"request"`
	Result           map[string]any `json:"result"`
	BackendRef       map[string]any `json:"backend_ref"`
	Reconcile        map[string]any `json:"reconcile"`
	Error            map[string]any `json:"error"`
}

// Ledger is an atomic, crash-safe, single-host ledger shared with Python.
type Ledger struct {
	Dir      string
	KeysDir  string
	LockTTL  time.Duration
	LockWait time.Duration
	Poll     time.Duration

	lockTTLError error
}

// NewLedger resolves the configured directory and lease timings.
func NewLedger(directory string) *Ledger {
	if directory == "" {
		directory = os.Getenv(ledgerDirEnv)
	}
	if directory == "" {
		home, err := os.UserHomeDir()
		if err != nil || home == "" {
			home = "."
		}
		directory = filepath.Join(home, ".mcp-mifosx", "idempotency")
	}
	ttl, ttlErr := envPositiveDuration(lockTTLEnv, defaultLockTTL)
	return &Ledger{
		Dir:          directory,
		KeysDir:      filepath.Join(directory, "keys"),
		LockTTL:      ttl,
		LockWait:     envDuration(lockWaitEnv, defaultLockWait),
		Poll:         defaultPoll,
		lockTTLError: ttlErr,
	}
}

var (
	defaultLedgerMu sync.Mutex
	defaultLedger   *Ledger
)

// DefaultLedger returns the process-wide ledger.
func DefaultLedger() *Ledger {
	defaultLedgerMu.Lock()
	defer defaultLedgerMu.Unlock()
	if defaultLedger == nil {
		defaultLedger = NewLedger("")
	}
	return defaultLedger
}

// ResetDefaultLedger re-reads the configured directory (test hook).
func ResetDefaultLedger() {
	defaultLedgerMu.Lock()
	defer defaultLedgerMu.Unlock()
	defaultLedger = nil
}

// EnsureReady creates the ledger directory or fails closed with a typed error.
func (l *Ledger) EnsureReady() error {
	if l.lockTTLError != nil {
		return ErrLedgerUnavailable("invalid idempotency lock configuration: " + l.lockTTLError.Error())
	}
	if l.LockTTL <= 0 {
		return ErrLedgerUnavailable("MCP_IDEMPOTENCY_LOCK_TTL_SECONDS must be greater than zero")
	}
	if err := os.MkdirAll(l.KeysDir, 0o700); err != nil {
		return ErrLedgerUnavailable(fmt.Sprintf("cannot create %s: %v", l.Dir, err))
	}
	return nil
}

// RecordPath is the durable record for a key hash.
func (l *Ledger) RecordPath(keyHash string) string {
	return filepath.Join(l.KeysDir, keyHash+".json")
}

// LockPath is the in-flight claim for a key hash.
func (l *Ledger) LockPath(keyHash string) string {
	return filepath.Join(l.KeysDir, keyHash+".lock")
}

// Read returns nil when no record exists. A corrupt or unreadable record is a
// LedgerUnavailable error: guessing would be unsafe.
func (l *Ledger) Read(keyHash string) (*Record, error) {
	data, err := os.ReadFile(l.RecordPath(keyHash))
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, ErrLedgerUnavailable(fmt.Sprintf("cannot read record: %v", err))
	}
	var record Record
	if err := json.Unmarshal(data, &record); err != nil {
		return nil, ErrLedgerUnavailable(fmt.Sprintf("record is corrupt: %v", err))
	}
	if record.Version != LedgerVersion {
		return nil, ErrLedgerUnavailable(fmt.Sprintf("unsupported ledger version %d", record.Version))
	}
	if record.KeyHash == "" || record.Operation == "" || record.Scope == "" ||
		record.Fingerprint == "" || record.State == "" || record.CreatedAt == "" || record.UpdatedAt == "" {
		return nil, ErrLedgerUnavailable("record is missing required fields")
	}
	switch record.State {
	case StatePending, StateInProgress, StateSucceeded, StateFailed, StateUnknown, StateRetryableAbsent:
	default:
		return nil, ErrLedgerUnavailable(fmt.Sprintf("record has an unknown state %q", record.State))
	}
	if record.Request == nil {
		record.Request = map[string]any{}
	}
	if record.Reconcile == nil {
		record.Reconcile = map[string]any{}
	}
	return &record, nil
}

// Write replaces the record atomically: a fully synced temp file renamed over
// the target. A crash never leaves a half-written record visible.
func (l *Ledger) Write(record *Record) error {
	if err := l.EnsureReady(); err != nil {
		return err
	}
	record.Version = LedgerVersion
	record.UpdatedAt = nowText()
	if record.Request == nil {
		record.Request = map[string]any{}
	}
	if record.Reconcile == nil {
		record.Reconcile = map[string]any{}
	}
	data, err := json.Marshal(record)
	if err != nil {
		return ErrLedgerUnavailable(fmt.Sprintf("cannot encode record: %v", err))
	}
	target := l.RecordPath(record.KeyHash)
	tmp := filepath.Join(l.KeysDir, fmt.Sprintf(".%s.%d.%d.tmp", record.KeyHash, os.Getpid(), time.Now().UnixNano()))
	file, err := os.OpenFile(tmp, os.O_CREATE|os.O_EXCL|os.O_WRONLY, 0o600)
	if err != nil {
		return ErrLedgerUnavailable(fmt.Sprintf("cannot create record: %v", err))
	}
	_, writeErr := file.Write(data)
	syncErr := file.Sync()
	closeErr := file.Close()
	if writeErr != nil || syncErr != nil || closeErr != nil {
		os.Remove(tmp)
		return ErrLedgerUnavailable("cannot write record")
	}
	if err := replaceFile(tmp, target); err != nil {
		os.Remove(tmp)
		return ErrLedgerUnavailable(fmt.Sprintf("cannot publish record: %v", err))
	}
	return nil
}

// replaceFile renames tmp over target, retrying briefly. On Windows a rename
// fails while a concurrent reader holds a handle to the target, so the short
// retry turns a harmless race into a normal write instead of a spurious
// ledger failure.
func replaceFile(tmp, target string) error {
	var err error
	for attempt := 0; attempt < retryAttempts; attempt++ {
		err = os.Rename(tmp, target)
		if err == nil {
			return nil
		}
		time.Sleep(retryPause)
	}
	return err
}

// ClaimHandle owns the OS lock for one key.
//
// The claim token is crypto-random per acquisition and lives in the claim
// body while the OS lock is held. Release never deletes the lock file: it
// verifies the token (best effort, under the still-held lock) and then
// unlocks/closes its own handle. A former owner therefore cannot delete a
// successor's claim, and a successor can only exist after the former owner's
// OS lock is gone.
type ClaimHandle struct {
	ledger        *Ledger
	path          string
	file          *os.File
	token         string
	correlationID string
	acquiredAt    string

	mu       sync.Mutex
	released bool

	stop chan struct{}
	done chan struct{}
}

// Acquire obtains the exclusive OS lock for one key and starts its heartbeat.
func (l *Ledger) Acquire(ctx context.Context, keyHash, correlationID string) (*ClaimHandle, error) {
	if err := ctx.Err(); err != nil {
		// Nothing was sent, so there is no outcome to record.
		return nil, err
	}
	if err := l.EnsureReady(); err != nil {
		return nil, err
	}
	path := l.LockPath(keyHash)
	deadline := time.Now().Add(l.LockWait)

	for {
		file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
		if err != nil {
			return nil, ErrLedgerUnavailable(fmt.Sprintf("cannot open claim: %v", err))
		}
		if err := tryLockFile(file); err != nil {
			file.Close()
			if !isLockContention(err) {
				return nil, ErrLedgerUnavailable(fmt.Sprintf("cannot lock claim: %v", err))
			}
			if time.Now().After(deadline) {
				return nil, ErrInProgress(correlationID)
			}
			select {
			case <-ctx.Done():
				return nil, ctx.Err()
			case <-time.After(l.Poll):
			}
			continue
		}
		// The lock is held, so it is safe to initialise byte 0: a waiter that
		// has not locked the file never writes into an owner's locked range.
		if info, statErr := file.Stat(); statErr == nil && info.Size() == 0 {
			if _, writeErr := file.WriteAt([]byte("\n"), 0); writeErr != nil {
				_ = unlockFile(file)
				file.Close()
				return nil, ErrLedgerUnavailable("cannot initialise the idempotency claim")
			}
		}

		handle := &ClaimHandle{
			ledger:        l,
			path:          path,
			file:          file,
			token:         claimToken(),
			correlationID: correlationID,
			acquiredAt:    nowText(),
		}
		if err := handle.writeClaim(); err != nil {
			_ = unlockFile(file)
			file.Close()
			return nil, err
		}
		handle.startHeartbeat()
		return handle, nil
	}
}

// Claim preserves the original public API: it returns the release function.
func (l *Ledger) Claim(ctx context.Context, keyHash, correlationID string) (func(), error) {
	handle, err := l.Acquire(ctx, keyHash, correlationID)
	if err != nil {
		return nil, err
	}
	return handle.Release, nil
}

func claimToken() string {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		// crypto/rand failure is exceptional; a timestamp is still unique
		// enough to avoid an accidental match, and the OS lock remains the
		// actual ownership mechanism.
		return fmt.Sprintf("t-%d", time.Now().UnixNano())
	}
	return hex.EncodeToString(buffer)
}

func (h *ClaimHandle) writeClaim() error {
	h.mu.Lock()
	defer h.mu.Unlock()
	if h.released {
		return ErrLedgerUnavailable("the idempotency claim has already been released")
	}
	now := time.Now().UTC()
	host, _ := os.Hostname()
	claim := map[string]any{
		"claim_token":      h.token,
		"owner_pid":        os.Getpid(),
		"owner_host":       host,
		"acquired_at":      h.acquiredAt,
		"heartbeat_at":     now.Format(timeLayout),
		"lease_expires_at": now.Add(h.ledger.LockTTL).Format(timeLayout),
		"correlation_id":   h.correlationID,
	}
	data, err := json.Marshal(claim)
	if err != nil {
		return ErrLedgerUnavailable("cannot encode the idempotency claim")
	}
	// Never truncate to zero: a waiter could observe an empty file between
	// truncate and write and try to initialise the byte range itself. Pad with
	// spaces instead so the file is always non-empty after the first claim.
	previous := int64(0)
	if info, statErr := h.file.Stat(); statErr == nil {
		previous = info.Size()
	}
	if _, err := h.file.WriteAt(data, 0); err != nil {
		return ErrLedgerUnavailable(fmt.Sprintf("cannot record the idempotency claim: %v", err))
	}
	if previous > int64(len(data)) {
		padding := make([]byte, previous-int64(len(data)))
		for index := range padding {
			padding[index] = ' '
		}
		if _, err := h.file.WriteAt(padding, int64(len(data))); err != nil {
			return ErrLedgerUnavailable(fmt.Sprintf("cannot record the idempotency claim: %v", err))
		}
	}
	if err := h.file.Sync(); err != nil {
		return ErrLedgerUnavailable(fmt.Sprintf("cannot sync the idempotency claim: %v", err))
	}
	return nil
}

func (h *ClaimHandle) readClaim() (map[string]any, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, err := h.file.Seek(0, io.SeekStart); err != nil {
		return nil, err
	}
	data := make([]byte, 65536)
	count, err := h.file.Read(data)
	if err != nil && !errors.Is(err, io.EOF) {
		return nil, err
	}
	claim := map[string]any{}
	if len(data[:count]) == 0 {
		return claim, nil
	}
	if err := json.Unmarshal(data[:count], &claim); err != nil {
		return nil, err
	}
	return claim, nil
}

// EnsureHeld fails closed if ownership or the lease can no longer be proven.
// Ownership cannot be transferred while this process holds the OS lock, so
// this is defence in depth against a heartbeat that stopped renewing the lease
// and against a claim body that no longer carries this handle's token.
//
// External deletion or replacement of the lock *path* is explicitly
// unsupported and outside the threat model: ownership is checked through this
// handle's open descriptor, not by re-opening the path, so a process that
// unlinks or replaces the file behind this process's back cannot be detected
// reliably on every supported platform. This implementation never deletes or
// renames lock files.
func (h *ClaimHandle) EnsureHeld() error {
	claim, err := h.readClaim()
	if err != nil {
		return ErrLedgerUnavailable("the idempotency claim metadata is unreadable")
	}
	if claimString(claim["claim_token"]) != h.token || claimInt(claim["owner_pid"]) != os.Getpid() {
		return ErrInProgress(h.correlationID)
	}
	if expires := claimString(claim["lease_expires_at"]); expires != "" {
		parsed, parseErr := time.Parse(timeLayout, expires)
		if parseErr != nil {
			return ErrLedgerUnavailable("the idempotency lease timestamp is invalid")
		}
		if time.Now().UTC().After(parsed) {
			return ErrLedgerUnavailable("the idempotency lease expired before the operation completed; refusing to execute")
		}
	}
	return nil
}

func (h *ClaimHandle) startHeartbeat() {
	h.stop = make(chan struct{})
	h.done = make(chan struct{})
	interval := h.ledger.LockTTL / 3
	if interval <= 0 || interval > time.Minute {
		interval = time.Minute
	}
	if interval < 10*time.Millisecond {
		interval = 10 * time.Millisecond
	}
	go func() {
		defer close(h.done)
		ticker := time.NewTicker(interval)
		defer ticker.Stop()
		for {
			select {
			case <-h.stop:
				return
			case <-ticker.C:
				if err := h.writeClaim(); err != nil {
					return
				}
			}
		}
	}()
}

// Release stops the heartbeat and unlocks/closes this handle. It never
// deletes or rewrites the claim file, so it cannot damage a successor.
func (h *ClaimHandle) Release() {
	h.mu.Lock()
	if h.released {
		h.mu.Unlock()
		return
	}
	h.released = true
	h.mu.Unlock()

	if h.stop != nil {
		close(h.stop)
		<-h.done
	}
	if claim, err := h.readClaim(); err == nil && claimString(claim["claim_token"]) != h.token {
		fmt.Fprintf(os.Stderr, "mcp-mifosx: claim %s changed owner before release; leaving it untouched\n", filepath.Base(h.path))
	}
	if err := unlockFile(h.file); err != nil {
		fmt.Fprintf(os.Stderr, "mcp-mifosx: could not unlock idempotency claim %s\n", filepath.Base(h.path))
	}
	h.file.Close()
}

const timeLayout = "2006-01-02T15:04:05.000000Z"

func claimString(value any) string {
	if text, ok := value.(string); ok {
		return text
	}
	return ""
}

func claimInt(value any) int {
	switch typed := value.(type) {
	case int:
		return typed
	case int64:
		return int(typed)
	case float64:
		return int(typed)
	case json.Number:
		parsed, err := typed.Int64()
		if err == nil {
			return int(parsed)
		}
	}
	return 0
}

func nowText() string {
	return time.Now().UTC().Format(timeLayout)
}

func envDuration(name string, fallback time.Duration) time.Duration {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
		return fallback
	}
	return time.Duration(value * float64(time.Second))
}

// envPositiveDuration rejects zero, negative and non-finite lease values.
// A zero lease used to make every claim look instantly stale, which let a
// second process preempt a live owner.
func envPositiveDuration(name string, fallback time.Duration) (time.Duration, error) {
	raw := os.Getenv(name)
	if raw == "" {
		return fallback, nil
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || value <= 0 {
		return fallback, fmt.Errorf("%s must be greater than zero", name)
	}
	return time.Duration(value * float64(time.Second)), nil
}

// AbsenceConfirmations is how many spaced empty reconciliation reads are
// needed before an attempt is treated as conclusively absent.
func AbsenceConfirmations() int {
	raw := os.Getenv(absenceReadEnv)
	if raw == "" {
		return 2
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1 {
		return 2
	}
	return value
}

// AbsenceSafetyWindow is the minimum quiet period after an attempt ended, and
// the minimum spacing between empty reads. It is a mitigation, not proof of
// backend read consistency.
func AbsenceSafetyWindow() time.Duration {
	raw := os.Getenv(absenceWindowEnv)
	if raw == "" {
		return defaultAbsWindow
	}
	value, err := strconv.ParseFloat(raw, 64)
	if err != nil || math.IsNaN(value) || math.IsInf(value, 0) || value < 0 {
		return defaultAbsWindow
	}
	return time.Duration(value * float64(time.Second))
}

// BackendDedupVerified reports whether the deployment has independently
// proven that the backend deduplicates on the idempotency key. Defaults false:
// without proof, even confirmed absence stays UNKNOWN.
func BackendDedupVerified() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(backendDedupEnv))) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}
