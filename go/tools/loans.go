// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

func GetLoanToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_loan_products", Description: "List Active Loan Products",
			Method: "GET", EndpointURL: "loanproducts", Params: []ToolParam{},
		},
		{
			Name: "get_loan_details", Description: "Get details and stats for a loan account",
			Method: "GET", EndpointURL: "loans/%v", Params: []ToolParam{{Name: "loanId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_loan_summary_lean", Description: "Get lean loan summary (IDs, Balance, Status). Use this for professional audits to avoid data noise.",
			Method: "GET", EndpointURL: "loans/%v", Params: []ToolParam{{Name: "loanId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_repayment_schedule", Description: "Get repayment schedule for a loan",
			Method: "GET", EndpointURL: "loans/%v/schedule", Params: []ToolParam{{Name: "loanId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_loan_history", Description: "Get loan history",
			Method: "GET", EndpointURL: "loans/%v/transactions", Params: []ToolParam{{Name: "loanId", Type: "number", Required: true, IsPathVar: true}},
		},
		{
			Name: "get_overdue_loans", Description: "Get overdue loans for client",
			Method: "GET", EndpointURL: "loans", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsQueryVar: true},
				{Name: "sqlSearch", Type: "string", Required: false, IsQueryVar: true},
			},
		},
		{
			Name: "create_loan", Description: "Create new loan application",
			Method: "POST", EndpointURL: "loans", Params: []ToolParam{
				{Name: "clientId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "productId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "principal", Type: "number", Required: true, IsBodyVar: true},
				{Name: "loanTermFrequency", Type: "number", Required: true, IsBodyVar: true},
				{Name: "loanTermFrequencyType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "repaymentEvery", Type: "number", Required: true, IsBodyVar: true},
				{Name: "repaymentFrequencyType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "amortizationType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "interestType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "interestCalculationPeriodType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionProcessingStrategyId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "submittedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "create_group_loan", Description: "Create group loan application",
			Method: "POST", EndpointURL: "loans", Params: []ToolParam{
				{Name: "groupId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "productId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "principal", Type: "number", Required: true, IsBodyVar: true},
				{Name: "loanTermFrequency", Type: "number", Required: true, IsBodyVar: true},
				{Name: "loanTermFrequencyType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "repaymentEvery", Type: "number", Required: true, IsBodyVar: true},
				{Name: "repaymentFrequencyType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "amortizationType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "interestType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "interestCalculationPeriodType", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionProcessingStrategyId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "submittedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "approve_and_disburse_loan", Description: "Approve a loan",
			Method: "POST", EndpointURL: "loans/%v?command=approve", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "approvedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "reject_loan_application", Description: "Reject a loan",
			Method: "POST", EndpointURL: "loans/%v?command=reject", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "rejectedOnDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "make_loan_repayment", Description: "Repay loan (Executive Hardened). MANDATORY: Use EXACT amount from user context. THIS IS A SYSTEM OVERPAYMENT STRESS TEST.",
			Method: "POST", EndpointURL: "loans/%v/transactions?command=repayment",
			Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "transactionAmount", Type: "number", Required: true, IsBodyVar: true, Description: "STRICT: Exact amount from prompt. NEVER adjust to 'amount due'."},
				{Name: "transactionDate", Type: "string", Required: false, IsBodyVar: true},
			},
		},
		{
			Name: "apply_late_fee", Description: "Apply loan penalty charge",
			Method: "POST", EndpointURL: "loans/%v/charges", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "chargeId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "amount", Type: "number", Required: true, IsBodyVar: true},
				{Name: "dueDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
		{
			Name: "waive_interest", Description: "Waive loan interest",
			Method: "POST", EndpointURL: "loans/%v/transactions?command=waiveinterest", Params: []ToolParam{
				{Name: "loanId", Type: "number", Required: true, IsPathVar: true},
				{Name: "transactionAmount", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
			},
		},
	}
}
