// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"unicode/utf8"
)

// ClientReadPermitted verifies the current caller's permissions without exposing
// Fineract's authentication response or caching identity across calls.
func (c *CallerClient) ClientReadPermitted(ctx context.Context) (bool, error) {
	call, err := callerContext(ctx)
	if err != nil {
		return false, err
	}
	unresolved := errors.New("caller permissions could not be verified")
	parts := strings.SplitN(call.authorization, " ", 2)
	if len(parts) != 2 || strings.TrimSpace(parts[1]) == "" {
		return false, unresolved
	}
	method, endpoint := http.MethodGet, "userdetails"
	var body io.Reader
	switch {
	case strings.EqualFold(parts[0], "Basic"):
		// Fineract's Basic authentication API requires JSON credentials, not
		// query parameters. Derive them exclusively from the caller header.
		header := http.Header{"Authorization": {call.authorization}}
		username, password, ok := (&http.Request{Header: header}).BasicAuth()
		if !ok || username == "" || password == "" || !utf8.ValidString(username) || !utf8.ValidString(password) {
			return false, unresolved
		}
		data, err := json.Marshal(map[string]string{"username": username, "password": password})
		if err != nil {
			return false, unresolved
		}
		body = bytes.NewReader(data)
		method, endpoint = http.MethodPost, "authentication"
	case strings.EqualFold(parts[0], "Bearer"):
		// The OAuth endpoint resolves the principal from the caller token.
	default:
		return false, unresolved
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+"/"+endpoint, body)
	if err != nil {
		return false, unresolved
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	data, err := c.doCallerRequest(req, call)
	if err != nil {
		return false, unresolved
	}
	// Project only the fields needed for discovery. Never return authentication
	// keys, access tokens, user data, permissions, or downstream parse errors.
	var result struct {
		Authenticated                     bool            `json:"authenticated"`
		Permissions                       []string        `json:"permissions"`
		ShouldRenewPassword               json.RawMessage `json:"shouldRenewPassword"`
		IsTwoFactorAuthenticationRequired json.RawMessage `json:"isTwoFactorAuthenticationRequired"`
		TwoFactorAuthenticationRequired   json.RawMessage `json:"twoFactorAuthenticationRequired"`
	}
	if json.Unmarshal(data, &result) != nil || !result.Authenticated || result.Permissions == nil {
		return false, unresolved
	}
	for _, flag := range []json.RawMessage{result.ShouldRenewPassword, result.IsTwoFactorAuthenticationRequired, result.TwoFactorAuthenticationRequired} {
		if len(flag) != 0 && string(flag) != "false" {
			return false, unresolved
		}
	}
	permitted := false
	for _, permission := range result.Permissions {
		if strings.TrimSpace(permission) == "" {
			return false, unresolved
		}
		// Verified in Fineract AppUser.validateHasReadPermission and
		// ClientsApiResource: all three pilot endpoints read CLIENT.
		switch permission {
		case "READ_CLIENT", "ALL_FUNCTIONS", "ALL_FUNCTIONS_READ":
			permitted = true
		}
	}
	return permitted, nil
}
