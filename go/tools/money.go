// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package tools

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/idempotency"
)

// MoneyMove marks a tool as balance-changing so its handler runs through the
// durable idempotency ledger instead of calling Fineract directly.
//
// Operation names and payload shapes are kept identical to the Python
// implementation (python/tools/domains/*.py) for the same logical request, so
// a key claimed by one language replays in the other instead of being
// executed twice.
type MoneyMove struct {
	Operation string
	// Payload is the client-visible request that is fingerprinted. It must not
	// contain server-derived values such as a defaulted date: a retry on a
	// later day has to match the original claim.
	Payload func(args map[string]any) map[string]any
	// Reconciliation supplies the ids the reconciler reads.
	Reconciliation func(args map[string]any) map[string]any
	// Reconcile builds the read-only reconciler. Nil means Fineract exposes no
	// verified read for this operation (manual journal entries), so an
	// ambiguous outcome deliberately stays UNKNOWN.
	Reconcile func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler
	// ItemPayload fingerprints one bulk item. Bulk tools take free-form item
	// maps, so the default is the item itself.
	ItemPayload func(item map[string]any) map[string]any
	// ItemReconciliation maps a bulk item onto the reconciler's ids.
	ItemReconciliation func(item map[string]any) map[string]any
}

// idempotencyKeyParam is schema-only: it never becomes a path, query or body
// value. Every money tool declares it so a caller can retry safely.
func idempotencyKeyParam() ToolParam {
	return ToolParam{
		Name: "idempotencyKey", Type: "string", Required: false,
		Description: "Stable key for this money move (8-128 chars, [A-Za-z0-9._:-]). " +
			"Retrying with the same key replays the first result instead of paying twice.",
	}
}

// classifyMoneyResponse turns one backend response into an attempt outcome.
//
// A 4xx (other than the statuses that leave the effect open) proves Fineract
// refused the request, so a retry is safe. A 5xx, a 408/409/425/429, or a
// transport failure may have happened after the transaction committed, so the
// outcome is ambiguous and must be reconciled before anything is repeated.
func classifyMoneyResponse(response adapter.Response) (idempotency.Attempt, error) {
	if response.Err == nil {
		result := map[string]any{}
		if len(response.Body) > 0 {
			if err := json.Unmarshal(response.Body, &result); err != nil {
				// A successful status with unreadable body means the write may
				// have happened but its result id is lost: ambiguous.
				return idempotency.Attempt{
					Outcome: idempotency.OutcomeAmbiguous,
					Error:   "Fineract returned a response that could not be read.",
					Detail:  fmt.Sprintf("HTTP %d with an unreadable body.", response.StatusCode),
				}, nil
			}
		}
		return idempotency.Attempt{Outcome: idempotency.OutcomeSuccess, Result: result}, nil
	}

	message := backendMessage(response)
	status := response.StatusCode
	if status >= 400 && status < 500 && status != http.StatusRequestTimeout &&
		status != http.StatusConflict && status != http.StatusTooEarly && status != http.StatusTooManyRequests {
		return idempotency.Attempt{
			Outcome: idempotency.OutcomeFailure,
			Error:   message,
			Raw:     map[string]any{"error": message, "status_code": status},
		}, nil
	}

	detail := ""
	if status != 0 {
		detail = fmt.Sprintf("Fineract returned HTTP %d.", status)
	} else {
		detail = "The connection failed before an HTTP status was received."
	}
	return idempotency.Attempt{
		Outcome: idempotency.OutcomeAmbiguous,
		Error:   message,
		Detail:  detail,
	}, nil
}

// backendMessage prefers Fineract's own error text and never echoes request
// data: only the parsed "defaultUserMessage"/"developerMessage" fields and the
// HTTP status are used.
func backendMessage(response adapter.Response) string {
	var payload map[string]any
	if len(response.Body) > 0 && json.Unmarshal(response.Body, &payload) == nil {
		for _, field := range []string{"defaultUserMessage", "developerMessage", "error"} {
			if text, ok := payload[field].(string); ok && strings.TrimSpace(text) != "" {
				return text
			}
		}
	}
	if response.StatusCode != 0 {
		return fmt.Sprintf("Fineract rejected the request with HTTP %d.", response.StatusCode)
	}
	return "The connection failed before an HTTP response was received."
}

