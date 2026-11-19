// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package idempotency

import (
	"fmt"
	"regexp"
)

// Operation states persisted in the ledger.
const (
	StatePending         = "PENDING"
	StateInProgress      = "IN_PROGRESS"
	StateSucceeded       = "SUCCEEDED"
	StateFailed          = "FAILED"
	StateUnknown         = "UNKNOWN"
	StateRetryableAbsent = "RETRYABLE_ABSENT"
)

// Error is a typed idempotency failure safe to return to a caller.
type Error struct {
	Code          string         `json:"error_code"`
	Message       string         `json:"error"`
	HTTPStatus    int            `json:"-"`
	Retryable     bool           `json:"retryable"`
	CorrelationID string         `json:"correlation_id,omitempty"`
	State         string         `json:"idempotency_state,omitempty"`
	Details       map[string]any `json:"details,omitempty"`
}

func (e *Error) Error() string { return e.Message }

// Body is the JSON-safe error body. It never contains request data or credentials.
func (e *Error) Body() map[string]any {
	body := map[string]any{
		"error":          e.Message,
		"error_code":     e.Code,
		"retryable":      e.Retryable,
		"correlation_id": e.CorrelationID,
	}
	if e.State != "" {
		body["idempotency_state"] = e.State
	}
	if len(e.Details) > 0 {
		body["details"] = e.Details
	}
	return body
}

func (e *Error) withCorrelation(correlationID string) *Error {
	e.CorrelationID = correlationID
	return e
}

// ErrMissingKey reports a money move attempted without a key in strict mode.
func ErrMissingKey(correlationID string) *Error {
	return &Error{
		Code:          "missing_idempotency_key",
		Message:       "An idempotency_key is required for money-moving operations.",
		HTTPStatus:    400,
		Retryable:     true,
		CorrelationID: correlationID,
	}
}

// ErrInvalidKey reports a key outside the documented shape.
func ErrInvalidKey(correlationID string) *Error {
	return &Error{
		Code:          "invalid_idempotency_key",
		Message:       "Idempotency key must be 8-128 characters from [A-Za-z0-9._:-] and start with an alphanumeric.",
		HTTPStatus:    400,
		Retryable:     true,
		CorrelationID: correlationID,
	}
}

// ErrKeyReused reports the same key used for a different operation or payload.
func ErrKeyReused(correlationID, state string) *Error {
	return &Error{
		Code:          "idempotency_key_reused",
		Message:       "This idempotency key was already used for a different operation or payload.",
		HTTPStatus:    409,
		CorrelationID: correlationID,
		State:         state,
	}
}

// ErrInProgress reports another request holding the same key.
func ErrInProgress(correlationID string) *Error {
	return &Error{
		Code:          "operation_in_progress",
		Message:       "Another request with the same idempotency key is still in progress.",
		HTTPStatus:    409,
		Retryable:     true,
		CorrelationID: correlationID,
		State:         StateInProgress,
	}
}

// ErrOutcomeUnknown reports an attempt whose effect cannot be established.
func ErrOutcomeUnknown(correlationID, message string) *Error {
	return &Error{
		Code:          "outcome_unknown",
		Message:       message,
		HTTPStatus:    409,
		CorrelationID: correlationID,
		State:         StateUnknown,
	}
}

// ErrReconciliationRequired reports an ambiguous outcome that was not repeated.
func ErrReconciliationRequired(correlationID, reason string) *Error {
	details := map[string]any{}
	if reason != "" {
		details["reason"] = reason
	}
	return &Error{
		Code: "reconciliation_required",
		Message: "The operation's outcome is unknown. It may have been processed, so it was not repeated. " +
			"Verify it in Fineract before retrying with a new idempotency key.",
		HTTPStatus:    409,
		CorrelationID: correlationID,
		State:         StateUnknown,
		Details:       details,
	}
}

// ErrLedgerUnavailable reports that persistence cannot be trusted, so nothing ran.
func ErrLedgerUnavailable(message string) *Error {
	return &Error{
		Code:       "ledger_unavailable",
		Message:    "Idempotency ledger is unavailable: " + message,
		HTTPStatus: 503,
		Retryable:  true,
	}
}

var keyPattern = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$`)

// ValidateKey enforces the shape shared with the Python implementation.
func ValidateKey(key, correlationID string) error {
	if !keyPattern.MatchString(key) {
		return ErrInvalidKey(correlationID)
	}
	return nil
}

func asError(err error) *Error {
	if typed, ok := err.(*Error); ok {
		return typed
	}
	return &Error{Code: "idempotency_error", Message: fmt.Sprintf("%v", err), HTTPStatus: 500}
}
