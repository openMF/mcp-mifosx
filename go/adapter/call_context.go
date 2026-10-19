// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"context"
	"crypto/sha256"
	"errors"
	"strings"
)

// CallContext is an immutable snapshot of HTTP caller banking identity.
// It never uses the configured credentials or tenant defaults.
type CallContext struct {
	authorization string
	tenant        string
}

// String avoids accidental disclosure when a context value is formatted.
func (CallContext) String() string { return "CallContext{redacted}" }

func (c CallContext) GoString() string { return c.String() }

type callerContextKey struct{}

// WithCallerContext must only be called at the trusted HTTP header boundary.
func WithCallerContext(ctx context.Context, authorization, tenant string) (context.Context, error) {
	if strings.TrimSpace(authorization) == "" || strings.TrimSpace(tenant) == "" ||
		strings.ContainsAny(authorization+tenant, "\r\n") {
		return nil, errors.New("caller Authorization and tenant headers are required")
	}
	return context.WithValue(ctx, callerContextKey{}, CallContext{authorization: authorization, tenant: tenant}), nil
}

func callerContext(ctx context.Context) (CallContext, error) {
	call, ok := ctx.Value(callerContextKey{}).(CallContext)
	if !ok || call.authorization == "" || call.tenant == "" {
		return CallContext{}, errors.New("caller authentication context is required")
	}
	return call, nil
}

// CallerFingerprint identifies the exact authorization/tenant pair without
// retaining credentials in session ownership metadata. Never expose this value.
func CallerFingerprint(ctx context.Context) ([sha256.Size]byte, error) {
	call, err := callerContext(ctx)
	if err != nil {
		return [sha256.Size]byte{}, err
	}
	// WithCallerContext rejects newlines, so this separator is unambiguous.
	return sha256.Sum256([]byte(call.authorization + "\n" + call.tenant)), nil
}