// runMoneyTool executes one money tool at most once per idempotency key.
func (r *Registry) runMoneyTool(ctx context.Context, def BaseToolDef, args map[string]any, endpoint string, body map[string]any, queryParams map[string]string) (*mcp.CallToolResult, error) {
	move := def.MoneyMove
	key := stringValue(args["idempotencyKey"])

	request := idempotency.Request{
		Operation:      move.Operation,
		Resource:       endpoint,
		Payload:        move.Payload(args),
		Reconciliation: move.Reconciliation(args),
	}

	executor := func(ctx context.Context, backendKey string) (idempotency.Attempt, error) {
		headers := map[string]string{}
		if backendKey != "" {
			headers[idempotency.BackendHeaderName()] = backendKey
		}
		response := r.Fineract.DoRequestOutcome(ctx, def.Method, endpoint, body, queryParams, headers)
		attempt, err := classifyMoneyResponse(response)
		if err != nil {
			return attempt, err
		}
		// Cancellation is never a business failure: the request may have been
		// accepted before the context ended, so surface it and let the ledger
		// record UNKNOWN.
		if ctxErr := ctx.Err(); ctxErr != nil && attempt.Outcome != idempotency.OutcomeSuccess {
			return attempt, ctxErr
		}
		return attempt, nil
	}

	var reconciler idempotency.Reconciler
	if move.Reconcile != nil {
		reconciler = move.Reconcile(r.Fineract, request.Reconciliation)
	}

	result, err := idempotency.Execute(ctx, request, key, executor, reconciler, idempotency.Options{})
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			// Cancellation is the caller's own signal, not an idempotency
			// verdict: propagate it so the transport can report it.
			return nil, ctxErr
		}
		return mcp.NewToolResultError(jsonText(idempotency.ErrorBody(err))), nil
	}
	if _, failed := result["error"]; failed {
		return mcp.NewToolResultError(jsonText(result)), nil
	}
	return mcp.NewToolResultText(moneySuccessText(def.Name, args, result)), nil
}

// executeBulkItem runs one bulk item through the ledger and returns a
// deterministic per-item record. The returned map is JSON-safe and carries its
// own status, so a caller can tell a succeeded item from a failed, in-progress
// or unknown one and reconcile them individually.
func (r *Registry) executeBulkItem(ctx context.Context, move *MoneyMove, method, endpoint string, body map[string]any, item map[string]any, index int, key string) map[string]any {
	payload := bulkPayloadItem(item)
	if move.ItemPayload != nil {
		payload = move.ItemPayload(item)
	}
	reconciliation := map[string]any{}
	if move.ItemReconciliation != nil {
		reconciliation = move.ItemReconciliation(item)
	}

	result := map[string]any{"index": index}

	if ctx.Err() != nil {
		result["status"] = "cancelled"
		result["error"] = "attempted after the request context ended"
		return result
	}

	request := idempotency.Request{
		Operation:      move.Operation,
		Resource:       endpoint,
		Payload:        payload,
		Reconciliation: reconciliation,
	}
	executor := func(ctx context.Context, backendKey string) (idempotency.Attempt, error) {
		headers := map[string]string{}
		if backendKey != "" {
			headers[idempotency.BackendHeaderName()] = backendKey
		}
		response := r.Fineract.DoRequestOutcome(ctx, method, endpoint, body, nil, headers)
		attempt, err := classifyMoneyResponse(response)
		if err != nil {
			return attempt, err
		}
		if ctxErr := ctx.Err(); ctxErr != nil && attempt.Outcome != idempotency.OutcomeSuccess {
			return attempt, ctxErr
		}
		return attempt, nil
	}

	var reconciler idempotency.Reconciler
	if move.Reconcile != nil {
		reconciler = move.Reconcile(r.Fineract, reconciliation)
	}

	outcome, err := idempotency.Execute(ctx, request, key, executor, reconciler, idempotency.Options{})
	if err != nil {
		if ctxErr := ctx.Err(); ctxErr != nil {
			result["status"] = "cancelled"
			result["error"] = ctxErr.Error()
			return result
		}
		errorBody := idempotency.ErrorBody(err)
		result["status"] = bulkStatus(errorBody["error_code"])
		for field, value := range errorBody {
			result[field] = value
		}
		return result
	}
	if _, failed := outcome["error"]; failed {
		result["status"] = bulkStatus(outcome["error_code"])
		for field, value := range outcome {
			result[field] = value
		}
		return result
	}
	result["status"] = "succeeded"
	result["result"] = outcome
	return result
}

