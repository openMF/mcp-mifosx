// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/prometheus/client_golang/prometheus"
)

var (
	McpToolCalls = prometheus.NewCounter(prometheus.CounterOpts{
		Name: "mcp_tool_calls_total",
		Help: "Total number of MCP tool calls processed by the Go server.",
	})
)

func init() {
	prometheus.MustRegister(McpToolCalls)
}

type BaseToolDef struct {
	Name        string
	Description string
	Method      string
	EndpointURL string
	Params      []ToolParam
	Handler     func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error)
}

type ToolParam struct {
	Name        string
	Description string
	Type        string
	Required    bool
	IsPathVar   bool
	IsQueryVar  bool
	IsBodyVar   bool
}

type Registry struct {
	Server   *server.MCPServer
	Fineract *adapter.FineractClient
	ToolDefs map[string]BaseToolDef
}

func (r *Registry) registerTools(defs []BaseToolDef) {
	if r.ToolDefs == nil {
		r.ToolDefs = make(map[string]BaseToolDef)
	}
	for _, def := range defs {
		r.ToolDefs[def.Name] = def
		opts := []mcp.ToolOption{mcp.WithDescription(def.Description)}
		for _, p := range def.Params {
			var propOpts []mcp.PropertyOption
			if p.Description != "" {
				propOpts = append(propOpts, mcp.Description(p.Description))
			}
			if p.Required {
				propOpts = append(propOpts, mcp.Required())
			}

			if p.Type == "string" {
				opts = append(opts, mcp.WithString(p.Name, propOpts...))
			} else if p.Type == "number" {
				opts = append(opts, mcp.WithNumber(p.Name, propOpts...))
			} else if p.Type == "boolean" {
				opts = append(opts, mcp.WithBoolean(p.Name, propOpts...))
			} else if p.Type == "array" {
				if strings.Contains(strings.ToLower(p.Name), "name") || strings.Contains(strings.ToLower(p.Name), "query") {
					opts = append(opts, mcp.WithArray(p.Name, append(propOpts, mcp.WithStringItems())...))
				} else {
					opts = append(opts, mcp.WithArray(p.Name, append(propOpts, mcp.WithNumberItems())...))
				}
			}
		}

		tool := mcp.NewTool(def.Name, opts...)
		d := def
		handler := func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
			McpToolCalls.Inc()
			if d.Handler != nil {
				return d.Handler(ctx, req)
			}

			endpoint := d.EndpointURL
			body := make(map[string]interface{})
			queryParams := make(map[string]string)

			args, ok := req.Params.Arguments.(map[string]interface{})
			if !ok {
				return mcp.NewToolResultError("Arguments must be a JSON object"), nil
			}

			for _, p := range d.Params {
				val, ok := args[p.Name]
				if !ok {
					if p.Name == "transactionDate" {
						val = time.Now().Format("2006-01-02")
					} else if p.Name == "dateFormat" {
						val = "yyyy-MM-dd"
					} else if p.Name == "locale" {
						val = "en"
					} else {
						continue
					}
				}

				if p.Name == "transactionAmount" || p.Name == "amount" {
					hardened, err := FormatAmount(val)
					if err == nil {
						val = hardened
					}
				}

				if p.IsPathVar {
					endpoint = strings.Replace(endpoint, "%v", fmt.Sprintf("%v", val), 1)
				}
				if p.IsQueryVar {
					queryParams[p.Name] = fmt.Sprintf("%v", val)
				}
				if p.IsBodyVar {
					body[p.Name] = val
				}
			}

			hasDate := false
			dateFields := []string{"transactionDate", "submittedOnDate", "approvedOnDate", "activationDate", "disbursementDate", "dueDate", "closedOnDate"}
			for _, df := range dateFields {
				if _, ok := body[df]; ok {
					hasDate = true
					break
				}
			}

			if hasDate {
				if _, ok := body["dateFormat"]; !ok {
					body["dateFormat"] = "yyyy-MM-dd"
				}
				if _, ok := body["locale"]; !ok {
					body["locale"] = "en"
				}
			}

			respData, err := r.Fineract.DoRequest(d.Method, endpoint, body, queryParams)
			if err != nil {
				return mcp.NewToolResultError(fmt.Sprintf("%v\nDetails: %s", err, string(respData))), nil
			}

			msgPrefix := fmt.Sprintf("SUCCESS: Executed %s.", d.Name)
			if lId, ok := body["loanId"]; ok {
				msgPrefix += fmt.Sprintf(" Loan ID: %v.", lId)
			} else if lId, ok := args["loanId"]; ok {
				msgPrefix += fmt.Sprintf(" Loan ID: %v.", lId)
			}
			if cId, ok := body["clientId"]; ok {
				msgPrefix += fmt.Sprintf(" Client ID: %v.", cId)
			} else if cId, ok := args["clientId"]; ok {
				msgPrefix += fmt.Sprintf(" Client ID: %v.", cId)
			}
			if sId, ok := body["savingsId"]; ok {
				msgPrefix += fmt.Sprintf(" Savings ID: %v.", sId)
			} else if sId, ok := args["savingsId"]; ok {
				msgPrefix += fmt.Sprintf(" Savings ID: %v.", sId)
			}

			return mcp.NewToolResultText(fmt.Sprintf("%s Result: %s", msgPrefix, string(respData))), nil
		}

		r.Server.AddTool(tool, handler)
	}
}

func (r *Registry) RegisterAllTools() {
	r.RegisterClientTools()
	r.RegisterGroupTools()
	r.RegisterSavingsTools()
	r.RegisterLoanTools()
	r.RegisterStaffTools()
	r.RegisterAccountingTools()
	r.RegisterDocumentTools()
	r.RegisterNativeDocumentTools()
	r.RegisterReportTools()
	r.RegisterNativeReportTools()
	r.RegisterIdentityTools()
	r.RegisterNativeIdentityTools()
	r.RegisterBulkTools()
	r.RegisterNativeBulkTools()
	r.RegisterDiagnosticTools()
	r.RegisterCompositeTools()
}

func (r *Registry) RegisterClientTools()     { r.registerTools(GetClientToolDefs()) }
func (r *Registry) RegisterGroupTools()      { r.registerTools(GetGroupToolDefs()) }
func (r *Registry) RegisterSavingsTools()    { r.registerTools(GetSavingsToolDefs()) }
func (r *Registry) RegisterLoanTools()       { r.registerTools(GetLoanToolDefs()) }
func (r *Registry) RegisterStaffTools()      { r.registerTools(GetStaffToolDefs()) }
func (r *Registry) RegisterAccountingTools() { r.registerTools(GetAccountingToolDefs()) }
func (r *Registry) RegisterDocumentTools()   { r.registerTools(GetDocumentToolDefs()) }
func (r *Registry) RegisterReportTools()     { r.registerTools(GetReportToolDefs()) }
func (r *Registry) RegisterIdentityTools()   { r.registerTools(GetIdentityToolDefs()) }
