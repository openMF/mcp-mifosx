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
	// Money, when set, executes every item through the durable idempotency
	// ledger with its own derived key.
	Money *MoneyMove
}

// bulkItemID is the resource id a bulk endpoint substitutes for %v.
func bulkItemID(item map[string]any) any {
	for _, field := range []string{"clientId", "loanId", "savingsId", "groupId"} {
		if value := item[field]; value != nil {
			return value
		}
	}
	return nil
}

// bulkItemBody is the Fineract payload for one item: the item without the
// control fields used to address or key it.
func bulkItemBody(item map[string]any) map[string]any {
	body := make(map[string]any, len(item))
	for key, value := range item {
		switch key {
		case "clientId", "loanId", "savingsId", "groupId", "idempotencyKey":
			continue
		}
		body[key] = value
	}
	return body
}

// firstValue returns the first non-nil value among the given keys.
func firstValue(item map[string]any, keys ...string) any {
	for _, key := range keys {
		if value := item[key]; value != nil {
			return value
		}
	}
	return nil
}

func repaymentItemPayload(item map[string]any) map[string]any {
	return map[string]any{
		"loan_id":         numericPayloadValue(item["loanId"]),
		"amount":          numericPayloadValue(firstValue(item, "amount", "transactionAmount")),
		"payment_type_id": numericPayloadValue(item["paymentTypeId"]),
	}
}

func repaymentItemReconciliation(item map[string]any) map[string]any {
	return map[string]any{
		"loan_id": numericPayloadValue(item["loanId"]),
		"amount":  numericPayloadValue(firstValue(item, "amount", "transactionAmount")),
	}
}

// bulkToolDefs is the catalogue of bulk tools. Money-moving entries carry the
// same operation names as their single-item counterparts, so per-item keys and
// reconciliation behave identically.
func bulkToolDefs() []BulkDef {
	return []BulkDef{
		{Name: "bulk_search_clients", Description: "Search multiple client names concurrently", Method: "GET", EndpointURL: "search"},
		{Name: "bulk_get_loan_status", Description: "Get loan details for multiple loans concurrently", Method: "GET", EndpointURL: "loans/%v"},
		{
			Name: "bulk_disburse_loans", Description: "Disburse multiple loans concurrently", Method: "POST",
			EndpointURL: "loans/%v?command=disburse", Money: &MoneyMove{
				Operation: "loan.disburse",
				Reconciliation: func(item map[string]any) map[string]any {
					return map[string]any{"loan_id": numericPayloadValue(item["loanId"])}
				},
				Reconcile: loanDisbursalReconcilerFor,
			},
		},
		{
			Name: "bulk_make_repayments", Description: "Make loop repayments concurrently", Method: "POST",
			EndpointURL: "loans/%v/transactions?command=repayment", Money: &MoneyMove{
				Operation:      "loan.repayment",
				ItemPayload:    repaymentItemPayload,
				Reconciliation: repaymentItemReconciliation,
				Reconcile:      loanTransactionsReconciler([]string{"repay"}, []string{".repayment"}, "loan.repayment"),
			},
		},
		{Name: "bulk_activate_clients", Description: "Activate multiple clients concurrently", Method: "POST", EndpointURL: "clients/%v?command=activate"},
		{Name: "bulk_get_savings_balances", Description: "Get savings details concurrently", Method: "GET", EndpointURL: "savingsaccounts/%v"},
		{
			Name: "bulk_apply_fees", Description: "Apply fees to multiple loans simultaneously", Method: "POST",
			EndpointURL: "loans/%v/charges", Money: &MoneyMove{
				Operation: "loan.charge",
				Reconciliation: func(item map[string]any) map[string]any {
					return map[string]any{
						"loan_id":   numericPayloadValue(item["loanId"]),
						"charge_id": numericPayloadValue(item["chargeId"]),
						"amount":    numericPayloadValue(item["amount"]),
					}
				},
				Reconcile: loanChargeReconciler(),
			},
		},
		{Name: "bulk_close_accounts", Description: "Close multiple savings accounts concurrently", Method: "POST", EndpointURL: "savingsaccounts/%v?command=close"},
		{Name: "bulk_create_savings_accounts", Description: "Create multiple savings accounts concurrently", Method: "POST", EndpointURL: "savingsaccounts"},
		{Name: "bulk_approve_and_activate_savings", Description: "Approve and activate multiple savings concurrently", Method: "POST", EndpointURL: "savingsaccounts/%v?command=approve"},
		{
			Name: "bulk_deposit_savings", Description: "Deposit into multiple savings concurrently", Method: "POST",
			EndpointURL: "savingsaccounts/%v/transactions?command=deposit", Money: &MoneyMove{
				Operation: "savings.deposit",
				Reconciliation: func(item map[string]any) map[string]any {
					return map[string]any{
						"account_id": numericPayloadValue(item["savingsId"]),
						"amount":     numericPayloadValue(firstValue(item, "transactionAmount", "amount")),
					}
				},
				Reconcile: savingsTransactionsReconciler([]string{"deposit"}, []string{".deposit"}, "savings.deposit"),
			},
		},
	}
}