// bulkStatus maps a structured error code onto a per-item status.
func bulkStatus(code any) string {
	switch fmt.Sprintf("%v", code) {
	case "safe_retry_permitted", "operation_failed":
		return "failed"
	case "operation_in_progress":
		return "in_progress"
	case "outcome_unknown", "reconciliation_required":
		return "unknown"
	case "missing_idempotency_key", "invalid_idempotency_key", "idempotency_key_reused", "ledger_unavailable":
		return "rejected"
	}
	return "failed"
}

// bulkMoneyItemKey derives a stable key for one bulk item from the caller's
// parent key, the item's position and its fingerprinted payload. Retrying the
// same batch with the same parent key therefore replays every item instead of
// repeating the ones that already succeeded.
func bulkMoneyItemKey(parent, operation string, index int, payload map[string]any) string {
	material, err := idempotency.Canonical(map[string]any{
		"operation": operation,
		"index":     index,
		"payload":   payload,
	})
	if err != nil {
		return ""
	}
	sum := sha256.Sum256([]byte(parent + "|" + material))
	return "bulk-" + hex.EncodeToString(sum[:])[:40]
}

// moneySuccessText keeps the established "SUCCESS: Executed <tool>" shape while
// echoing the replayed or freshly created result.
func moneySuccessText(toolName string, args map[string]any, result map[string]any) string {
	prefix := fmt.Sprintf("SUCCESS: Executed %s.", toolName)
	for _, id := range []struct{ arg, label string }{
		{"loanId", "Loan ID"},
		{"clientId", "Client ID"},
		{"savingsId", "Savings ID"},
	} {
		if value, ok := args[id.arg]; ok && value != nil {
			prefix += fmt.Sprintf(" %s: %v.", id.label, value)
		}
	}
	return fmt.Sprintf("%s Result: %s", prefix, jsonText(result))
}

// jsonText renders a result with sorted keys so a replayed and a fresh
// response are textually identical.
func jsonText(value map[string]any) string {
	encoded, err := json.Marshal(value)
	if err != nil {
		return "{}"
	}
	return string(encoded)
}

// bulkPayloadItem is the fingerprint payload for a bulk item: the item map
// without the control field that keys it.
func bulkPayloadItem(item map[string]any) map[string]any {
	payload := make(map[string]any, len(item))
	for key, value := range item {
		if key == "idempotencyKey" {
			continue
		}
		payload[key] = value
	}
	return payload
}

// ── reconciliation ───────────────────────────────────────────────────

// newRecordReconciler confirms an ambiguous attempt by finding exactly one
// backend record that did not exist before it ran.
type newRecordReconciler struct {
	read     func(ctx context.Context) ([]map[string]any, error)
	matches  func(item, params map[string]any) bool
	build    func(item, params map[string]any) map[string]any
	identity []string
}

func (n *newRecordReconciler) Snapshot(ctx context.Context, request idempotency.Request) (any, error) {
	records, err := n.read(ctx)
	if err != nil {
		return nil, err
	}
	keys := make([]string, 0, len(records))
	for _, item := range records {
		if key := recordKey(item, n.identity); key != "" {
			keys = append(keys, key)
		}
	}
	return keys, nil
}

