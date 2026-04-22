// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"fmt"

	"github.com/mark3labs/mcp-go/mcp"
)

func GetIdentityToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "get_client_identifiers", Description: "Get identifiers (IDs, Passports) for a client",
			Method: "GET", EndpointURL: "clients/%v/identifiers", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_client_identifier", Description: "Get details of a specific identifier for a client",
			Method: "GET", EndpointURL: "clients/%v/identifiers/%v", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "identifierId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "delete_client_identifier", Description: "Delete an identifier from a client",
			Method: "DELETE", EndpointURL: "clients/%v/identifiers/%v", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "identifierId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "get_client_identifiers_template", Description: "Get the template for creating client identifiers (lists allowed document types)",
			Method: "GET", EndpointURL: "clients/%v/identifiers/template", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
			},
		},
	}
}

func (r *Registry) RegisterNativeIdentityTools() {
	defs := []BaseToolDef{
		{
			Name:        "create_client_identifier",
			Description: "Create a new identifier for a client (Native)",
			Params: []ToolParam{
				{Name: "clientId", Required: true, Description: "The Client ID", Type: "string"},
				{Name: "documentTypeId", Required: true, Description: "The Document Type ID (from template)", Type: "number"},
				{Name: "documentKey", Required: true, Description: "The unique identifier key (e.g. Passport number)", Type: "string"},
				{Name: "description", Required: false, Description: "Optional description", Type: "string"},
				{Name: "status", Required: false, Description: "Identifier status (defaults to 'Active')", Type: "string"},
			},
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}

				clientId := fmt.Sprintf("%v", args["clientId"])
				dtId := args["documentTypeId"]
				key, _ := args["documentKey"].(string)
				desc, _ := args["description"].(string)
				status, _ := args["status"].(string)

				if clientId == "" || dtId == nil || key == "" {
					return mcp.NewToolResultError("clientId, documentTypeId, and documentKey are required"), nil
				}

				if status == "" {
					status = "Active"
				}

				payload := map[string]interface{}{
					"documentTypeId": dtId,
					"documentKey":    key,
					"status":         status,
				}
				if desc != "" {
					payload["description"] = desc
				}

				endpoint := fmt.Sprintf("clients/%s/identifiers", clientId)
				data, err := r.Fineract.DoRequest("POST", endpoint, payload, nil)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("%v\nDetails: %s", err, string(data))), nil
				}

				return mcp.NewToolResultText(fmt.Sprintf("SUCCESS: Created identifier for client %s. Result: %s", clientId, string(data))), nil
			},
		},
		{
			Name:        "update_client_identifier",
			Description: "Update an existing identifier (Native)",
			Params: []ToolParam{
				{Name: "clientId", Required: true, Description: "The Client ID", Type: "string"},
				{Name: "identifierId", Required: true, Description: "The Identifier ID", Type: "string"},
				{Name: "documentTypeId", Required: false, Description: "New Document Type ID", Type: "number"},
				{Name: "documentKey", Required: false, Description: "New identifier key", Type: "string"},
				{Name: "description", Required: false, Description: "New description", Type: "string"},
			},
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}

				clientId := fmt.Sprintf("%v", args["clientId"])
				idId := fmt.Sprintf("%v", args["identifierId"])

				getEndpoint := fmt.Sprintf("clients/%s/identifiers/%s", clientId, idId)
				existingData, err := r.Fineract.DoRequest("GET", getEndpoint, nil, nil)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("Failed to fetch current identifier: %v", err)), nil
				}

				var existing map[string]interface{}
				if err := json.Unmarshal(existingData, &existing); err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("Failed to parse identifier data: %v", err)), nil
				}

				docType, _ := existing["documentType"].(map[string]interface{})
				payload := map[string]interface{}{
					"documentTypeId": docType["id"],
					"documentKey":    existing["documentKey"],
					"description":    existing["description"],
				}

				hasChange := false
				if v, ok := args["documentTypeId"]; ok {
					payload["documentTypeId"] = v
					hasChange = true
				}
				if v, ok := args["documentKey"]; ok && fmt.Sprintf("%v", v) != "" {
					payload["documentKey"] = fmt.Sprintf("%v", v)
					hasChange = true
				}
				if v, ok := args["description"]; ok && fmt.Sprintf("%v", v) != "" {
					payload["description"] = fmt.Sprintf("%v", v)
					hasChange = true
				}

				if !hasChange {
					return mcp.NewToolResultError("Update failed: No changes provided (documentKey or description)."), nil
				}

				data, err := r.Fineract.DoRequest("PUT", getEndpoint, payload, nil)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("%v\nDetails: %s", err, string(data))), nil
				}

				return mcp.NewToolResultText(fmt.Sprintf("SUCCESS: Updated identifier %s for client %s. Result: %s", idId, clientId, string(data))), nil
			},
		},
	}

	r.registerTools(defs)
}
