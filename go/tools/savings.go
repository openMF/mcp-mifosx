// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

func GetSavingsToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_savings_products", Description: "List Active Savings Products",
			Method: "GET", EndpointURL: "savingsproducts", Params: []ToolParam{},
		},
		{
			Name: "get_savings_account", Description: "Get details for a savings account",
			Method: "GET", EndpointURL: "savingsaccounts/%v", Params: []ToolParam{{Name: "savingsId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_savings_transactions", Description: "Get savings transactions",
			Method: "GET", EndpointURL: "savingsaccounts/%v/transactions", Params: []ToolParam{{Name: "savingsId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "create_savings_account", Description: "Create new savings application",
			Method: "POST", EndpointURL: "savingsaccounts", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "productId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "submittedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "approve_savings", Description: "Approve savings application",
			Method: "POST", EndpointURL: "savingsaccounts/%v?command=approve", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "approvedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "activate_savings", Description: "Activate savings account",
			Method: "POST", EndpointURL: "savingsaccounts/%v?command=activate", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "activatedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "deposit_savings", Description: "Deposit money into savings",
			Method: "POST", EndpointURL: "savingsaccounts/%v/transactions?command=deposit", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "transactionAmount", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "withdraw_savings", Description: "Withdraw money from savings",
			Method: "POST", EndpointURL: "savingsaccounts/%v/transactions?command=withdrawal", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "transactionAmount", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "apply_savings_charge", Description: "Apply penalty/charge to savings",
			Method: "POST", EndpointURL: "savingsaccounts/%v/charges", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
				{Name: "chargeId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "amount", Type: "number", Required: true, IsBodyVar: true},
				{Name: "dueDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "calculate_and_post_interest", Description: "Post interest to savings",
			Method: "POST", EndpointURL: "savingsaccounts/%v?command=calculateInterest", Params: []ToolParam{
				{Name: "savingsId", Type: "number", Required: true, IsPathVar: true},
			},
		},
	}
}