func (n *newRecordReconciler) Reconcile(ctx context.Context, record *idempotency.Record) (idempotency.ReconcileResult, error) {
	snapshot, ok := stringSlice(record.Reconcile["snapshot"])
	if !ok {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "No pre-execution snapshot exists, so a new record cannot be told apart from an older one. Check the operation in Fineract.",
		}, nil
	}
	records, err := n.read(ctx)
	if err != nil {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "Reconciliation read failed: " + err.Error(),
		}, nil
	}

	seen := make(map[string]struct{}, len(snapshot))
	for _, key := range snapshot {
		seen[key] = struct{}{}
	}
	var fresh []map[string]any
	for _, item := range records {
		key := recordKey(item, n.identity)
		if key == "" {
			continue
		}
		if _, existed := seen[key]; !existed {
			fresh = append(fresh, item)
		}
	}

	params := record.Reconcile
	if params == nil {
		params = map[string]any{}
	}
	if len(fresh) == 0 {
		confirmed, detail := absenceVerdict(record, params)
		if !confirmed {
			return idempotency.ReconcileResult{
				Status: idempotency.ReconcileUnknown,
				Detail: detail,
			}, nil
		}
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileAbsent,
			Detail: detail,
		}, nil
	}

	// New records with the right type and amount are not enough: another
	// channel may have created an identical transaction. Only a record that
	// carries this request's unique marker can be attributed to it.
	var marked []map[string]any
	for _, item := range fresh {
		if hasMarker(item, params) {
			marked = append(marked, item)
		}
	}
	switch len(marked) {
	case 1:
		return idempotency.ReconcileResult{
			Status:     idempotency.ReconcileConfirmed,
			Result:     n.build(marked[0], params),
			BackendRef: map[string]any{"record_id": recordKey(marked[0], n.identity)},
			Detail:     "Matched exactly one new Fineract record carrying this request's marker.",
		}, nil
	case 0:
	default:
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "More than one new record carries this request's marker, so its effect cannot be isolated.",
		}, nil
	}

	var matching []map[string]any
	for _, item := range fresh {
		if n.matches(item, params) {
			matching = append(matching, item)
		}
	}
	if len(matching) > 0 {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "New records match this operation's type and amount, but none carries a unique request marker, so the outcome cannot be attributed safely. Check it in Fineract.",
		}, nil
	}
	return idempotency.ReconcileResult{
		Status: idempotency.ReconcileUnknown,
		Detail: "New records appeared, but none matches this operation. Check it in Fineract.",
	}, nil
}

const ledgerTimeLayout = "2006-01-02T15:04:05.000000Z"

// absenceVerdict reports whether attempt-scoped, time-windowed absence
// evidence is sufficient. The window is a mitigation, not proof of backend
// read consistency, so the state machine still refuses an automatic retry
// without verified backend deduplication.
func absenceVerdict(record *idempotency.Record, params map[string]any) (bool, string) {
	required := idempotency.AbsenceConfirmations()
	window := idempotency.AbsenceSafetyWindow()
	now := time.Now().UTC()

	endedText := record.AttemptEndedAt
	if endedText == "" {
		endedText = stringValue(params["attempt_ended_at"])
	}
	ended, err := time.Parse(ledgerTimeLayout, endedText)
	if endedText == "" || err != nil {
		return false, "The attempt's end time is unknown, so absence cannot be proven."
	}

	if intValue(params["absence_attempt"]) != record.Attempt {
		// New attempt: earlier empty reads say nothing about this one.
		params["absence_attempt"] = record.Attempt
		params["absence_reads"] = 0
		delete(params, "absence_last_read_at")
	}

	if now.Sub(ended) < window {
		return false, "The safety window since the attempt ended has not elapsed; absence is not confirmed."
	}
	if lastText := stringValue(params["absence_last_read_at"]); lastText != "" {
		if last, parseErr := time.Parse(ledgerTimeLayout, lastText); parseErr == nil && now.Sub(last) < window {
			return false, "Not enough time has passed since the previous empty read; absence is not confirmed."
		}
	}

	reads := intValue(params["absence_reads"]) + 1
	params["absence_reads"] = reads
	params["absence_last_read_at"] = now.Format(ledgerTimeLayout)
	if reads < required {
		return false, "No matching record is visible yet; absence is not confirmed."
	}
	return true, "No record of this operation exists in Fineract."
}

