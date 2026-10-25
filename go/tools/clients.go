// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"fmt"
	"net/url"
	"strconv"
	"strings"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

// RegisterCallerScopedHTTPTools exposes only the audited read-only pilot.
// It does not register any configured-credential handlers.
func (r *Registry) RegisterCallerScopedHTTPTools(client *adapter.CallerClient) {
	for _, def := range GetClientToolDefs() {
		switch def.Name {
		case "get_client", "get_client_accounts":
			def.Handler = func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("clientId must be a positive integer"), nil
				}
				idText := fmt.Sprint(args["clientId"])
				if number, ok := args["clientId"].(float64); ok {
					idText = strconv.FormatFloat(number, 'f', -1, 64)
				}
				id, err := strconv.ParseInt(idText, 10, 64)
				if err != nil || id <= 0 {
					return mcp.NewToolResultError("clientId must be a positive integer"), nil
				}
				endpoint := "clients/" + strconv.FormatInt(id, 10)
				if def.Name == "get_client_accounts" {
					endpoint += "/accounts"
				}
				data, err := client.DoCallerRequest(ctx, endpoint)
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				return mcp.NewToolResultText(string(data)), nil
			}
		case "search_clients":
			def.Handler = func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("displayName is required"), nil
				}
				name, ok := args["displayName"].(string)
				if !ok || strings.TrimSpace(name) == "" {
					return mcp.NewToolResultError("displayName is required"), nil
				}
				data, err := client.DoCallerRequest(ctx, "clients", url.Values{"displayName": {name}})
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				return mcp.NewToolResultText(string(data)), nil
			}
		default:
			continue
		}
		r.registerTools([]BaseToolDef{def})
	}
}

// RegisterCreateClientProposal installs only the approval proposal handler in HTTP mode.
func (r *Registry) RegisterCreateClientProposal(handler func(context.Context, mcp.CallToolRequest) (*mcp.CallToolResult, error)) {
	for _, def := range GetClientToolDefs() {
		if def.Name == "create_client" {
			def.Description = "Propose client creation for human approval. Does not execute a write."
			def.Handler = handler
			r.registerTools([]BaseToolDef{def})
		}
	}
}

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
