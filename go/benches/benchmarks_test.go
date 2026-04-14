// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

package benches

import (
	"encoding/json"
	"fmt"
	"testing"
	"time"

	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"

	mcpserver "github.com/mark3labs/mcp-go/server"
)

func newTestRegistry() *tools.Registry {
	appServer := mcpserver.NewMCPServer("Bench-MCP-Go", "0.0.1")
	fc := &adapter.FineractClient{}
	r := &tools.Registry{
		Server:   appServer,
		Fineract: fc,
	}
	r.RegisterAllTools()
	return r
}

func BenchmarkRegistryInit(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = newTestRegistry()
	}
}

func BenchmarkGetClientToolDefs(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = tools.GetClientToolDefs()
	}
}

func BenchmarkGetLoanToolDefs(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = tools.GetLoanToolDefs()
	}
}

func BenchmarkGetAllToolDefs(b *testing.B) {
	for i := 0; i < b.N; i++ {
		var all []tools.BaseToolDef
		all = append(all, tools.GetClientToolDefs()...)
		all = append(all, tools.GetLoanToolDefs()...)
		all = append(all, tools.GetSavingsToolDefs()...)
		_ = all
	}
}

func BenchmarkToolLookupByName(b *testing.B) {
	r := newTestRegistry()
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		_ = r.ToolDefs["make_loan_repayment"]
	}
}

func BenchmarkJSONSerializeSmall(b *testing.B) {
	payload := map[string]interface{}{
		"id":     1,
		"name":   "Test Client",
		"status": "active",
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		data, err := json.Marshal(payload)
		if err != nil {
			b.Fatalf("marshal payload: %v", err)
		}
		_ = data
	}
}

func BenchmarkJSONSerializeLarge(b *testing.B) {
	payload := map[string]interface{}{
		"id":              42,
		"accountNo":       "000000042",
		"loanProductName": "Short Term Loan",
		"status": map[string]interface{}{
			"id": 300, "code": "loanStatusType.active", "value": "Active",
		},
		"loanType": map[string]interface{}{
			"id": 1, "code": "loanType.individual", "value": "Individual",
		},
		"principal":         10000.0,
		"approvedPrincipal": 10000.0,
		"annualInterestRate": 5.0,
		"numberOfRepayments": 12,
		"repaymentEvery":     1,
		"repaymentFrequencyType": map[string]interface{}{
			"id": 2, "code": "repaymentFrequency.periodFrequencyType.months", "value": "Months",
		},
		"summary": map[string]interface{}{
			"principalPaid":        5000.0,
			"principalOutstanding": 5000.0,
			"interestPaid":         250.0,
			"interestOutstanding":  250.0,
			"totalOutstanding":     5250.0,
			"totalOverdue":         0.0,
		},
		"timeline": map[string]interface{}{
			"submittedOnDate":      []int{2026, 1, 15},
			"approvedOnDate":       []int{2026, 1, 16},
			"actualDisbursementDate": []int{2026, 1, 17},
			"expectedMaturityDate":   []int{2027, 1, 17},
		},
	}
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		data, err := json.Marshal(payload)
		if err != nil {
			b.Fatalf("marshal payload: %v", err)
		}
		_ = data
	}
}

func BenchmarkJSONDeserializeLarge(b *testing.B) {
	raw := []byte(`{
		"id":42,"accountNo":"000000042","loanProductName":"Short Term Loan",
		"status":{"id":300,"code":"loanStatusType.active","value":"Active"},
		"principal":10000.0,"approvedPrincipal":10000.0,"annualInterestRate":5.0,
		"numberOfRepayments":12,"repaymentEvery":1,
		"summary":{"principalPaid":5000.0,"principalOutstanding":5000.0,
		"interestPaid":250.0,"interestOutstanding":250.0,
		"totalOutstanding":5250.0,"totalOverdue":0.0}
	}`)
	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		var v interface{}
		if err := json.Unmarshal(raw, &v); err != nil {
			b.Fatalf("unmarshal payload: %v", err)
		}
	}
}

func BenchmarkTodayFormatting(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = time.Now().Format("2006-01-02")
	}
}

func BenchmarkFormatAmountFloat(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_, _ = tools.FormatAmount(12345.678)
	}
}

func BenchmarkFormatAmountString(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_, _ = tools.FormatAmount("12345.678")
	}
}

func BenchmarkErrorFormatting(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_ = fmt.Errorf("Fineract Error: Loan not found: %w", fmt.Errorf("API Error 404"))
	}
}

func BenchmarkEndpointPathSubstitution(b *testing.B) {
	template := "loans/%v/transactions?command=repayment"
	for i := 0; i < b.N; i++ {
		_ = fmt.Sprintf(template, 42)
	}
}