func (r *Registry) RegisterBulkTools() {
	for _, d := range bulkToolDefs() {
		opts := []mcp.ToolOption{
			mcp.WithDescription(d.Description),
			mcp.WithString("requests", mcp.Required(), mcp.Description("A JSON array of parameter block objects corresponding to the single operation parameters.")),
		}
		if d.Money != nil {
			opts = append(opts, mcp.WithString("idempotencyKey", mcp.Description(
				"Parent key for this batch. Each item derives a stable key from it, so retrying the same "+
					"batch replays the items that already succeeded instead of paying them twice.")))
		}
		tool := mcp.NewTool(d.Name, opts...)

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

			if def.Money != nil {
				return r.runBulkMoney(ctx, def, payloadArray, stringValue(args["idempotencyKey"])), nil
			}

			var wg sync.WaitGroup
			results := make([]string, len(payloadArray))
			errorsFound := false
			var mu sync.Mutex

			for i, payload := range payloadArray {
				wg.Add(1)
				go func(idx int, p map[string]interface{}) {
					defer wg.Done()

					endpoint := def.EndpointURL
					queryParams := make(map[string]string)
					bodyParams := make(map[string]interface{})

					if strings.Contains(endpoint, "%v") {
						if idVal := bulkItemID(p); idVal != nil {
							endpoint = strings.Replace(endpoint, "%v", fmt.Sprintf("%v", idVal), 1)
						}
					}

					if def.Method == "GET" {
						for k, v := range p {
							queryParams[k] = fmt.Sprintf("%v", v)
						}
					} else {
						bodyParams = bulkItemBody(p)
					}

					respData, err := r.Fineract.DoRequest(def.Method, endpoint, bodyParams, queryParams)

					mu.Lock()
					defer mu.Unlock()
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

// runBulkMoney executes every item at most once per derived key and returns
// one deterministic record per item, in request order.
func (r *Registry) runBulkMoney(ctx context.Context, def BulkDef, items []map[string]any, parentKey string) *mcp.CallToolResult {
	results := make([]map[string]any, len(items))
	if ctx.Err() != nil {
		return mcp.NewToolResultError(ctx.Err().Error())
	}

	var wg sync.WaitGroup
	sem := make(chan struct{}, 10)
	for index, item := range items {
		wg.Add(1)
		go func(idx int, p map[string]any) {
			defer wg.Done()
			sem <- struct{}{}
			defer func() { <-sem }()

			endpoint := def.EndpointURL
			if strings.Contains(endpoint, "%v") {
				if idVal := bulkItemID(p); idVal != nil {
					endpoint = strings.Replace(endpoint, "%v", fmt.Sprintf("%v", idVal), 1)
				}
			}

			key := stringValue(p["idempotencyKey"])
			if key == "" && parentKey != "" {
				payload := p
				if def.Money.ItemPayload != nil {
					payload = def.Money.ItemPayload(p)
				}
				key = bulkMoneyItemKey(parentKey, def.Money.Operation, idx, payload)
			}
			results[idx] = r.executeBulkItem(ctx, def.Money, def.Method, endpoint, bulkItemBody(p), p, idx, key)
		}(index, item)
	}
	wg.Wait()

	encoded, err := json.Marshal(results)
	if err != nil {
		return mcp.NewToolResultError("could not encode bulk results")
	}
	for _, item := range results {
		if item["status"] != "succeeded" {
			return mcp.NewToolResultError("SUCCESS: Executed " + def.Name + ". Bulk execution completed with some errors: " + string(encoded))
		}
	}
	return mcp.NewToolResultText("SUCCESS: Executed " + def.Name + ". Result: " + string(encoded))
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

				move := &MoneyMove{
					Operation:          "loan.repayment",
					ItemPayload:        repaymentItemPayload,
					Reconciliation:     repaymentItemReconciliation,
					ItemReconciliation: repaymentItemReconciliation,
					Reconcile:          loanTransactionsReconciler([]string{"repay"}, []string{".repayment"}, "loan.repayment"),
				}
				parentKey := stringValue(args["idempotencyKey"])
				today := time.Now().Format("2006-01-02")

				results := make([]map[string]any, len(repaymentsRaw))
				var wg sync.WaitGroup
				sem := make(chan struct{}, 10)

				for i, repRaw := range repaymentsRaw {
					rep, ok := repRaw.(map[string]any)
					if !ok {
						results[i] = map[string]any{
							"index": i, "status": "rejected",
							"error": "Invalid repayment object",
						}
						continue
					}

					wg.Add(1)
					go func(idx int, p map[string]any) {
						defer wg.Done()
						sem <- struct{}{}
						defer func() { <-sem }()

						amount, err := FormatAmount(p["amount"])
						if err != nil {
							results[idx] = map[string]any{
								"index": idx, "status": "rejected",
								"error": fmt.Sprintf("Invalid amount: %v", err),
							}
							return
						}

						date := stringValue(p["transactionDate"])
						if date == "" {
							date = today
						}
						body := map[string]any{
							"transactionDate":   date,
							"transactionAmount": amount,
							"dateFormat":        "yyyy-MM-dd",
							"locale":            "en",
						}

						key := stringValue(p["idempotencyKey"])
						if key == "" && parentKey != "" {
							key = bulkMoneyItemKey(parentKey, move.Operation, idx, repaymentItemPayload(p))
						}

						endpoint := fmt.Sprintf("loans/%v/transactions?command=repayment", p["loanId"])
						results[idx] = r.executeBulkItem(ctx, move, "POST", endpoint, body, p, idx, key)
					}(i, rep)
				}
				wg.Wait()

				encoded, err := json.Marshal(results)
				if err != nil {
					return mcp.NewToolResultError("could not encode bulk results"), nil
				}
				for _, item := range results {
					if item["status"] != "succeeded" {
						return mcp.NewToolResultError("SUCCESS: Executed bulk_make_repayments_native. Bulk execution completed with some errors: " + string(encoded)), nil
					}
				}
				return mcp.NewToolResultText("SUCCESS: Executed bulk_make_repayments_native. Results: " + string(encoded)), nil
			},
			Params: []ToolParam{
				{Name: "repayments", Description: "Array of objects. MISSION CRITICAL: Each 'amount' field MUST be the exact numeric value from the human prompt. IGNORE backend due amounts.", Type: "array", Required: true},
				{Name: "idempotencyKey", Description: "Parent key for this batch; every item derives a stable key from it so a retry cannot pay twice.", Type: "string", Required: false},
			},
		},
	}
}
