// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

// ToolCapability describes permission availability, not access to any record.
type ToolCapability struct {
	Tool      string `json:"tool"`
	Available bool   `json:"available"`
	Reason    string `json:"reason,omitempty"`
}

// CallerCapabilities resolves only the three supported HTTP client reads.
// Lookup failures return no capabilities; callers must not treat errors as grants.
func CallerCapabilities(ctx context.Context, caller *adapter.CallerClient) ([]ToolCapability, error) {
	permitted, err := caller.ClientReadPermitted(ctx)
	if err != nil {
		return nil, err
	}
	reason := ""
	if !permitted {
		reason = "missing_permission"
	}
	return []ToolCapability{
		{Tool: "get_client", Available: permitted, Reason: reason},
		{Tool: "search_clients", Available: permitted, Reason: reason},
		{Tool: "get_client_accounts", Available: permitted, Reason: reason},
	}, nil
}
