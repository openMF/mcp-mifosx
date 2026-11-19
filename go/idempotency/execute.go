// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"strings"
)

// Outcome is how a backend call ended, from the caller's point of view.
type Outcome string

const (
	OutcomeSuccess   Outcome = "SUCCESS"
	OutcomeFailure   Outcome = "FAILURE"
	OutcomeAmbiguous Outcome = "AMBIGUOUS"
)

// Attempt is the classified result of exactly one backend call.
type Attempt struct {
	Outcome    Outcome
	Result     map[string]any
	Error      string
	BackendRef map[string]any
	Detail     string
	// Raw preserves the body a domain layer already returned for a
	// conclusive failure, so an unprotected call keeps its old shape.
	Raw map[string]any
}

// ReconcileStatus is what a backend read proved about an earlier attempt.
type ReconcileStatus string

const (
	ReconcileConfirmed ReconcileStatus = "CONFIRMED"
	ReconcileAbsent    ReconcileStatus = "ABSENT"
	ReconcileUnknown   ReconcileStatus = "UNKNOWN"
)

// ReconcileResult is the outcome of a read-only reconciliation.
type ReconcileResult struct {
	Status     ReconcileStatus
	Result     map[string]any
	BackendRef map[string]any
	Detail     string
}

// Request describes what is executed and what is needed to reconcile it.
type Request struct {
	Operation      string
	Resource       string
	Payload        map[string]any
	Reconciliation map[string]any
}

// Reconciler is an operation-specific, read-only check used after an
// ambiguous attempt. It must never mutate the backend.
type Reconciler interface {
	Snapshot(ctx context.Context, request Request) (any, error)
	Reconcile(ctx context.Context, record *Record) (ReconcileResult, error)
}

// Options carries per-call identity and test overrides.
type Options struct {
	Scope         string
	CorrelationID string
	Ledger        *Ledger
	Required      *bool
}

// Executor performs one backend attempt. It must classify the outcome and
// must return ctx.Err() when the context is cancelled.
type Executor func(ctx context.Context, backendKey string) (Attempt, error)

// CorrelationID returns a new opaque correlation id.
func CorrelationID() string {
	buffer := make([]byte, 16)
	if _, err := rand.Read(buffer); err != nil {
		return fmt.Sprintf("corr-%d", os.Getpid())
	}
	return hex.EncodeToString(buffer)
}

