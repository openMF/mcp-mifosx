// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
)

// Every bulk call is bounded twice: at most MCP_BULK_MAX_CONCURRENCY Fineract
// requests are in flight, and a batch may not exceed MCP_BULK_MAX_BATCH_SIZE
// items. Each item produces exactly one structured result, in input order, even
// when the batch is cancelled part-way through.
const (
	defaultBulkConcurrency  = 8
	defaultBulkMaxBatchSize = 100
	bulkConcurrencyEnv      = "MCP_BULK_MAX_CONCURRENCY"
	bulkMaxBatchSizeEnv     = "MCP_BULK_MAX_BATCH_SIZE"
)

// Status values reported for a single item.
const (
	bulkItemSuccess   = "success"
	bulkItemError     = "error"
	bulkItemCancelled = "cancelled"
)

// Status values reported for the whole batch.
const (
	bulkStatusSuccess   = "success"
	bulkStatusPartial   = "partial_failure"
	bulkStatusFailure   = "failure"
	bulkStatusCancelled = "cancelled"
)

// bulkLimits are the per-call bounds read from the environment.
type bulkLimits struct {
	Concurrency  int
	MaxBatchSize int
}

// loadBulkLimits reads the bounds for one call. Invalid or non-positive values
// fall back to the defaults rather than disabling the protection.
func loadBulkLimits() bulkLimits {
	return bulkLimits{
		Concurrency:  envPositiveInt(bulkConcurrencyEnv, defaultBulkConcurrency),
		MaxBatchSize: envPositiveInt(bulkMaxBatchSizeEnv, defaultBulkMaxBatchSize),
	}
}