func markerFor(params map[string]any) string {
	marker, _ := params["marker"].(string)
	return marker
}

func markerFieldsFor(params map[string]any) []string {
	switch fields := params["marker_fields"].(type) {
	case []any:
		out := make([]string, 0, len(fields))
		for _, field := range fields {
			if text, ok := field.(string); ok {
				out = append(out, text)
			}
		}
		if len(out) > 0 {
			return out
		}
	case []string:
		if len(fields) > 0 {
			return fields
		}
	}
	return idempotency.DefaultMarkerFields
}

func hasMarker(item, params map[string]any) bool {
	marker := markerFor(params)
	if marker == "" {
		return false
	}
	for _, field := range markerFieldsFor(params) {
		if value, ok := item[field].(string); ok && value == marker {
			return true
		}
	}
	return false
}

func recordKey(item map[string]any, identity []string) string {
	for _, field := range []string{"id", "transactionId"} {
		if value, ok := item[field]; ok && value != nil {
			return fmt.Sprintf("%s:%v", field, value)
		}
	}
	if len(identity) == 0 {
		return ""
	}
	parts := make([]string, 0, len(identity))
	for _, field := range identity {
		parts = append(parts, fmt.Sprintf("%s:%v", field, item[field]))
	}
	return strings.Join(parts, "|")
}

func transactionKind(item map[string]any) (string, string) {
	for _, field := range []string{"transactionType", "type"} {
		switch value := item[field].(type) {
		case map[string]any:
			return stringValue(value["code"]), stringValue(value["value"])
		case string:
			return "", value
		}
	}
	return "", ""
}

func kindMatches(item map[string]any, needles, suffixes []string) bool {
	code, label := transactionKind(item)
	for _, suffix := range suffixes {
		if strings.HasSuffix(code, suffix) {
			return true
		}
	}
	lowered := strings.ToLower(label)
	for _, needle := range needles {
		if strings.Contains(lowered, needle) {
			return true
		}
	}
	return false
}

func transactionMatches(needles, suffixes []string) func(item, params map[string]any) bool {
	return func(item, params map[string]any) bool {
		if reversed, _ := item["reversed"].(bool); reversed {
			return false
		}
		if !kindMatches(item, needles, suffixes) {
			return false
		}
		return sameAmount(item["amount"], params["amount"])
	}
}

func chargeMatches(item, params map[string]any) bool {
	return sameNumber(item["chargeId"], params["charge_id"]) &&
		sameAmount(item["amount"], params["amount"])
}

func loanTransactionResult(operation string) func(item, params map[string]any) map[string]any {
	return func(item, params map[string]any) map[string]any {
		_, label := transactionKind(item)
		return map[string]any{
			"loanId":        params["loan_id"],
			"transactionId": item["id"],
			"resourceId":    item["id"],
			"type":          nilIfEmpty(label),
			"amount":        item["amount"],
			"date":          item["date"],
			"reconciled":    true,
			"operation":     operation,
		}
	}
}

func savingsTransactionResult(operation string) func(item, params map[string]any) map[string]any {
	return func(item, params map[string]any) map[string]any {
		_, label := transactionKind(item)
		return map[string]any{
			"savingsId":     params["account_id"],
			"transactionId": item["id"],
			"resourceId":    item["id"],
			"type":          nilIfEmpty(label),
			"amount":        item["amount"],
			"date":          item["date"],
			"reconciled":    true,
			"operation":     operation,
		}
	}
}

func chargeResult(owner, operation string) func(item, params map[string]any) map[string]any {
	return func(item, params map[string]any) map[string]any {
		return map[string]any{
			owner:             params["owner_id"],
			"chargeId":        item["chargeId"],
			"appliedChargeId": item["id"],
			"resourceId":      item["id"],
			"amount":          item["amount"],
			"dueDate":         item["dueDate"],
			"reconciled":      true,
			"operation":       operation,
		}
	}
}

func nilIfEmpty(value string) any {
	if value == "" {
		return nil
	}
	return value
}