// Execute runs a money-moving operation at most once per idempotency key.
func Execute(ctx context.Context, request Request, key string, executor Executor, reconciler Reconciler, opts Options) (map[string]any, error) {
	correlationID := opts.CorrelationID
	if correlationID == "" {
		correlationID = CorrelationID()
	}
	scope := opts.Scope
	if scope == "" {
		scope = DefaultScope()
	}
	key = strings.TrimSpace(key)
	if request.Payload == nil {
		request.Payload = map[string]any{}
	}
	if request.Reconciliation == nil {
		request.Reconciliation = map[string]any{}
	}

	if key == "" {
		required := EnvRequired()
		if opts.Required != nil {
			required = *opts.Required
		}
		if required {
			return nil, ErrMissingKey(correlationID)
		}
		return executeUnprotected(ctx, request, executor, correlationID)
	}

	if err := ValidateKey(key, correlationID); err != nil {
		return nil, err
	}
	fingerprint, err := Fingerprint(request.Operation, request.Payload)
	if err != nil {
		return nil, fmt.Errorf("idempotency fingerprint: %w", err)
	}
	digest := KeyHash(scope, request.Operation, key)
	ledger := opts.Ledger
	if ledger == nil {
		ledger = DefaultLedger()
	}

	release, err := ledger.Acquire(ctx, digest, correlationID)
	if err != nil {
		return nil, err
	}
	defer release.Release()

	record, err := ledger.Read(digest)
	if err != nil {
		return nil, err
	}

	if record == nil {
		record = &Record{
			Version:          LedgerVersion,
			KeyHash:          digest,
			Operation:        request.Operation,
			Scope:            scope,
			Fingerprint:      fingerprint,
			State:            StateInProgress,
			Attempt:          1,
			CreatedAt:        nowText(),
			UpdatedAt:        nowText(),
			CorrelationID:    correlationID,
			AttemptStartedAt: nowText(),
			AttemptEndedAt:   "",
			OwnerPID:         os.Getpid(),
			OwnerHost:        hostname(),
			Request:          map[string]any{"resource": request.Resource},
			Reconcile:        copyMap(request.Reconciliation),
		}
		applyMarker(record, scope, request.Operation, key)
		if snapshot := snapshotFor(ctx, reconciler, request); snapshot != nil {
			record.Reconcile["snapshot"] = snapshot
		}
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
		log.Printf("idempotency record created operation=%s state=%s correlation_id=%s", request.Operation, record.State, correlationID)
	} else {
		if record.Operation != request.Operation || record.Scope != scope || record.Fingerprint != fingerprint {
			return nil, ErrKeyReused(correlationID, record.State)
		}
		switch record.State {
		case StateSucceeded:
			log.Printf("idempotent replay operation=%s correlation_id=%s", request.Operation, correlationID)
			return nonNil(record.Result), nil
		}

		// An absence-derived FAILED from an older ledger version must be
		// reconciled, never treated as a conclusive failure to retry. Any other
		// FAILED classification this implementation does not recognise fails
		// closed instead of being retried.
		legacyAbsenceFailure, legacyErr := classifyLegacyFailure(record)
		if legacyErr != nil {
			return nil, legacyErr
		}
		if record.State == StateInProgress || record.State == StatePending ||
			record.State == StateUnknown || record.State == StateRetryableAbsent || legacyAbsenceFailure {
			reconciled := reconcile(ctx, reconciler, record)
			switch reconciled.Status {
			case ReconcileConfirmed:
				record.State = StateSucceeded
				record.Result = nonNil(reconciled.Result)
				record.BackendRef = reconciled.BackendRef
				record.Error = nil
				if err := ledger.Write(record); err != nil {
					return nil, err
				}
				log.Printf("ambiguous outcome reconciled as SUCCEEDED operation=%s correlation_id=%s", request.Operation, correlationID)
				return record.Result, nil
			case ReconcileUnknown:
				record.State = StateUnknown
				record.Error = map[string]any{
					"code":      "reconciliation_required",
					"message":   reconciled.Detail,
					"retryable": false,
				}
				if err := ledger.Write(record); err != nil {
					return nil, err
				}
				return nil, ErrReconciliationRequired(correlationID, reconciled.Detail)
			}

			// ABSENT evidence is attempt-scoped and time-windowed; it is never
			// sufficient by itself to execute a financial retry.
			if !BackendDedupVerified() {
				record.State = StateUnknown
				record.Error = map[string]any{
					"code": "reconciliation_required",
					"message": "No record of this operation is visible, but backend deduplication " +
						"has not been verified, so an automatic retry is not permitted.",
					"retryable": false,
				}
				if err := ledger.Write(record); err != nil {
					return nil, err
				}
				return nil, ErrReconciliationRequired(correlationID, reconciled.Detail)
			}

			if record.State == StateRetryableAbsent {
				log.Printf("absence re-verified; controlled retry operation=%s correlation_id=%s", request.Operation, correlationID)
			} else {
				record.State = StateRetryableAbsent
				record.Reconcile["absence_confirmed_at"] = nowText()
				record.Error = map[string]any{
					"code": "safe_retry_permitted",
					"message": "The backend has no record of this operation and deduplication is " +
						"verified; retry with the same idempotency key to complete it.",
					"retryable": true,
				}
				if err := ledger.Write(record); err != nil {
					return nil, err
				}
				return map[string]any{
					"error":             "The operation was not found in Fineract; it is safe to retry with the same idempotency key.",
					"error_code":        "safe_retry_permitted",
					"correlation_id":    correlationID,
					"idempotency_state": StateRetryableAbsent,
					"safe_to_retry":     true,
				}, nil
			}
		}

		record.Attempt++
		record.State = StateInProgress
		markAttemptStarted(record)
		record.CorrelationID = correlationID
		record.OwnerPID = os.Getpid()
		record.OwnerHost = hostname()
		record.Error = nil
		applyMarker(record, scope, request.Operation, key)
		if reconciler != nil {
			if _, ok := record.Reconcile["snapshot"]; !ok {
				if snapshot := snapshotFor(ctx, reconciler, request); snapshot != nil {
					record.Reconcile["snapshot"] = snapshot
				}
			}
		}
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
	}

	if err := release.EnsureHeld(); err != nil {
		return nil, err
	}
	return runAttempt(ctx, record, request, executor, reconciler, ledger, scope, key, correlationID)
}

