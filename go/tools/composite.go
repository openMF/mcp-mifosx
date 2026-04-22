// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"fmt"
	"sync"

	"github.com/mark3labs/mcp-go/mcp"
)

func (r *Registry) RegisterCompositeTools() {
	r.registerTools([]BaseToolDef{
		{
			Name:        "get_client_holistic_view",
			Description: "Concurrently fetches all client details, accounts, charges, and identifiers in one parallel turn.",
			Params: []ToolParam{
				{Name: "clientId", Required: true, Description: "The ID of the client", Type: "string"},
			},
			Method: "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}
				clientId, _ := args["clientId"].(string)

				endpoints := map[string]string{
					"details":     fmt.Sprintf("clients/%s", clientId),
					"accounts":    fmt.Sprintf("clients/%s/accounts", clientId),
					"charges":     fmt.Sprintf("clients/%s/charges", clientId),
					"identifiers": fmt.Sprintf("clients/%s/identifiers", clientId),
				}

				results := make(map[string]interface{})
				var mu sync.Mutex
				var wg sync.WaitGroup

				for key, url := range endpoints {
					wg.Add(1)
					go func(k, u string) {
						defer wg.Done()
						data, err := r.Fineract.DoRequest("GET", u, nil, nil)
						var val interface{}
						if err == nil {
							json.Unmarshal(data, &val)
						} else {
							val = fmt.Sprintf("Error: %v", err)
						}
						mu.Lock()
						results[k] = val
						mu.Unlock()
					}(key, url)
				}

				wg.Wait()
				resp, _ := json.MarshalIndent(results, "", "  ")
				return mcp.NewToolResultText(string(resp)), nil
			},
		},
		{
			Name:        "search_all_domains",
			Description: "Searches concurrently across Clients, Groups, and Staff for a keyword.",
			Params: []ToolParam{
				{Name: "query", Required: true, Description: "Search keyword", Type: "string"},
			},
			Method: "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}
				query, _ := args["query"].(string)
				queryParams := map[string]string{"displayName": query, "name": query, "sqlSearch": "name like '" + query + "%'"}

				searches := map[string]string{
					"clients": "clients",
					"groups":  "groups",
					"staff":   "staff",
				}

				results := make(map[string]interface{})
				var mu sync.Mutex
				var wg sync.WaitGroup

				for key, endpoint := range searches {
					wg.Add(1)
					go func(k, e string) {
						defer wg.Done()
						data, err := r.Fineract.DoRequest("GET", e, nil, queryParams)
						var val interface{}
						if err == nil {
							json.Unmarshal(data, &val)
						}
						mu.Lock()
						results[k] = val
						mu.Unlock()
					}(key, endpoint)
				}

				wg.Wait()
				resp, _ := json.MarshalIndent(results, "", "  ")
				return mcp.NewToolResultText(string(resp)), nil
			},
		},
		{
			Name:        "get_system_overview",
			Description: "Concurrently fetches a snapshot of Offices, Staff, Loan Products, and Savings Products.",
			Method:      "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				overview := map[string]string{
					"offices":          "offices",
					"staff":            "staff",
					"loan_products":    "loanproducts",
					"savings_products": "savingsproducts",
				}

				results := make(map[string]interface{})
				var mu sync.Mutex
				var wg sync.WaitGroup

				for key, endpoint := range overview {
					wg.Add(1)
					go func(k, e string) {
						defer wg.Done()
						data, err := r.Fineract.DoRequest("GET", e, nil, nil)
						var val interface{}
						if err == nil {
							json.Unmarshal(data, &val)
						}
						mu.Lock()
						results[k] = val
						mu.Unlock()
					}(key, endpoint)
				}

				wg.Wait()
				resp, _ := json.MarshalIndent(results, "", "  ")
				return mcp.NewToolResultText(string(resp)), nil
			},
		},
	})
}
