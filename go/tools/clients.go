// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

func GetClientToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_clients", Description: "List all clients with basic details",
			Method: "GET", EndpointURL: "clients", Params: []ToolParam{{Name: "limit", Type: "number", Required: false, IsQueryVar: true}},
		},
		{
			Name: "get_client", Description: "Get full details of a client by ID",
			Method: "GET", EndpointURL: "clients/%v", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_client_accounts", Description: "Get loan and savings accounts for a client",
			Method: "GET", EndpointURL: "clients/%v/accounts", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "search_clients", Description: "Search for clients by name or other fields",
			Method: "GET", EndpointURL: "clients", Params: []ToolParam{{Name: "displayName", Type: "string", Required: true, IsQueryVar: true}},
		},
		{
			Name: "create_client", Description: "Create a new client",
			Method: "POST", EndpointURL: "clients", Params: []ToolParam{
				{Name: "firstname", Type: "string", Required: true, IsBodyVar: true},
				{Name: "lastname", Type: "string", Required: true, IsBodyVar: true},
				{Name: "officeId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "active", Type: "boolean", Required: true, IsBodyVar: true},
				{Name: "activationDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "activate_client", Description: "Activate a pending client",
			Method: "POST", EndpointURL: "clients/%v?command=activate", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "activationDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "get_client_charges", Description: "Get charges for a client",
			Method: "GET", EndpointURL: "clients/%v/charges", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "update_client", Description: "Update client details",
			Method: "PUT", EndpointURL: "clients/%v", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "firstname", Type: "string", Required: false, IsBodyVar: true},
				{Name: "lastname", Type: "string", Required: false, IsBodyVar: true},
			},
		},
		{
			Name: "close_client", Description: "Close a client account",
			Method: "POST", EndpointURL: "clients/%v?command=close", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "closureDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "closureReasonId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "delete_client", Description: "Delete a client (only if no transactions/accounts)",
			Method: "DELETE", EndpointURL: "clients/%v", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
	}
}
