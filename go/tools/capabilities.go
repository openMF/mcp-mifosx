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

// ToolAvailable requires an explicit grant. Missing or unknown capabilities
// never make a tool available.
func ToolAvailable(capabilities []ToolCapability, name string) bool {
	for _, capability := range capabilities {
		if capability.Tool == name {
			return capability.Available
		}
	}
	return false
}

// CallerCapabilities resolves the supported caller-scoped CLIENT operations.
// Lookup failures return no capabilities; callers must not treat errors as grants.
func CallerCapabilities(ctx context.Context, caller *adapter.CallerClient) ([]ToolCapability, error) {
	permitted, err := caller.ClientPermissions(ctx)
	if err != nil {
		return nil, err
	}
	reason := ""
	if !permitted.Read {
		reason = "missing_permission"
	}
	createReason := ""
	if !permitted.Create {
		createReason = "missing_permission"
	}
	return []ToolCapability{
		{Tool: "get_client", Available: permitted.Read, Reason: reason},
		{Tool: "search_clients", Available: permitted.Read, Reason: reason},
		{Tool: "get_client_accounts", Available: permitted.Read, Reason: reason},
		{Tool: "create_client", Available: permitted.Create, Reason: createReason},
	}, nil
}
