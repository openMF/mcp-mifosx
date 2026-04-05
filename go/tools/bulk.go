// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
)

type BulkDef struct {
	Name        string
	Description string
	Method      string
	EndpointURL string
}

func (r *Registry) RegisterBulkTools() {
	bulkDefs := []BulkDef{
		{"bulk_search_clients", "Search multiple client names concurrently", "GET", "search"},
		{"bulk_get_loan_status", "Get loan details for multiple loans concurrently", "GET", "loans/%v"},
		{"bulk_disburse_loans", "Disburse multiple loans concurrently", "POST", "loans/%v?command=disburse"},
		{"bulk_make_repayments", "Make loop repayments concurrently", "POST", "loans/%v/transactions?command=repayment"},
		{"bulk_activate_clients", "Activate multiple clients concurrently", "POST", "clients/%v?command=activate"},
		{"bulk_get_savings_balances", "Get savings details concurrently", "GET", "savingsaccounts/%v"},
		{"bulk_apply_fees", "Apply fees to multiple loans simultaneously", "POST", "loans/%v/charges"},
		{"bulk_close_accounts", "Close multiple savings accounts concurrently", "POST", "savingsaccounts/%v?command=close"},
		{"bulk_create_savings_accounts", "Create multiple savings accounts concurrently", "POST", "savingsaccounts"},
		{"bulk_approve_and_activate_savings", "Approve and activate multiple savings concurrently", "POST", "savingsaccounts/%v?command=approve"},
		{"bulk_deposit_savings", "Deposit into multiple savings concurrently", "POST", "savingsaccounts/%v/transactions?command=deposit"},
	}

	for _, d := range bulkDefs {
		tool := mcp.NewTool(d.Name,
			mcp.WithDescription(d.Description),
			mcp.WithString("requests", mcp.Required(), mcp.Description("A JSON array of parameter block objects corresponding to the single operation parameters.")),
		)

		def := d
		r.Server.AddTool(tool, func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {

			args, ok := req.Params.Arguments.(map[string]interface{})
			if !ok {
				return mcp.NewToolResultError("Arguments must be a valid JSON object"), nil
			}

			requestsRaw, ok := args["requests"].(string)
			if !ok {
				return mcp.NewToolResultError("Missing 'requests' parameter as stringified JSON array"), nil
			}

			var payloadArray []map[string]interface{}
			if err := json.Unmarshal([]byte(requestsRaw), &payloadArray); err != nil {
				return mcp.NewToolResultError("Failed to parse 'requests' array JSON: " + err.Error()), nil
			}

			var wg sync.WaitGroup
			results := make([]string, len(payloadArray))
			errorsFound := false

			for i, payload := range payloadArray {
				wg.Add(1)
				go func(idx int, p map[string]interface{}) {
					defer wg.Done()

					endpoint := def.EndpointURL
					queryParams := make(map[string]string)
					bodyParams := make(map[string]interface{})

					if strings.Contains(endpoint, "%v") {
						idVal := p["clientId"]
						if idVal == nil {
							idVal = p["loanId"]
						}
						if idVal == nil {
							idVal = p["savingsId"]
						}
						if idVal == nil {
							idVal = p["groupId"]
						}

						if idVal != nil {
							endpoint = strings.Replace(endpoint, "%v", fmt.Sprintf("%v", idVal), 1)
						}
					}

					if def.Method == "GET" {
						for k, v := range p {
							queryParams[k] = fmt.Sprintf("%v", v)
						}
					} else {
						for k, v := range p {
							if k == "clientId" || k == "loanId" || k == "savingsId" || k == "groupId" {
								continue
							}
							bodyParams[k] = v
						}
					}

					respData, err := r.Fineract.DoRequest(def.Method, endpoint, bodyParams, queryParams)
					if err != nil {
						results[idx] = fmt.Sprintf("Error on index %d: %v", idx, err)
						errorsFound = true
					} else {
						results[idx] = string(respData)
					}
				}(i, payload)
			}

			wg.Wait()

			finalOutput := "[" + strings.Join(results, ",") + "]"
			if errorsFound {
				return mcp.NewToolResultError("SUCCESS: Executed " + def.Name + ". Bulk execution completed with some errors: " + finalOutput), nil
			}
			return mcp.NewToolResultText("SUCCESS: Executed " + def.Name + ". Result: " + finalOutput), nil
		})
	}
}