// ── Fineract reads ───────────────────────────────────────────────────

func getJSON(ctx context.Context, client *adapter.FineractClient, endpoint string) (map[string]any, error) {
	response := client.DoRequestOutcome(ctx, http.MethodGet, endpoint, nil, nil, nil)
	if response.Err != nil {
		return nil, fmt.Errorf("GET %s: %w", endpoint, response.Err)
	}
	payload := map[string]any{}
	if err := json.Unmarshal(response.Body, &payload); err != nil {
		return nil, fmt.Errorf("GET %s: unreadable response", endpoint)
	}
	return payload, nil
}

func readCollection(ctx context.Context, client *adapter.FineractClient, endpoint, key string) ([]map[string]any, error) {
	payload, err := getJSON(ctx, client, endpoint)
	if err != nil {
		return nil, err
	}
	rawValue, present := payload[key]
	if !present {
		// A 200 response that omits the collection proved nothing. Treating it
		// as empty would turn a proxy/serializer change into absence evidence.
		return nil, fmt.Errorf("reconciliation response has no %q collection; the outcome is unknown", key)
	}
	raw, ok := rawValue.([]any)
	if !ok {
		return nil, fmt.Errorf("reconciliation response field %q is not a list", key)
	}
	items := make([]map[string]any, 0, len(raw))
	for _, entry := range raw {
		item, ok := entry.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("reconciliation response has a malformed %q entry", key)
		}
		items = append(items, item)
	}
	return items, nil
}

func interestMatches(item, _ map[string]any) bool {
	if reversed, _ := item["reversed"].(bool); reversed {
		return false
	}
	return kindMatches(item, []string{"interest"}, []string{".interestposting"})
}

func loanTransactionsReconciler(needles, suffixes []string, operation string) func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
	return func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
		return &newRecordReconciler{
			read: func(ctx context.Context) ([]map[string]any, error) {
				return readCollection(ctx, client, fmt.Sprintf("loans/%v?associations=transactions", params["loan_id"]), "transactions")
			},
			matches: transactionMatches(needles, suffixes),
			build:   loanTransactionResult(operation),
		}
	}
}

func savingsTransactionsReconciler(needles, suffixes []string, operation string) func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
	return func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
		return &newRecordReconciler{
			read: func(ctx context.Context) ([]map[string]any, error) {
				return readCollection(ctx, client, fmt.Sprintf("savingsaccounts/%v?associations=transactions", params["account_id"]), "transactions")
			},
			matches: transactionMatches(needles, suffixes),
			build:   savingsTransactionResult(operation),
		}
	}
}

// savingsInterestReconciler matches interest postings by type, mirroring the
// Python reconciler. It still cannot confirm without a unique request marker.
func savingsInterestReconciler() func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
	return func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
		return &newRecordReconciler{
			read: func(ctx context.Context) ([]map[string]any, error) {
				return readCollection(ctx, client, fmt.Sprintf("savingsaccounts/%v?associations=transactions", params["account_id"]), "transactions")
			},
			matches: interestMatches,
			build:   savingsTransactionResult("savings.post_interest"),
		}
	}
}

func loanChargeReconciler() func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
	return func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
		return &newRecordReconciler{
			identity: []string{"chargeId", "amount", "dueDate"},
			read: func(ctx context.Context) ([]map[string]any, error) {
				return readCollection(ctx, client, fmt.Sprintf("loans/%v?associations=charges", params["loan_id"]), "charges")
			},
			matches: chargeMatches,
			build:   chargeResult("loanId", "loan.charge"),
		}
	}
}

// savingsChargeReconciler documents an unavailable read rather than inventing
// one: the associations this repository uses expose savings transactions, not
// applied charges, and a charge is not a transaction until it is paid.
func savingsChargeReconciler() func(client *adapter.FineractClient, params map[string]any) idempotency.Reconciler {
	return nil
}

// errLoanStatusUnavailable marks a status read that returned HTTP 200 but did
// not carry a usable, recognised loan status. It is deliberately distinct from
// a failed request: both are UNKNOWN, but only a usable read may ever support
// an absence verdict.
var errLoanStatusUnavailable = errors.New("the loan status is not usable")

