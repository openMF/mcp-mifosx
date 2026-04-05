// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"fmt"
	"runtime"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
)

func (r *Registry) RegisterDiagnosticTools() {
	r.registerTools([]BaseToolDef{
		{
			Name:        "server_runtime_stats",
			Description: "Returns real-time Go runtime statistics for the MCP server (Memory, CPU, Goroutines).",
			Method:      "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				var m runtime.MemStats
				runtime.ReadMemStats(&m)

				stats := fmt.Sprintf("Memory Alloc: %v KB\nTotal Alloc: %v KB\nSys: %v MB\nNumGC: %v\nNumGoroutine: %v",
					m.Alloc/1024, m.TotalAlloc/1024, m.Sys/1024/1024, m.NumGC, runtime.NumGoroutine())

				return mcp.NewToolResultText(stats), nil
			},
		},
		{
			Name:        "check_fineract_latency",
			Description: "Measures the exact millisecond ping/latency to the Fineract banking backend.",
			Method:      "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				start := time.Now()
				_, err := r.Fineract.DoRequest("GET", "offices", nil, nil)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("Failed to reach Fineract: %v", err)), nil
				}
				duration := time.Since(start)

				return mcp.NewToolResultText(fmt.Sprintf("Mifos X Fineract Latency: %v", duration)), nil
			},
		},
	})
}