func envPositiveInt(name string, fallback int) int {
	raw := strings.TrimSpace(os.Getenv(name))
	if raw == "" {
		return fallback
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1 {
		return fallback
	}
	return value
}

// bulkItemResult is one item's outcome. Index is the item's position in the
// request, so a client can match results to inputs; Result carries Fineract's
// response verbatim when one was received.
type bulkItemResult struct {
	Index      int    `json:"index"`
	ID         any    `json:"id,omitempty"`
	Status     string `json:"status"`
	HTTPStatus int    `json:"http_status,omitempty"`
	Error      string `json:"error,omitempty"`
	Result     any    `json:"result,omitempty"`
}

// bulkReport is the response envelope for every bulk tool.
type bulkReport struct {
	Tool      string           `json:"tool"`
	Status    string           `json:"status"`
	Total     int              `json:"total"`
	Succeeded int              `json:"succeeded"`
	Failed    int              `json:"failed"`
	Cancelled int              `json:"cancelled"`
	Results   []bulkItemResult `json:"results"`
	Message   string           `json:"message,omitempty"`
}

// bulkJob is one validated bulk item, ready to send.
type bulkJob struct {
	Index    int
	ID       any
	IDKey    string
	Method   string
	Endpoint string
	Query    map[string]string
	Body     map[string]any
	// Err marks an item that cannot be sent (invalid input). It still gets a
	// deterministic result slot.
	Err error
}

type BulkDef struct {
	Name        string
	Description string
	Method      string
	EndpointURL string
}

// RegisterBulkTools registers the JSON-batch tools directly on the MCP server.
// They deliberately stay out of Registry.ToolDefs: the caller-scoped HTTP
// catalog exposes only its own tools and has no configured Fineract identity.
func (r *Registry) RegisterBulkTools() {
	for _, def := range r.GetBulkToolDefs() {
		tool := mcp.NewTool(def.Name,
			mcp.WithDescription(def.Description),
			mcp.WithString("requests", mcp.Required(), mcp.Description("A JSON array of parameter block objects corresponding to the single operation parameters.")),
		)
		r.Server.AddTool(tool, def.Handler)
	}
}

// GetBulkToolDefs builds the eleven tools that accept a stringified JSON array
// under "requests". It mirrors GetNativeBulkToolDefs so handlers can be
// exercised without an MCP transport.
func (r *Registry) GetBulkToolDefs() []BaseToolDef {
	specs := []BulkDef{
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

	defs := make([]BaseToolDef, 0, len(specs))
	for _, spec := range specs {
		def := spec
		defs = append(defs, BaseToolDef{
			Name:        def.Name,
			Description: def.Description,
			Method:      def.Method,
			EndpointURL: def.EndpointURL,
			Params:      []ToolParam{{Name: "requests", Type: "string", Required: true}},
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				return r.handleBulk(ctx, req, def)
			},
		})
	}
	return defs
}

// handleBulk validates the whole batch before any request is sent.
func (r *Registry) handleBulk(ctx context.Context, req mcp.CallToolRequest, def BulkDef) (*mcp.CallToolResult, error) {
	args := req.GetArguments()
	requestsRaw, ok := args["requests"].(string)
	if !ok {
		return mcp.NewToolResultError("Missing 'requests' parameter as stringified JSON array"), nil
	}

	var payloads []map[string]interface{}
	if err := json.Unmarshal([]byte(requestsRaw), &payloads); err != nil {
		return mcp.NewToolResultError("Failed to parse 'requests' array JSON: " + err.Error()), nil
	}

	limits := loadBulkLimits()
	if err := validateBulkBatch(len(payloads), limits); err != nil {
		return mcp.NewToolResultError(err.Error()), nil
	}

	jobs := make([]bulkJob, len(payloads))
	for i, payload := range payloads {
		jobs[i] = buildBulkJob(def, i, payload)
	}
	if err := rejectDuplicateBulkIDs(def.Name, def.Method == "POST", jobs); err != nil {
		return mcp.NewToolResultError(err.Error()), nil
	}
	return bulkCallResult(r.executeBulk(ctx, def.Name, jobs, limits)), nil
}

// buildBulkJob maps one request object onto a single-operation call. The path
// placeholder is filled from the first identifier present, matching the single
// operation parameter names; the identifier itself is never sent in the body.
func buildBulkJob(def BulkDef, index int, payload map[string]interface{}) bulkJob {
	job := bulkJob{Index: index, Method: def.Method, Endpoint: def.EndpointURL}

	for _, key := range []string{"clientId", "loanId", "savingsId", "groupId"} {
		if value, ok := payload[key]; ok && value != nil {
			job.ID, job.IDKey = value, key
			break
		}
	}

	if strings.Contains(job.Endpoint, "%v") {
		if job.ID == nil {
			job.Err = fmt.Errorf("%s requires one of clientId, loanId, savingsId or groupId", def.Name)
			return job
		}
		path, err := bulkIdentifier(job.ID)
		if err != nil {
			job.Err = fmt.Errorf("%s: invalid %s: %v", def.Name, job.IDKey, err)
			return job
		}
		job.Endpoint = strings.Replace(job.Endpoint, "%v", path, 1)
	} else {
		// The identifier fields are body data here (clientId when opening an
		// account, for example), not a batch target, so repeats are legitimate
		// and must not be treated as duplicate writes.
		job.IDKey = ""
	}

	if def.Method == "GET" {
		job.Query = make(map[string]string, len(payload))
		for key, value := range payload {
			job.Query[key] = fmt.Sprintf("%v", value)
		}
		return job
	}

	job.Body = make(map[string]interface{}, len(payload))
	for key, value := range payload {
		// Identifier fields consumed by a %v target are not repeated in the
		// body. A tool without a %v target has no such target, so its
		// identifier fields are ordinary body data and must be kept.
		if job.IDKey != "" {
			switch key {
			case "clientId", "loanId", "savingsId", "groupId":
				continue
			}
		}
		job.Body[key] = value
	}
	return job
}

// validateBulkBatch rejects a batch that can never be executed, before any work
// starts.
func validateBulkBatch(size int, limits bulkLimits) error {
	if size == 0 {
		return fmt.Errorf("requests must contain at least one operation")
	}
	if size > limits.MaxBatchSize {
		return fmt.Errorf("batch contains %d operations but the maximum is %d; split the batch or raise %s", size, limits.MaxBatchSize, bulkMaxBatchSizeEnv)
	}
	return nil
}

// bulkIdentifier validates one resource identifier before it is placed in an
// endpoint. JSON numbers arrive as float64; direct callers and json.Number are
// accepted too, and integral strings are normalised to their numeric form.
// Null, empty, fractional, non-positive, and unsupported values are rejected so
// an endpoint is never built from malformed input.
func bulkIdentifier(value any) (string, error) {
	switch v := value.(type) {
	case nil:
		return "", errors.New("identifier must not be null")
	case float64:
		return bulkFloatIdentifier(v)
	case float32:
		return bulkFloatIdentifier(float64(v))
	case int:
		return bulkIntegerIdentifier(int64(v))
	case int8:
		return bulkIntegerIdentifier(int64(v))
	case int16:
		return bulkIntegerIdentifier(int64(v))
	case int32:
		return bulkIntegerIdentifier(int64(v))
	case int64:
		return bulkIntegerIdentifier(v)
	case json.Number:
		parsed, err := v.Int64()
		if err != nil {
			return "", fmt.Errorf("identifier %q must be a whole number", v.String())
		}
		return bulkIntegerIdentifier(parsed)
	case string:
		trimmed := strings.TrimSpace(v)
		if trimmed == "" {
			return "", errors.New("identifier must not be empty")
		}
		parsed, err := strconv.ParseInt(trimmed, 10, 64)
		if err != nil {
			return "", fmt.Errorf("identifier %q must be a whole number", v)
		}
		return bulkIntegerIdentifier(parsed)
	default:
		return "", fmt.Errorf("unsupported identifier type %T", value)
	}
}

const bulkMaxIdentifier = int64(^uint64(0) >> 1)

func bulkIntegerIdentifier(value int64) (string, error) {
	if value < 1 {
		return "", fmt.Errorf("identifier must be a positive whole number, got %d", value)
	}
	return strconv.FormatInt(value, 10), nil
}

func bulkFloatIdentifier(value float64) (string, error) {
	if math.IsNaN(value) || math.IsInf(value, 0) || value != math.Trunc(value) {
		return "", fmt.Errorf("identifier must be a whole number, got %v", value)
	}
	if value < 1 || value >= float64(bulkMaxIdentifier) {
		return "", fmt.Errorf("identifier must be a positive whole number, got %v", value)
	}
	return bulkIntegerIdentifier(int64(value))
}

// bulkNonEmptyString validates a textual native parameter such as a search
// query.
func bulkNonEmptyString(value any, field string) (string, error) {
	text, ok := value.(string)
	if !ok {
		return "", fmt.Errorf("%s must be a string, got %T", field, value)
	}
	text = strings.TrimSpace(text)
	if text == "" {
		return "", fmt.Errorf("%s must not be empty", field)
	}
	return text, nil
}

// bulkRepaymentDate keeps the established "today" default and requires any
// explicit date to match the dateFormat the request advertises.
func bulkRepaymentDate(value any, today string) (string, error) {
	if value == nil {
		return today, nil
	}
	text, ok := value.(string)
	if !ok {
		return "", fmt.Errorf("transactionDate must be a string, got %T", value)
	}
	text = strings.TrimSpace(text)
	if text == "" {
		return today, nil
	}
	if _, err := time.Parse("2006-01-02", text); err != nil {
		return "", fmt.Errorf("transactionDate %q must use yyyy-MM-dd format", text)
	}
	return text, nil
}

// bulkRepaymentAmount keeps FormatAmount's exact normalised value while
// rejecting amounts that cannot fund a repayment.
func bulkRepaymentAmount(amount string) error {
	value, err := strconv.ParseFloat(amount, 64)
	if err != nil {
		return fmt.Errorf("invalid amount: %v", err)
	}
	if math.IsNaN(value) || math.IsInf(value, 0) || value <= 0 {
		return fmt.Errorf("amount must be a positive number, got %s", amount)
	}
	return nil
}

// bulkTargetRef identifies one write target inside a batch. Kind is the
// identifier parameter name (loanId, clientId, ...), so equal numeric values for
// different resource types cannot collide. Type records the Go type for values
// that cannot be canonicalised.
type bulkTargetRef struct {
	Kind  string
	Type  string
	Value string
}

// bulkTargetReference returns the comparable identity of a job's target. Valid
// identifiers are compared by their canonical path value, so int, float64, and
// numeric-string spellings of the same id match; invalid values fall back to a
// type-qualified rendering instead of bare fmt.Sprintf("%v", id).
func bulkTargetReference(job bulkJob) (bulkTargetRef, bool) {
	if job.ID == nil || job.IDKey == "" {
		return bulkTargetRef{}, false
	}
	if canonical, err := bulkIdentifier(job.ID); err == nil {
		return bulkTargetRef{Kind: job.IDKey, Type: "id", Value: canonical}, true
	}
	return bulkTargetRef{Kind: job.IDKey, Type: fmt.Sprintf("%T", job.ID), Value: fmt.Sprintf("%v", job.ID)}, true
}

// bulkAllowsRepeatedTargets reports whether one batch may legitimately mention
// the same resource more than once. Repayments are separate financial events
// against one loan, so they must not be treated as duplicate writes. The other
// write tools perform a state transition that must happen once per target.
func bulkAllowsRepeatedTargets(toolName string) bool {
	switch toolName {
	case "bulk_make_repayments", "bulk_make_repayments_native":
		return true
	default:
		return false
	}
}

// rejectDuplicateBulkIDs refuses a write batch that targets the same record
// twice. Reads and repayments may legitimately repeat an identifier; for the
// remaining write tools a repeated target would book the same operation twice
// in one call.
func rejectDuplicateBulkIDs(toolName string, writes bool, jobs []bulkJob) error {
	if !writes || bulkAllowsRepeatedTargets(toolName) {
		return nil
	}
	firstSeen := make(map[bulkTargetRef]int, len(jobs))
	var duplicates []string
	for _, job := range jobs {
		if job.Err != nil {
			continue
		}
		ref, ok := bulkTargetReference(job)
		if !ok {
			continue
		}
		first, seen := firstSeen[ref]
		if !seen {
			firstSeen[ref] = job.Index
			continue
		}
		duplicates = append(duplicates, fmt.Sprintf("%s=%s at indexes %d and %d", ref.Kind, ref.Value, first, job.Index))
	}
	if len(duplicates) == 0 {
		return nil
	}
	return fmt.Errorf("%s rejects duplicate resource ids in one batch: %s", toolName, strings.Join(duplicates, ", "))
}

// executeBulk runs the jobs with bounded concurrency and summarises the
// outcomes. Result slots that were never started keep the pre-filled
// "cancelled" value.
func (r *Registry) executeBulk(ctx context.Context, toolName string, jobs []bulkJob, limits bulkLimits) bulkReport {
	results := make([]bulkItemResult, len(jobs))
	for i, job := range jobs {
		results[i] = bulkItemResult{
			Index:  i,
			ID:     job.ID,
			Status: bulkItemCancelled,
			Error:  "not started: bulk execution cancelled",
		}
	}

	// Each index is written by the single worker that owns it, so the slice
	// elements are disjoint; wg.Wait in runBulkPool orders those writes before
	// the counts below are read.
	runBulkPool(ctx, len(jobs), limits.Concurrency, func(ctx context.Context, index int) {
		results[index] = r.executeBulkJob(ctx, jobs[index])
	})

	report := bulkReport{Tool: toolName, Total: len(jobs), Results: results}
	for _, result := range results {
		switch result.Status {
		case bulkItemSuccess:
			report.Succeeded++
		case bulkItemCancelled:
			report.Cancelled++
		default:
			report.Failed++
		}
	}

	switch {
	case report.Cancelled > 0:
		report.Status = bulkStatusCancelled
		report.Message = fmt.Sprintf("%d of %d operations were cancelled; %d succeeded and %d failed before cancellation", report.Cancelled, report.Total, report.Succeeded, report.Failed)
	case report.Failed == report.Total:
		report.Status = bulkStatusFailure
		report.Message = fmt.Sprintf("all %d operations failed", report.Total)
	case report.Failed > 0:
		report.Status = bulkStatusPartial
		report.Message = fmt.Sprintf("%d of %d operations failed; %d succeeded", report.Failed, report.Total, report.Succeeded)
	default:
		report.Status = bulkStatusSuccess
	}
	return report
}

// executeBulkJob performs one Fineract call and classifies the outcome. A
// response that arrived is authoritative even if the context died meanwhile;
// anything else with a dead context is a cancellation.
func (r *Registry) executeBulkJob(ctx context.Context, job bulkJob) bulkItemResult {
	result := bulkItemResult{Index: job.Index, ID: job.ID}
	if job.Err != nil {
		result.Status = bulkItemError
		result.Error = job.Err.Error()
		return result
	}

	body, err := r.Fineract.DoRequestContext(ctx, job.Method, job.Endpoint, job.Body, job.Query)
	result.Result = bulkPayload(body)
	if err == nil {
		result.Status = bulkItemSuccess
		return result
	}

	var apiErr *adapter.APIError
	switch {
	case errors.As(err, &apiErr):
		result.Status = bulkItemError
		result.HTTPStatus = apiErr.StatusCode
		result.Error = err.Error()
	case ctx.Err() != nil:
		result.Status = bulkItemCancelled
		result.Error = "cancelled: " + err.Error()
	default:
		result.Status = bulkItemError
		result.Error = err.Error()
	}
	return result
}

// bulkPayload keeps a Fineract response machine-readable when it is JSON and
// falls back to text when it is not.
func bulkPayload(body []byte) any {
	if len(body) == 0 {
		return nil
	}
	if json.Valid(body) {
		return json.RawMessage(body)
	}
	return string(body)
}

// bulkCallResult renders the report as JSON. A batch that did not fully succeed
// is returned as an MCP error, so a client cannot read it as success, while the
// report still carries every successful payload.
func bulkCallResult(report bulkReport) *mcp.CallToolResult {
	encoded, err := json.Marshal(report)
	if err != nil {
		return mcp.NewToolResultError("failed to encode bulk results: " + err.Error())
	}
	if report.Status == bulkStatusSuccess {
		return mcp.NewToolResultText(string(encoded))
	}
	return mcp.NewToolResultError(string(encoded))
}

// runBulkPool executes work for indexes 0..n-1 using at most concurrency
// workers. It never creates more than concurrency goroutines, stops handing out
// indexes once ctx is done, and waits for started work before returning so no
// goroutine outlives the call.
func runBulkPool(ctx context.Context, n, concurrency int, work func(context.Context, int)) {
	if n <= 0 {
		return
	}
	if concurrency < 1 {
		concurrency = 1
	}
	if concurrency > n {
		concurrency = n
	}

	jobs := make(chan int)
	var wg sync.WaitGroup
	wg.Add(concurrency)
	for i := 0; i < concurrency; i++ {
		go func() {
			defer wg.Done()
			for index := range jobs {
				if ctx.Err() != nil {
					return
				}
				work(ctx, index)
			}
		}()
	}

send:
	for index := 0; index < n; index++ {
		if ctx.Err() != nil {
			break
		}
		select {
		case jobs <- index:
		case <-ctx.Done():
			break send
		}
	}
	close(jobs)
	wg.Wait()
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
				ids, err := bulkArrayArg(req, "clientIds", "clientIds must be an array of numbers")
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				limits := loadBulkLimits()
				if err := validateBulkBatch(len(ids), limits); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				jobs := make([]bulkJob, len(ids))
				for i, id := range ids {
					job := bulkJob{Index: i, ID: id, IDKey: "clientId", Method: "GET"}
					path, err := bulkIdentifier(id)
					if err != nil {
						job.Err = fmt.Errorf("clientIds[%d]: %v", i, err)
						jobs[i] = job
						continue
					}
					job.Endpoint = "clients/" + path
					jobs[i] = job
				}
				return bulkCallResult(r.executeBulk(ctx, "bulk_get_client_profiles", jobs, limits)), nil
			},
			Params: []ToolParam{
				{Name: "clientIds", Description: "Array of Client IDs to fetch", Type: "array", Required: true},
			},
		},
		{
			Name:        "bulk_get_loan_summaries",
			Description: "Fetch status and details for multiple Loan IDs in parallel (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				ids, err := bulkArrayArg(req, "loanIds", "loanIds must be an array of numbers")
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				limits := loadBulkLimits()
				if err := validateBulkBatch(len(ids), limits); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				jobs := make([]bulkJob, len(ids))
				for i, id := range ids {
					job := bulkJob{Index: i, ID: id, IDKey: "loanId", Method: "GET"}
					path, err := bulkIdentifier(id)
					if err != nil {
						job.Err = fmt.Errorf("loanIds[%d]: %v", i, err)
						jobs[i] = job
						continue
					}
					job.Endpoint = "loans/" + path
					jobs[i] = job
				}
				return bulkCallResult(r.executeBulk(ctx, "bulk_get_loan_summaries", jobs, limits)), nil
			},
			Params: []ToolParam{
				{Name: "loanIds", Description: "Array of Loan IDs to fetch", Type: "array", Required: true},
			},
		},
		{
			Name:        "bulk_search_clients_parallel",
			Description: "Search for multiple name fragments concurrently (Go-Native)",
			Handler: func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
				queries, err := bulkArrayArg(req, "queries", "queries must be an array of strings")
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				limits := loadBulkLimits()
				if err := validateBulkBatch(len(queries), limits); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				jobs := make([]bulkJob, len(queries))
				for i, query := range queries {
					job := bulkJob{Index: i, ID: query, Method: "GET", Endpoint: "clients"}
					text, err := bulkNonEmptyString(query, "query")
					if err != nil {
						job.Err = fmt.Errorf("queries[%d]: %v", i, err)
						jobs[i] = job
						continue
					}
					job.Query = map[string]string{"displayName": text}
					jobs[i] = job
				}
				return bulkCallResult(r.executeBulk(ctx, "bulk_search_clients_parallel", jobs, limits)), nil
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
				ids, err := bulkArrayArg(req, "clientIds", "clientIds must be an array")
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				limits := loadBulkLimits()
				if err := validateBulkBatch(len(ids), limits); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				chargePath, err := bulkIdentifier(args["chargeId"])
				if err != nil {
					return mcp.NewToolResultError("Missing clientIds (array) or chargeId (number): " + err.Error()), nil
				}
				chargeID, err := strconv.ParseInt(chargePath, 10, 64)
				if err != nil {
					return mcp.NewToolResultError("invalid chargeId: " + err.Error()), nil
				}
				jobs := make([]bulkJob, len(ids))
				for i, id := range ids {
					job := bulkJob{Index: i, ID: id, IDKey: "clientId", Method: "POST"}
					path, err := bulkIdentifier(id)
					if err != nil {
						job.Err = fmt.Errorf("clientIds[%d]: %v", i, err)
						jobs[i] = job
						continue
					}
					job.Endpoint = "clients/" + path + "/charges"
					job.Body = map[string]any{
						"chargeId": chargeID,
						"amount":   args["amount"],
						"dueDate":  args["dueDate"],
					}
					jobs[i] = job
				}
				if err := rejectDuplicateBulkIDs("bulk_apply_charges_to_batch", true, jobs); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				return bulkCallResult(r.executeBulk(ctx, "bulk_apply_charges_to_batch", jobs, limits)), nil
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
				repayments, err := bulkArrayArg(req, "repayments", "repayments must be an array of objects")
				if err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				limits := loadBulkLimits()
				if err := validateBulkBatch(len(repayments), limits); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}

				today := time.Now().Format("2006-01-02")
				jobs := make([]bulkJob, len(repayments))
				for i, item := range repayments {
					job := bulkJob{Index: i, Method: "POST"}
					fields, ok := item.(map[string]interface{})
					if !ok {
						job.Err = fmt.Errorf("repayment at index %d must be an object", i)
						jobs[i] = job
						continue
					}
					job.ID, job.IDKey = fields["loanId"], "loanId"
					if job.ID == nil {
						job.Err = fmt.Errorf("repayment at index %d is missing loanId", i)
						jobs[i] = job
						continue
					}
					loanPath, err := bulkIdentifier(job.ID)
					if err != nil {
						job.Err = fmt.Errorf("repayment at index %d has invalid loanId: %v", i, err)
						jobs[i] = job
						continue
					}

					date, err := bulkRepaymentDate(fields["transactionDate"], today)
					if err != nil {
						job.Err = fmt.Errorf("repayment at index %d: %v", i, err)
						jobs[i] = job
						continue
					}
					amount, err := FormatAmount(fields["amount"])
					if err != nil {
						job.Err = fmt.Errorf("invalid amount: %v", err)
						jobs[i] = job
						continue
					}
					if err := bulkRepaymentAmount(amount); err != nil {
						job.Err = fmt.Errorf("repayment at index %d: %v", i, err)
						jobs[i] = job
						continue
					}

					job.Endpoint = "loans/" + loanPath + "/transactions?command=repayment"
					job.Body = map[string]interface{}{
						"transactionDate":   date,
						"transactionAmount": amount,
						"dateFormat":        "yyyy-MM-dd",
						"locale":            "en",
					}
					jobs[i] = job
				}
				if err := rejectDuplicateBulkIDs("bulk_make_repayments_native", true, jobs); err != nil {
					return mcp.NewToolResultError(err.Error()), nil
				}
				return bulkCallResult(r.executeBulk(ctx, "bulk_make_repayments_native", jobs, limits)), nil
			},
			Params: []ToolParam{
				{Name: "repayments", Description: "Array of objects. MISSION CRITICAL: Each 'amount' field MUST be the exact numeric value from the human prompt. IGNORE backend due amounts.", Type: "array", Required: true},
			},
		},
	}
}

// bulkArrayArg reads an array argument, keeping the tool's established wording
// when the value is missing or has the wrong shape.
func bulkArrayArg(req mcp.CallToolRequest, name, message string) ([]any, error) {
	values, ok := req.GetArguments()[name].([]any)
	if !ok {
		return nil, errors.New(message)
	}
	return values, nil
}