// knownLoanStatuses are the Fineract loan status labels this reconciler
// understands. A status outside this set is treated as an unreadable response,
// never as evidence of anything: this list is the entire vocabulary that may
// participate in a reconciliation decision.
var knownLoanStatuses = map[string]struct{}{
	"submitted and pending approval": {},
	"approved":                       {},
	"active":                         {},
	"rejected":                       {},
	"withdrawn by applicant":         {},
	"closed (obligations met)":       {},
	"closed (written off)":           {},
	"closed (rescheduled)":           {},
	"overpaid":                       {},
	"transfer in progress":           {},
	"transfer on hold":               {},
}

// preDisbursalLoanStatuses cannot be observed after a successful disbursal.
// Only these statuses may ever support an absence verdict: a status such as
// "Closed (obligations met)" proves the loan existed, not that no disbursal
// happened.
var preDisbursalLoanStatuses = map[string]struct{}{
	"submitted and pending approval": {},
	"approved":                       {},
	"rejected":                       {},
	"withdrawn by applicant":         {},
}

// normalizeLoanStatus accepts only a non-empty string whose trimmed value is a
// status this reconciler explicitly understands. Missing, null, numeric,
// object-valued and unknown strings are all rejected.
func normalizeLoanStatus(value any) (string, bool) {
	text, ok := value.(string)
	if !ok {
		return "", false
	}
	label := strings.TrimSpace(text)
	if label == "" {
		return "", false
	}
	if _, known := knownLoanStatuses[strings.ToLower(label)]; !known {
		return "", false
	}
	return label, true
}

func isPreDisbursalStatus(label string) bool {
	_, ok := preDisbursalLoanStatuses[strings.ToLower(strings.TrimSpace(label))]
	return ok
}

// loanDisbursalReconciler confirms a disbursal from the loan's own status.
//
// A status read that returns HTTP 200 without a recognised status cannot prove
// anything: it is reported as UNKNOWN, never as absence. This mirrors
// python/tools/idempotent_money.py.
type loanDisbursalReconciler struct {
	client *adapter.FineractClient
}

func (l *loanDisbursalReconciler) status(ctx context.Context, loanID any) (string, error) {
	payload, err := getJSON(ctx, l.client, fmt.Sprintf("loans/%v", loanID))
	if err != nil {
		return "", err
	}
	status, ok := payload["status"].(map[string]any)
	if !ok {
		return "", fmt.Errorf("%w: the response has no status object", errLoanStatusUnavailable)
	}
	label, ok := normalizeLoanStatus(status["value"])
	if !ok {
		return "", fmt.Errorf("%w: %q is not a recognised loan status", errLoanStatusUnavailable, status["value"])
	}
	return label, nil
}

func (l *loanDisbursalReconciler) Snapshot(ctx context.Context, request idempotency.Request) (any, error) {
	status, err := l.status(ctx, request.Reconciliation["loan_id"])
	if err != nil {
		if errors.Is(err, errLoanStatusUnavailable) {
			// Returning {"status": ""} would make an already-Active loan look
			// like it transitioned during this attempt, so store nothing.
			return nil, nil
		}
		return nil, err
	}
	return map[string]any{"status": status}, nil
}