func (r *Registry) RegisterNativeBulkTools() {
	defs := r.GetNativeBulkToolDefs()
	r.registerTools(defs)
}

func (r *Registry) GetNativeBulkToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name:        "bulk_get_client_profiles",
			Description: "Fetch full details for multiple Client IDs in parallel (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args := req.GetArguments()
				clientIdsRaw, ok := args["clientIds"].([]any)
				if !ok {
					return mcp.NewToolResultError("clientIds must be an array of numbers"), nil
				}

				var wg sync.WaitGroup
				results := make([]string, len(clientIdsRaw))
				var mu sync.Mutex

				for i, idRaw := range clientIdsRaw {
					wg.Add(1)
					go func(idx int, id interface{}) {
						defer wg.Done()
						endpoint := fmt.Sprintf("clients/%v", id)
						respData, err := r.Fineract.DoRequest("GET", endpoint, nil, nil)
						
						mu.Lock()
						if err != nil {
							results[idx] = fmt.Sprintf(`{"id": %v, "error": "%v"}`, id, err)
						} else {
							results[idx] = string(respData)
						}
						mu.Unlock()
					}(i, idRaw)
				}
				wg.Wait()
				return mcp.NewToolResultText("[" + strings.Join(results, ",") + "]"), nil
			},
			Params: []ToolParam{
				{Name: "clientIds", Description: "Array of Client IDs to fetch", Type: "array", Required: true},
			},
		},
		{
			Name:        "bulk_get_loan_summaries",
			Description: "Fetch status and details for multiple Loan IDs in parallel (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args := req.GetArguments()
				loanIdsRaw, ok := args["loanIds"].([]any)
				if !ok {
					return mcp.NewToolResultError("loanIds must be an array of numbers"), nil
				}

				var wg sync.WaitGroup
				results := make([]string, len(loanIdsRaw))
				var mu sync.Mutex

				for i, idRaw := range loanIdsRaw {
					wg.Add(1)
					go func(idx int, id interface{}) {
						defer wg.Done()
						endpoint := fmt.Sprintf("loans/%v", id)
						respData, err := r.Fineract.DoRequest("GET", endpoint, nil, nil)
						
						mu.Lock()
						if err != nil {
							results[idx] = fmt.Sprintf(`{"id": %v, "error": "%v"}`, id, err)
						} else {
							results[idx] = string(respData)
						}
						mu.Unlock()
					}(i, idRaw)
				}
				wg.Wait()
				return mcp.NewToolResultText("[" + strings.Join(results, ",") + "]"), nil
			},
			Params: []ToolParam{
				{Name: "loanIds", Description: "Array of Loan IDs to fetch", Type: "array", Required: true},
			},
		},
		{
			Name:        "bulk_search_clients_parallel",
			Description: "Search for multiple name fragments concurrently (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args := req.GetArguments()
				queriesRaw, ok := args["queries"].([]any)
				if !ok {
					return mcp.NewToolResultError("queries must be an array of strings"), nil
				}

				var wg sync.WaitGroup
				results := make([]string, len(queriesRaw))
				var mu sync.Mutex

				for i, queryRaw := range queriesRaw {
					wg.Add(1)
					go func(idx int, q interface{}) {
						defer wg.Done()
						queryParams := map[string]string{"displayName": fmt.Sprintf("%v", q)}
						respData, err := r.Fineract.DoRequest("GET", "clients", nil, queryParams)
						
						mu.Lock()
						if err != nil {
							results[idx] = fmt.Sprintf(`{"query": "%v", "error": "%v"}`, q, err)
						} else {
							results[idx] = string(respData)
						}
						mu.Unlock()
					}(i, queryRaw)
				}
				wg.Wait()
				return mcp.NewToolResultText("[" + strings.Join(results, ",") + "]"), nil
			},
			Params: []ToolParam{
				{Name: "queries", Description: "Array of search strings", Type: "array", Required: true},
			},
		},
		{
			Name:        "bulk_apply_charges_to_batch",
			Description: "Apply a single charge to multiple clients concurrently (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args := req.GetArguments()
				clientIdsRaw, ok := args["clientIds"].([]any)
				chargeId, ok2 := args["chargeId"].(float64)
				if !ok || !ok2 {
					return mcp.NewToolResultError("Missing clientIds (array) or chargeId (number)"), nil
				}

				var wg sync.WaitGroup
				results := make([]string, len(clientIdsRaw))
				var mu sync.Mutex

				body := map[string]interface{}{
					"chargeId": chargeId,
					"amount":   args["amount"],
					"dueDate":  args["dueDate"],
				}

				for i, idRaw := range clientIdsRaw {
					wg.Add(1)
					go func(idx int, id interface{}) {
						defer wg.Done()
						endpoint := fmt.Sprintf("clients/%v/charges", id)
						respData, err := r.Fineract.DoRequest("POST", endpoint, body, nil)
						
						mu.Lock()
						if err != nil {
							results[idx] = fmt.Sprintf(`{"clientId": %v, "error": "%v"}`, id, err)
						} else {
							results[idx] = string(respData)
						}
						mu.Unlock()
					}(i, idRaw)
				}
				wg.Wait()
				return mcp.NewToolResultText("[" + strings.Join(results, ",") + "]"), nil
			},
			Params: []ToolParam{
				{Name: "clientIds", Description: "Array of Client IDs", Type: "array", Required: true},
				{Name: "chargeId", Description: "ID of the charge to apply", Type: "number", Required: true},
				{Name: "amount", Description: "Optional amount override", Type: "number", Required: false},
				{Name: "dueDate", Description: "Optional due date", Type: "string", Required: false},
			},
		},
		{
			Name:        "bulk_make_repayments_native",
			Description: "Submit multiple parallel loan repayments. MISSION CRITICAL: Use the exact amount from the human prompt. Do NOT adjust based on outstanding balances.",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args := req.GetArguments()
				repaymentsRaw, ok := args["repayments"].([]any)
				if !ok {
					return mcp.NewToolResultError("repayments must be an array of objects"), nil
				}

				var wg sync.WaitGroup
				results := make([]string, len(repaymentsRaw))
				var mu sync.Mutex

				sem := make(chan struct{}, 10)
				today := time.Now().Format("2006-01-02")

				for i, repRaw := range repaymentsRaw {
					wg.Add(1)
					rep, ok := repRaw.(map[string]interface{})
					if !ok {
						results[i] = `{"error": "Invalid repayment object"}`
						wg.Done()
						continue
					}

					go func(idx int, p map[string]interface{}) {
						defer wg.Done()
						sem <- struct{}{}
						defer func() { <-sem }()

						loanId := p["loanId"]
						amountRaw := p["amount"]
						date := p["transactionDate"]

						if date == nil || date == "" {
							date = today
						}

						amount, err := FormatAmount(amountRaw)
						if err != nil {
							mu.Lock()
							results[idx] = fmt.Sprintf(`{"loanId": %v, "error": "Invalid amount: %v"}`, loanId, err)
							mu.Unlock()
							return
						}

						body := map[string]interface{}{
							"transactionDate": date,
							"transactionAmount": amount,
							"dateFormat":      "yyyy-MM-dd",
							"locale":          "en",
						}
						
						endpoint := fmt.Sprintf("loans/%v/transactions?command=repayment", loanId)
						respData, err := r.Fineract.DoRequest("POST", endpoint, body, nil)
						
						mu.Lock()
						if err != nil {
							results[idx] = fmt.Sprintf(`{"loanId": %v, "error": "%v"}`, loanId, err)
						} else {
							results[idx] = string(respData)
						}
						mu.Unlock()
					}(i, rep)
				}
				wg.Wait()
				return mcp.NewToolResultText("SUCCESS: Executed bulk_make_repayments_native. Results: [" + strings.Join(results, ",") + "]"), nil
			},
			Params: []ToolParam{
				{Name: "repayments", Description: "Array of objects. MISSION CRITICAL: Each 'amount' field MUST be the exact numeric value from the human prompt. IGNORE backend due amounts.", Type: "array", Required: true},
			},
		},
	}
}
