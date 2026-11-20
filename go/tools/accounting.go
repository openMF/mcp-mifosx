// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import "strings"

func GetAccountingToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "list_gl_accounts", Description: "List General Ledger accounts",
			Method: "GET", EndpointURL: "glaccounts", Params: []ToolParam{},
		},
		{
			Name: "get_journal_entries", Description: "Get journal entries",
			Method: "GET", EndpointURL: "journalentries", Params: []ToolParam{},
		},
		{
			Name: "create_journal_entry", Description: "Create a manual GL entry",
			Method: "POST", EndpointURL: "journalentries", Params: []ToolParam{
				{Name: "officeId", Type: "number", Required: true, IsBodyVar: true},
				{Name: "transactionDate", Type: "string", Required: true, IsBodyVar: true},
				{Name: "currencyCode", Type: "string", Required: true, IsBodyVar: true},
				{Name: "dateFormat", Type: "string", Required: true, IsBodyVar: true},
				{Name: "locale", Type: "string", Required: true, IsBodyVar: true},
				{Name: "credits", Type: "string", Required: true, IsBodyVar: true},
				{Name: "debits", Type: "string", Required: true, IsBodyVar: true},
				{Name: "comment", Type: "string", Required: false, IsBodyVar: true},
				idempotencyKeyParam(),
			},
			MoneyMove: &MoneyMove{
				Operation: "journal.entry",
				Payload: func(args map[string]any) map[string]any {
					// Mirrors python/tools/domains/accounting.py, where the
					// entries are already structured and the currency is
					// trimmed, so the same request fingerprints identically.
					return map[string]any{
						"office_id":     numericPayloadValue(args["officeId"]),
						"date":          args["transactionDate"],
						"currency_code": strings.TrimSpace(stringValue(args["currencyCode"])),
						"credits":       jsonArrayValue(args["credits"]),
						"debits":        jsonArrayValue(args["debits"]),
						"comment":       stringValue(args["comment"]),
					}
				},
				Reconciliation: func(args map[string]any) map[string]any {
					return map[string]any{"office_id": numericPayloadValue(args["officeId"])}
				},
				// Fineract's journal endpoint is queried by GL account or by the
				// transaction id it returns; a lost response leaves neither, so a
				// manual entry cannot be reconciled and stays UNKNOWN.
				Reconcile: nil,
			},
		},
	}
}