func executeUnprotected(ctx context.Context, request Request, executor Executor, correlationID string) (map[string]any, error) {
	log.Printf("money operation %s executed without an idempotency key (correlation_id=%s)", request.Operation, correlationID)
	attempt, err := executor(ctx, "")
	if err != nil {
		return nil, err
	}
	switch attempt.Outcome {
	case OutcomeSuccess:
		if attempt.Result == nil {
			return map[string]any{}, nil
		}
		return attempt.Result, nil
	case OutcomeFailure:
		if attempt.Raw != nil {
			return attempt.Raw, nil
		}
		return failureBody(attempt.Error, correlationID), nil
	default:
		log.Printf("ambiguous outcome without an idempotency key for %s (correlation_id=%s)", request.Operation, correlationID)
		return map[string]any{
			"error": "The outcome of this operation is unknown. It may have been processed. " +
				"Check Fineract before retrying, and pass an idempotencyKey to make retries safe.",
			"error_code":        "outcome_unknown",
			"correlation_id":    correlationID,
			"idempotency_state": StateUnknown,
			"safe_to_retry":     false,
		}, nil
	}
}

func runAttempt(ctx context.Context, record *Record, request Request, executor Executor, reconciler Reconciler, ledger *Ledger, scope, key, correlationID string) (map[string]any, error) {
	backendKey := BackendKey(scope, request.Operation, key)

	attempt, err := executor(ctx, backendKey)
	if err != nil {
		// The executor may have sent the request before failing. There is no
		// safe way to call that a failure: record UNKNOWN and let the next
		// request reconcile before anything else happens.
		markAttemptEnded(record)
		record.State = StateUnknown
		record.Error = map[string]any{
			"code":      "outcome_unknown",
			"message":   "The operation raised an unexpected error; its outcome is unknown.",
			"retryable": false,
		}
		if writeErr := ledger.Write(record); writeErr != nil {
			return nil, writeErr
		}
		log.Printf("unexpected money-operation error operation=%s correlation_id=%s", request.Operation, correlationID)
		return nil, err
	}

	markAttemptEnded(record)

	switch attempt.Outcome {
	case OutcomeSuccess:
		record.State = StateSucceeded
		record.Result = nonNil(attempt.Result)
		record.BackendRef = attempt.BackendRef
		record.Error = nil
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
		log.Printf("operation succeeded operation=%s attempt=%d correlation_id=%s", request.Operation, record.Attempt, correlationID)
		return record.Result, nil

	case OutcomeFailure:
		record.State = StateFailed
		record.Error = map[string]any{
			"code":      "operation_failed",
			"message":   attempt.Error,
			"retryable": true,
		}
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
		log.Printf("operation failed conclusively operation=%s correlation_id=%s", request.Operation, correlationID)
		return failureBody(attempt.Error, correlationID), nil
	}

	record.State = StateUnknown
	message := attempt.Error
	if message == "" {
		message = attempt.Detail
	}
	if message == "" {
		message = "The backend did not confirm the operation."
	}
	record.Error = map[string]any{
		"code":      "outcome_unknown",
		"message":   message,
		"retryable": false,
	}
	detail := attempt.Detail
	if detail == "" {
		detail = attempt.Error
	}
	record.Reconcile["ambiguous_detail"] = detail
	if err := ledger.Write(record); err != nil {
		return nil, err
	}
	log.Printf("ambiguous operation outcome operation=%s correlation_id=%s", request.Operation, correlationID)

	reconciled := reconcile(ctx, reconciler, record)
	switch reconciled.Status {
	case ReconcileConfirmed:
		record.State = StateSucceeded
		record.Result = nonNil(reconciled.Result)
		record.BackendRef = reconciled.BackendRef
		record.Error = nil
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
		log.Printf("ambiguous outcome reconciled as SUCCEEDED operation=%s correlation_id=%s", request.Operation, correlationID)
		return record.Result, nil
	case ReconcileAbsent:
		if BackendDedupVerified() {
			// Even with proven backend dedup, record RETRYABLE_ABSENT instead
			// of executing here, so every retry reconciles once more.
			record.State = StateRetryableAbsent
			record.Reconcile["absence_confirmed_at"] = nowText()
			record.Error = map[string]any{
				"code": "safe_retry_permitted",
				"message": "The backend has no record of this operation and deduplication is " +
					"verified; retry with the same idempotency key to complete it.",
				"retryable": true,
			}
			if err := ledger.Write(record); err != nil {
				return nil, err
			}
			return map[string]any{
				"error":             "The operation was not found in Fineract; it is safe to retry with the same idempotency key.",
				"error_code":        "safe_retry_permitted",
				"correlation_id":    correlationID,
				"idempotency_state": StateRetryableAbsent,
				"safe_to_retry":     true,
			}, nil
		}

		// An absence-derived outcome is NOT a failure. Without independently
		// proven dedup it stays UNKNOWN and never authorises another attempt.
		record.State = StateUnknown
		record.Error = map[string]any{
			"code": "reconciliation_required",
			"message": "No record of this operation is visible, but backend deduplication " +
				"has not been verified, so an automatic retry is not permitted.",
			"retryable": false,
		}
		if err := ledger.Write(record); err != nil {
			return nil, err
		}
		return nil, ErrReconciliationRequired(correlationID, reconciled.Detail)
	}

	record.State = StateUnknown
	record.Error = map[string]any{
		"code":      "outcome_unknown",
		"message":   reconciled.Detail,
		"retryable": false,
	}
	if err := ledger.Write(record); err != nil {
		return nil, err
	}
	return nil, ErrReconciliationRequired(correlationID, reconciled.Detail)
}

