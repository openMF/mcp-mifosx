// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"fmt"

	"github.com/mark3labs/mcp-go/mcp"
)

func GetDocumentToolDefs() []BaseToolDef {
	return []BaseToolDef{
		// ── Client Documents ──────────────────────────────────────────
		{
			Name: "list_client_documents", Description: "List all documents attached to a client",
			Method: "GET", EndpointURL: "clients/%v/documents", Params: []ToolParam{{Name: "clientId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "retrieve_client_document", Description: "Retrieve metadata of a specific document attached to a client",
			Method: "GET", EndpointURL: "clients/%v/documents/%v", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "delete_client_document", Description: "Delete a specific document attached to a client",
			Method: "DELETE", EndpointURL: "clients/%v/documents/%v", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "get_client_document_attachment", Description: "Retrieve the binary file (attachment) associated with a client document",
			Method: "GET", EndpointURL: "clients/%v/documents/%v/attachment", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},

		// ── Loan Documents ────────────────────────────────────────────
		{
			Name: "list_loan_documents", Description: "List all documents attached to a loan",
			Method: "GET", EndpointURL: "loans/%v/documents", Params: []ToolParam{{Name: "loanId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "retrieve_loan_document", Description: "Retrieve metadata of a specific document attached to a loan",
			Method: "GET", EndpointURL: "loans/%v/documents/%v", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "delete_loan_document", Description: "Delete a specific document attached to a loan",
			Method: "DELETE", EndpointURL: "loans/%v/documents/%v", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "get_loan_document_attachment", Description: "Retrieve the binary file (attachment) associated with a loan document",
			Method: "GET", EndpointURL: "loans/%v/documents/%v/attachment", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},

		// ── Savings Account Documents ─────────────────────────────────
		{
			Name: "list_savings_documents", Description: "List all documents attached to a savings account",
			Method: "GET", EndpointURL: "savings/%v/documents", Params: []ToolParam{{Name: "savingsId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "retrieve_savings_document", Description: "Retrieve metadata of a specific document attached to a savings account",
			Method: "GET", EndpointURL: "savings/%v/documents/%v", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "delete_savings_document", Description: "Delete a document attached to a savings account",
			Method: "DELETE", EndpointURL: "savings/%v/documents/%v", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "get_savings_document_attachment", Description: "Retrieve the binary file (attachment) associated with a savings account document",
			Method: "GET", EndpointURL: "savings/%v/documents/%v/attachment", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "documentId", Type: "number", Required: true, IsPathVar: true},
			},
		},
	}
}

// RegisterNativeDocumentTools registers Go-native document creation and update tools
// that use multipart/form-data encoding (required by Fineract's document API).
func (r *Registry) RegisterNativeDocumentTools() {
	entities := []struct {
		entityName string
		paramName  string
		urlPrefix  string
	}{
		{"client", "clientId", "clients"},
		{"loan", "loanId", "loans"},
		{"savings", "savingsId", "savings"},
	}

	var defs []BaseToolDef
	for _, e := range entities {
		eName := e.entityName
		pName := e.paramName
		urlPfx := e.urlPrefix

		// Create Tool
		defs = append(defs, BaseToolDef{
			Name:        fmt.Sprintf("create_%s_document", eName),
			Description: fmt.Sprintf("Create and upload a document for a %s (uses multipart/form-data)", eName),
			Params: []ToolParam{
				{Name: pName, Required: true, Description: fmt.Sprintf("The %s ID", eName), Type: "string"},
				{Name: "name", Required: true, Description: "Document file name (e.g. Passport_ID.pdf)", Type: "string"},
				{Name: "description", Required: false, Description: "Document description", Type: "string"},
			},
			Method: "POST",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}

				entityId := fmt.Sprintf("%v", args[pName])
				name, _ := args["name"].(string)
				desc, _ := args["description"].(string)

				if entityId == "" || entityId == "<nil>" || name == "" {
					return mcp.NewToolResultError(fmt.Sprintf("Both '%s' and 'name' are required", pName)), nil
				}

				endpoint := fmt.Sprintf("%s/%s/documents", urlPfx, entityId)
				fields := map[string]string{
					"name":        name,
					"description": desc,
				}

				data, err := r.Fineract.DoMultipartRequest("POST", endpoint, fields)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("%v\nDetails: %s", err, string(data))), nil
				}

				return mcp.NewToolResultText(fmt.Sprintf("SUCCESS: Created document for %s %v. Result: %s", eName, entityId, string(data))), nil
			},
		})

		// Update Tool
		defs = append(defs, BaseToolDef{
			Name:        fmt.Sprintf("update_%s_document", eName),
			Description: fmt.Sprintf("Update an existing document for a %s (uses multipart/form-data to satisfy Fineract requirements)", eName),
			Params: []ToolParam{
				{Name: pName, Required: true, Description: fmt.Sprintf("The %s ID", eName), Type: "string"},
				{Name: "documentId", Required: true, Description: "The Document ID to update", Type: "string"},
				{Name: "name", Required: false, Description: "New document name", Type: "string"},
				{Name: "description", Required: false, Description: "New document description", Type: "string"},
			},
			Method: "PUT",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}

				entityId := fmt.Sprintf("%v", args[pName])
				docId := fmt.Sprintf("%v", args["documentId"])
				name, _ := args["name"].(string)
				desc, _ := args["description"].(string)

				if entityId == "" || entityId == "<nil>" || docId == "" || docId == "<nil>" {
					return mcp.NewToolResultError(fmt.Sprintf("Both '%s' and 'documentId' are required", pName)), nil
				}

				// For PUT, if name isn't provided, we might still need a dummy filename for the multipart resolver
				if name == "" {
					name = "updated_document.pdf"
				}

				endpoint := fmt.Sprintf("%s/%s/documents/%s", urlPfx, entityId, docId)
				fields := map[string]string{
					"name":        name,
					"description": desc,
				}

				data, err := r.Fineract.DoMultipartRequest("PUT", endpoint, fields)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("%v\nDetails: %s", err, string(data))), nil
				}

				return mcp.NewToolResultText(fmt.Sprintf("SUCCESS: Updated document %s for %s %v. Result: %s", docId, eName, entityId, string(data))), nil
			},
		})
	}

	r.registerTools(defs)
}
