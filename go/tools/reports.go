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

	"github.com/mark3labs/mcp-go/mcp"
)

// GetReportToolDefs returns the standard REST-based report tools.
func GetReportToolDefs() []BaseToolDef {
	return []BaseToolDef{
		{
			Name: "get_reports", Description: "Get a list of all available reports in Fineract",
			Method: "GET", EndpointURL: "reports", Params: []ToolParam{},
		},
		{
			Name: "create_report", Description: "Create a new non-core report definition",
			Method: "POST", EndpointURL: "reports", Params: []ToolParam{
				{Name: "reportName", Type: "string", Required: true, IsBodyVar: true},
				{Name: "reportType", Type: "string", Required: true, IsBodyVar: true},
				{Name: "reportSubType", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportCategory", Type: "string", Required: false, IsBodyVar: true},
				{Name: "description", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportSql", Type: "string", Required: false, IsBodyVar: true},
			},
		},
		{
			Name: "get_report_template", Description: "Retrieve the template for creating a report (lists allowed types, subtypes, etc.)",
			Method: "GET", EndpointURL: "reports/template", Params: []ToolParam{},
		},
		{
			Name: "get_report", Description: "Retrieve a specific report definition by ID",
			Method: "GET", EndpointURL: "reports/%v", Params: []ToolParam{
				{Name: "id", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "update_report", Description: "Update an existing non-core report definition",
			Method: "PUT", EndpointURL: "reports/%v", Params: []ToolParam{
				{Name: "id", Type: "number", Required: true, IsPathVar: true},
				{Name: "reportName", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportType", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportSubType", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportCategory", Type: "string", Required: false, IsBodyVar: true},
				{Name: "description", Type: "string", Required: false, IsBodyVar: true},
				{Name: "reportSql", Type: "string", Required: false, IsBodyVar: true},
			},
		},
		{
			Name: "delete_report", Description: "Delete a non-core report by ID",
			Method: "DELETE", EndpointURL: "reports/%v", Params: []ToolParam{
				{Name: "id", Type: "number", Required: true, IsPathVar: true},
			},
		},
		{
			Name: "run_report", Description: "Run a Fineract report by its report name. Standard parameters start with R_ (e.g. R_officeId)",
			Method: "GET", EndpointURL: "runreports/%v", Params: []ToolParam{
				{Name: "reportName", Type: "string", Required: true, IsPathVar: true},
				{Name: "exportCSV", Type: "boolean", Required: false, IsQueryVar: true},
				{Name: "R_officeId", Type: "number", Required: false, IsQueryVar: true},
				{Name: "R_clientId", Type: "number", Required: false, IsQueryVar: true},
				{Name: "R_loanId", Type: "number", Required: false, IsQueryVar: true},
				{Name: "R_startDate", Type: "string", Required: false, IsQueryVar: true},
				{Name: "R_endDate", Type: "string", Required: false, IsQueryVar: true},
				{Name: "genericResultSet", Type: "boolean", Required: false, IsQueryVar: true},
			},
		},
	}
}

// RegisterNativeReportTools registers Go-native report tools with server-side filtering.
func (r *Registry) RegisterNativeReportTools() {
	r.registerTools([]BaseToolDef{
		{
			Name:        "search_report_by_name",
			Description: "Search for a report by name. Fetches all reports server-side and returns only matches. Use this instead of get_reports when looking for a specific report.",
			Params: []ToolParam{
				{Name: "reportName", Required: true, Description: "Full or partial name of the report to find", Type: "string"},
			},
			Method: "GET",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				args, ok := req.Params.Arguments.(map[string]interface{})
				if !ok {
					return mcp.NewToolResultError("Arguments must be a JSON object"), nil
				}
				searchName, _ := args["reportName"].(string)
				searchName = strings.ToLower(strings.TrimSpace(searchName))

				data, err := r.Fineract.DoRequest("GET", "reports", nil, nil)
				if err != nil {
					return mcp.NewToolResultError(fmt.Sprintf("Failed to fetch reports: %v", err)), nil
				}

				var allReports []map[string]interface{}
				if err := json.Unmarshal(data, &allReports); err != nil {
					return mcp.NewToolResultError("Failed to parse reports list"), nil
				}

				var matches []map[string]interface{}
				for _, report := range allReports {
					name, _ := report["reportName"].(string)
					if strings.Contains(strings.ToLower(name), searchName) {
						matches = append(matches, map[string]interface{}{
							"id":             report["id"],
							"reportName":     report["reportName"],
							"reportType":     report["reportType"],
							"reportCategory": report["reportCategory"],
							"coreReport":     report["coreReport"],
						})
					}
				}

				if len(matches) == 0 {
					return mcp.NewToolResultText(fmt.Sprintf("No reports found matching '%s'", searchName)), nil
				}

				resp, _ := json.MarshalIndent(matches, "", "  ")
				return mcp.NewToolResultText(string(resp)), nil
			},
		},
	})
}