// DefaultMarkerFields are the backend fields a reconciler may use to find the
// unique per-request marker.
//
// This is a defensive allow-list, not a claim of support: current Fineract
// money-operation request bodies (loan repayment/disbursal, savings
// deposit/withdrawal, loan/savings charges) accept no field that is proven to
// be persisted and returned on a later transaction read. The marker is written
// only into the local ledger record; it is never injected into a request body.
// Marker-based confirmation therefore cannot succeed on a stock Fineract
// deployment, and an ambiguous outcome stays UNKNOWN unless a deployment (or
// future Fineract version) genuinely persists one of these fields. See
// go/README.md.
var DefaultMarkerFields = []string{
	"externalId",
	"idempotencyKey",
	"idempotency_key",
	"transactionReference",
	"note",
	"comments",
}

func errorCode(value map[string]any) string {
	if value == nil {
		return ""
	}
	code, _ := value["code"].(string)
	return code
}

// classifyLegacyFailure decides whether a persisted FAILED record may start a
// new attempt.
//
// Only the two classifications this implementation writes are understood:
// "safe_retry_permitted" (absence-derived, must reconcile first) and
// "operation_failed" (a conclusive backend refusal). A missing, non-string or
// unrecognised classification means the record cannot be trusted, so it fails
// closed rather than being retried blindly. JSON unmarshalling already rejects
// a record whose error field is not an object.
func classifyLegacyFailure(record *Record) (bool, error) {
	if record.State != StateFailed {
		return false, nil
	}
	if record.Error == nil {
		return false, ErrLedgerUnavailable("a failed idempotency record has no error classification")
	}
	code, ok := record.Error["code"].(string)
	if !ok {
		return false, ErrLedgerUnavailable("a failed idempotency record has an unusable error classification")
	}
	switch code {
	case "safe_retry_permitted":
		return true, nil
	case "operation_failed":
		return false, nil
	default:
		return false, ErrLedgerUnavailable("a failed idempotency record has an unrecognised error classification")
	}
}