func (l *loanDisbursalReconciler) Reconcile(ctx context.Context, record *idempotency.Record) (idempotency.ReconcileResult, error) {
	snapshot, ok := record.Reconcile["snapshot"].(map[string]any)
	if !ok {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "No pre-execution loan status was captured, so the disbursal cannot be told apart.",
		}, nil
	}
	before, ok := normalizeLoanStatus(snapshot["status"])
	if !ok {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: "The pre-execution loan status was not usable, so the disbursal cannot be told apart.",
		}, nil
	}
	current, err := l.status(ctx, record.Reconcile["loan_id"])
	if err != nil {
		detail := "The loan status could not be read."
		if errors.Is(err, errLoanStatusUnavailable) {
			detail = "The loan status response was not usable, so the disbursal cannot be told apart."
		}
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: detail,
		}, nil
	}
	if strings.EqualFold(current, "Active") {
		if strings.EqualFold(before, "Active") {
			return idempotency.ReconcileResult{
				Status: idempotency.ReconcileUnknown,
				Detail: "The loan was already Active before the attempt, so this disbursal cannot be isolated.",
			}, nil
		}
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileConfirmed,
			Result: map[string]any{
				"loanId":     record.Reconcile["loan_id"],
				"status":     current,
				"reconciled": true,
				"operation":  "loan.disburse",
				"note":       "The disbursement is visible as the loan's Active status.",
			},
			BackendRef: map[string]any{"loan_status": current},
			Detail:     "Loan transitioned to Active.",
		}, nil
	}
	if !isPreDisbursalStatus(before) || !isPreDisbursalStatus(current) {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: fmt.Sprintf("The loan status '%s' cannot prove that no disbursement happened.", current),
		}, nil
	}
	confirmed, detail := absenceVerdict(record, record.Reconcile)
	if !confirmed {
		return idempotency.ReconcileResult{
			Status: idempotency.ReconcileUnknown,
			Detail: detail,
		}, nil
	}
	return idempotency.ReconcileResult{
		Status: idempotency.ReconcileAbsent,
		Detail: fmt.Sprintf("The loan is still '%s', so it was not disbursed.", current),
	}, nil
}

func loanDisbursalReconcilerFor(client *adapter.FineractClient, _ map[string]any) idempotency.Reconciler {
	return &loanDisbursalReconciler{client: client}
}

// ── typed helpers ────────────────────────────────────────────────────

func stringValue(value any) string {
	switch v := value.(type) {
	case string:
		return v
	case nil:
		return ""
	default:
		return fmt.Sprintf("%v", v)
	}
}

func toFloat(value any) (float64, bool) {
	switch v := value.(type) {
	case float64:
		return v, true
	case float32:
		return float64(v), true
	case int:
		return float64(v), true
	case int64:
		return float64(v), true
	case json.Number:
		parsed, err := v.Float64()
		return parsed, err == nil
	case string:
		parsed, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
		return parsed, err == nil
	}
	return 0, false
}

func sameAmount(left, right any) bool {
	a, okA := toFloat(left)
	b, okB := toFloat(right)
	return okA && okB && math.Abs(a-b) < 1e-9
}

func sameNumber(left, right any) bool {
	return sameAmount(left, right)
}

func intValue(value any) int {
	if parsed, ok := toFloat(value); ok {
		return int(parsed)
	}
	if text, ok := value.(string); ok {
		if parsed, err := strconv.Atoi(text); err == nil {
			return parsed
		}
	}
	return 0
}

func stringSlice(value any) ([]string, bool) {
	switch v := value.(type) {
	case []string:
		return v, true
	case []any:
		out := make([]string, 0, len(v))
		for _, entry := range v {
			text, ok := entry.(string)
			if !ok {
				return nil, false
			}
			out = append(out, text)
		}
		return out, true
	}
	return nil, false
}

// defaultValue returns value unless it is nil or an empty string.
func defaultValue(value any, fallback string) any {
	if text, ok := value.(string); ok && strings.TrimSpace(text) != "" {
		return text
	}
	if value == nil {
		return fallback
	}
	return value
}

// jsonArrayValue normalises a JSON-encoded array string, which is how a
// generic MCP tool receives list arguments, into the structured value the
// Python implementation fingerprints. An unparseable value is left alone so
// the request still fails closed rather than being silently rewritten.
func jsonArrayValue(value any) any {
	switch typed := value.(type) {
	case string:
		var parsed any
		if err := json.Unmarshal([]byte(typed), &parsed); err == nil {
			return parsed
		}
	case nil:
		return []any{}
	}
	return value
}

// numericPayloadValue keeps fingerprint payloads numeric no matter whether the
// value arrived as a JSON number or as a hardened decimal string.
func numericPayloadValue(value any) any {
	if parsed, ok := toFloat(value); ok {
		return parsed
	}
	return value
}