func resetAbsenceEvidence(record *Record) {
	for _, name := range []string{
		"absence_reads",
		"absence_last_read_at",
		"absence_attempt",
		"absence_confirmed_at",
	} {
		delete(record.Reconcile, name)
	}
}

func markAttemptStarted(record *Record) {
	record.AttemptStartedAt = nowText()
	record.AttemptEndedAt = ""
	resetAbsenceEvidence(record)
}

func markAttemptEnded(record *Record) {
	record.AttemptEndedAt = nowText()
}

func applyMarker(record *Record, scope, operation, key string) {
	if record.Reconcile == nil {
		record.Reconcile = map[string]any{}
	}
	if _, ok := record.Reconcile["marker"]; !ok {
		record.Reconcile["marker"] = BackendKey(scope, operation, key)
	}
	if _, ok := record.Reconcile["marker_fields"]; !ok {
		fields := make([]any, len(DefaultMarkerFields))
		for index, field := range DefaultMarkerFields {
			fields[index] = field
		}
		record.Reconcile["marker_fields"] = fields
	}
}

func snapshotFor(ctx context.Context, reconciler Reconciler, request Request) any {
	if reconciler == nil {
		return nil
	}
	snapshot, err := reconciler.Snapshot(ctx, request)
	if err != nil {
		log.Printf("could not capture reconciliation snapshot operation=%s: %v", request.Operation, err)
		return nil
	}
	return snapshot
}

func reconcile(ctx context.Context, reconciler Reconciler, record *Record) ReconcileResult {
	if reconciler == nil {
		return ReconcileResult{
			Status: ReconcileUnknown,
			Detail: "No reconciler is configured for this operation; the outcome must be checked in Fineract.",
		}
	}
	result, err := reconciler.Reconcile(ctx, record)
	if err != nil {
		log.Printf("reconciliation read failed operation=%s: %v", record.Operation, err)
		return ReconcileResult{Status: ReconcileUnknown, Detail: "Reconciliation read failed: " + err.Error()}
	}
	switch result.Status {
	case ReconcileConfirmed, ReconcileAbsent, ReconcileUnknown:
		return result
	}
	// An unrecognised status is not evidence. In particular it must never fall
	// through to the ABSENT branch, which can authorise an automatic retry.
	log.Printf("reconciler returned an unrecognised status operation=%s status=%q", record.Operation, result.Status)
	return ReconcileResult{
		Status: ReconcileUnknown,
		Detail: "The reconciler returned an unrecognised status, so the outcome cannot be determined.",
	}
}

func failureBody(message, correlationID string) map[string]any {
	if message == "" {
		message = "The operation failed."
	}
	return map[string]any{
		"error":             message,
		"error_code":        "operation_failed",
		"correlation_id":    correlationID,
		"idempotency_state": StateFailed,
		"safe_to_retry":     true,
	}
}

// EnvRequired reports whether a key is mandatory in this deployment.
func EnvRequired() bool {
	switch strings.ToLower(strings.TrimSpace(os.Getenv(requiredEnv))) {
	case "1", "true", "yes", "on":
		return true
	}
	return false
}

func hostname() string {
	name, err := os.Hostname()
	if err != nil {
		return ""
	}
	return name
}

func nonNil(value map[string]any) map[string]any {
	if value == nil {
		return map[string]any{}
	}
	return value
}

func copyMap(source map[string]any) map[string]any {
	target := make(map[string]any, len(source))
	for key, value := range source {
		target[key] = value
	}
	return target
}

// TypedError extracts a *Error from any error.
func TypedError(err error) (*Error, bool) {
	if err == nil {
		return nil, false
	}
	typed, ok := err.(*Error)
	return typed, ok
}

// ErrorBody renders an error for an MCP text response.
func ErrorBody(err error) map[string]any {
	if typed, ok := TypedError(err); ok {
		return typed.Body()
	}
	return (&Error{Code: "idempotency_error", Message: err.Error(), HTTPStatus: 500}).Body()
}
