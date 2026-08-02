// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

// COMP-DIST-001 / COMP-DIST-002: the share-out / rotation-payout WRITE facade —
// the DESTRUCTIVE year-end distribution. The CommonPurse (mifos-x-group-banking)
// app's ShareOutApiImpl POSTs two writes at bare companion paths (the app's base
// URL points at THIS Go server, which serves only explicitly-registered routes —
// there is no catch-all proxy):
//
//	share-out-execute  POST /companion/groups/{groupId}/shareout/execute -> ShareOutExecuteResponseDto     (COMP-DIST-001, ACCUMULATING VSLA/ASCA/SHG/SILC)
//	rotation-execute   POST /companion/groups/{groupId}/rotation/execute -> RotationPayoutExecuteResponseDto (COMP-DIST-002, ROTATING_PAYOUT ROSCA/chit)
//
// Source of truth for the wire shapes is the app's OWN approved contract:
//   - service : core/network/.../service/shareout/ShareOutApiImpl.kt
//     (executeShareOut -> POST .../shareout/execute; executeRotationPayout -> POST .../rotation/execute)
//   - model   : core/network/.../model/ShareOutDto.kt
//     (ShareOutExecuteRequestDto / ShareOutExecuteResponseDto / MemberPayoutRequestDto /
//      RotationPayoutExecuteRequestDto / RotationPayoutExecuteResponseDto)
//   - mapper  : core/network/.../mapper/ShareOutMappers.kt
//
// REAL FINERACT EFFECT (why this is destructive): a VSLA year-end share-out drains
// the accumulated group corpus back out to members. mifos-bank-2 holds each group's
// corpus as ONE shared group savings account (see savings.go — the corpus model is a
// single group-linked account, members hold pro-rata shares). So the share-out is a
// sequence of REAL savings WITHDRAWAL transactions against that corpus account — one
// per member payout for COMP-DIST-001 (per-member success/failure counted), a single
// withdrawal for the one recipient in COMP-DIST-002 — plus a datatable record of the
// distribution (dt_share_out / dt_rosca_rotation). Nothing is fabricated: the money
// genuinely leaves the corpus account, and the record row id IS the returned
// shareoutRecordId / rotationRecordId.
//
// Writes use the shared FineractClient service credential (adapter.DoRequest, which
// POSTs with the service BasicAuth) — the correct path for a treasurer/organizer
// write, exactly like the loan-write + group-create facades. The end-user credential
// path (companion.go's fineractPost) is reserved for /authentication.
//
// DATE-FORMAT decisions (per the app's DTOs + mappers + the real Fineract writes):
//   - request.executedAt: the app sends an ISO-8601 client execution timestamp String
//     and the datatable stores it in a String (VARCHAR) column (executed_at) — it
//     round-trips byte-for-byte with NO Fineract dateFormat/locale parsing, exactly the
//     VARCHAR convention member_invite.go uses for expires_at/accepted_at. So NO
//     Instant.parse / LocalDate.parse hazard applies to this field.
//   - the savings WITHDRAWAL transactionDate is a REAL Fineract Date field: Fineract's
//     savings-transaction command parses a DATE-ONLY "dd MMMM yyyy" string via the
//     accompanying dateFormat+locale. This facade derives it from the DATE portion of
//     the request's executedAt (the app's declared execution moment — the app owns the
//     transaction date, exactly the philosophy loan_write.go follows), reformatted from
//     ISO-8601 "YYYY-MM-DD..." to "dd MMMM yyyy". It never sends the raw ISO instant (a
//     full instant would need a different dateFormat and Fineract savings transactions
//     are date-only), and it deliberately does NOT trust the facade host's own wall
//     clock — tying the transaction date to the caller's executedAt keeps it independent
//     of any host/server clock skew. Falls back to host time.Now() only when executedAt
//     is empty/unparseable.
//   - the datatable Decimal/Number columns (total_pool, amount, *_count,
//     rotation_position) parse with locale="en" only; there are no Fineract Date
//     columns on either datatable, so no dateFormat is sent on the row write.
package companion

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"
	"time"
)

// shareOutTable / roscaRotationTable are the Fineract datatables (apptable m_group)
// the distribution records are written to. Provisioned idempotently via
// ensureDatatable (POST /datatables, skipped when already registered), same
// out-of-band-but-safe convention as groupTypeConfigTable / invitationsTable.
const (
	shareOutTable      = "dt_share_out"
	roscaRotationTable = "dt_rosca_rotation"
	groupApptable      = "m_group"
)

// registerShareOutRoutes wires the COMP-DIST-001/002 write endpoints. Called from
// RegisterRoutes. The two POST patterns are disjoint from every other companion
// route (savings.go serves GET .../savings, groups.go serves GET .../{groupId}).
func (h *Handler) registerShareOutRoutes(mux *http.ServeMux) {
	mux.HandleFunc("POST /companion/groups/{groupId}/shareout/execute", h.HandleExecuteShareOut)
	mux.HandleFunc("POST /companion/groups/{groupId}/rotation/execute", h.HandleExecuteRotationPayout)
}

// ---- App-facing wire contract (exact match to core/network/.../model/ShareOutDto.kt) ----

// memberPayoutRequestDto == MemberPayoutRequestDto (narrow execute-request row).
type memberPayoutRequestDto struct {
	MemberID     string  `json:"memberId"`
	PayoutAmount float64 `json:"payoutAmount"`
	SharePercent float64 `json:"sharePercent"`
}

// shareOutExecuteRequestDto == ShareOutExecuteRequestDto (COMP-DIST-001 body).
type shareOutExecuteRequestDto struct {
	CycleNumber     int                      `json:"cycleNumber"`
	TotalPool       float64                  `json:"totalPool"`
	MemberPayouts   []memberPayoutRequestDto `json:"memberPayouts"`
	ShareoutFormula string                   `json:"shareoutFormula"`
	ExecutedAt      string                   `json:"executedAt"`
}

// shareOutExecuteResponseDto == ShareOutExecuteResponseDto. FailedMemberIDs is a
// non-nil slice so a fully-successful response serializes "failedMemberIds":[]
// (matching the app DTO's `= emptyList()` default).
type shareOutExecuteResponseDto struct {
	ShareoutRecordID string   `json:"shareoutRecordId"`
	SucceededCount   int      `json:"succeededCount"`
	FailedCount      int      `json:"failedCount"`
	FailedMemberIDs  []string `json:"failedMemberIds"`
}

// rotationPayoutExecuteRequestDto == RotationPayoutExecuteRequestDto (COMP-DIST-002 body).
type rotationPayoutExecuteRequestDto struct {
	RecipientMemberID string  `json:"recipientMemberId"`
	Amount            float64 `json:"amount"`
	PayoutOrderMethod string  `json:"payoutOrderMethod"`
	ExecutedAt        string  `json:"executedAt"`
}

// rotationPayoutExecuteResponseDto == RotationPayoutExecuteResponseDto. NextRecipientID
// is a *string so it serializes as JSON null on the final round (matching the app DTO's
// `String? = null`).
type rotationPayoutExecuteResponseDto struct {
	RotationRecordID    string  `json:"rotationRecordId"`
	NewRotationPosition int     `json:"newRotationPosition"`
	NextRecipientID     *string `json:"nextRecipientId"`
}

// ---- Handlers ----

// HandleExecuteShareOut (COMP-DIST-001) drains the group corpus back to members: one
// real savings withdrawal per member payout, then records a dt_share_out row. Per-member
// failures are collected (never abort the batch) and reported as failedMemberIds — the
// PartialFailure state the app's ShareOutExecuteResponseDto derives.
func (h *Handler) HandleExecuteShareOut(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	var req shareOutExecuteRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if len(req.MemberPayouts) == 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "memberPayouts is required")
		return
	}

	// Resolve the group's corpus savings account (the accumulated pool to distribute).
	corpusID, err := h.corpusSavingsAccount(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if corpusID == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "group has no active savings account to share out")
		return
	}

	// Drain each member's payout from the corpus. Collect per-member outcomes.
	paymentTypeID := h.resolvePaymentTypeID()
	failedMemberIDs := make([]string, 0)
	succeeded := 0
	for _, p := range req.MemberPayouts {
		if p.PayoutAmount <= 0 {
			// A zero/negative payout is nothing to withdraw — count as succeeded (no-op share).
			succeeded++
			continue
		}
		if err := h.fineractWithdraw(corpusID, p.PayoutAmount, req.ExecutedAt, paymentTypeID); err != nil {
			failedMemberIDs = append(failedMemberIDs, p.MemberID)
			continue
		}
		succeeded++
	}

	// Record the distribution in dt_share_out (provisioned idempotently first).
	recordID, err := h.recordShareOut(gid, req, succeeded, len(failedMemberIDs))
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "record share-out: "+err.Error())
		return
	}

	_ = json.NewEncoder(w).Encode(shareOutExecuteResponseDto{
		ShareoutRecordID: strconv.FormatInt(recordID, 10),
		SucceededCount:   succeeded,
		FailedCount:      len(failedMemberIDs),
		FailedMemberIDs:  failedMemberIDs,
	})
}

// HandleExecuteRotationPayout (COMP-DIST-002) pays the single ROSCA/chit recipient: one
// real savings withdrawal from the corpus, then advances rosca_rotation (a dt_rosca_rotation
// row) and returns the new position + the next recipient (null on the final round).
func (h *Handler) HandleExecuteRotationPayout(w http.ResponseWriter, r *http.Request) {
	setJSON(w)
	gid, ok := groupIDParam(w, r)
	if !ok {
		return
	}
	var req rotationPayoutExecuteRequestDto
	if err := json.NewDecoder(r.Body).Decode(&req); err != nil {
		writeErr(w, http.StatusBadRequest, "bad_request", "invalid JSON body")
		return
	}
	if strings.TrimSpace(req.RecipientMemberID) == "" {
		writeErr(w, http.StatusBadRequest, "bad_request", "recipientMemberId is required")
		return
	}
	if req.Amount <= 0 {
		writeErr(w, http.StatusBadRequest, "bad_request", "amount must be positive")
		return
	}

	corpusID, err := h.corpusSavingsAccount(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", err.Error())
		return
	}
	if corpusID == 0 {
		writeErr(w, http.StatusNotFound, "not_found", "group has no active savings account to pay out")
		return
	}

	// Single recipient -> a failed withdrawal fails the whole call (no partial state here).
	if err := h.fineractWithdraw(corpusID, req.Amount, req.ExecutedAt, h.resolvePaymentTypeID()); err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "rotation withdrawal: "+err.Error())
		return
	}

	// Advance the rotation: newPosition = completed rounds so far + 1.
	_, members, gerr := h.aggregateGroup(gid)
	if gerr != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", gerr.Error())
		return
	}
	newPosition, err := h.nextRotationPosition(gid)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "read rotation state: "+err.Error())
		return
	}
	recordID, err := h.recordRotation(gid, req, newPosition)
	if err != nil {
		writeErr(w, http.StatusBadGateway, "upstream_error", "record rotation: "+err.Error())
		return
	}

	_ = json.NewEncoder(w).Encode(rotationPayoutExecuteResponseDto{
		RotationRecordID:    strconv.FormatInt(recordID, 10),
		NewRotationPosition: newPosition,
		NextRecipientID:     nextRecipient(members, req.RecipientMemberID),
	})
}

// ---- Fineract writes (service credential via DoRequest) ----

// corpusSavingsAccount resolves the group's corpus savings account id (the accumulated
// pool the share-out distributes). Reuses aggregateSavings (groups/{id}/accounts) — the
// same active-savings read the dashboard/savings facades use. Returns 0 (not an error)
// when the group holds no active savings account.
func (h *Handler) corpusSavingsAccount(groupID int64) (int64, error) {
	_, ids, err := h.aggregateSavings(groupID)
	if err != nil {
		return 0, err
	}
	if len(ids) == 0 {
		return 0, nil
	}
	return ids[0], nil
}

// fineractWithdraw posts a real savings WITHDRAWAL transaction of amount against the
// corpus account. transactionDate is the DATE portion of the app's executedAt reformatted
// to Fineract's "dd MMMM yyyy" (with dateFormat+locale so Fineract parses it) — see
// fineractTxnDate. paymentTypeId is included because some tenants (mifos-bank-2) mark it
// mandatory for savings transactions; a positive value is always accepted where it is
// merely optional, so sending a resolved one is universally safe.
func (h *Handler) fineractWithdraw(savingsID int64, amount float64, executedAt string, paymentTypeID int64) error {
	body := map[string]interface{}{
		"transactionDate":   fineractTxnDate(executedAt),
		"transactionAmount": amount,
		"dateFormat":        "dd MMMM yyyy",
		"locale":            "en",
	}
	if paymentTypeID > 0 {
		body["paymentTypeId"] = paymentTypeID
	}
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("savingsaccounts/%d/transactions", savingsID), body, map[string]string{"command": "withdrawal"})
	if err != nil {
		return fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	return nil
}

// resolvePaymentTypeID returns a valid Fineract payment-type id for the withdrawal
// (lowest id from GET /paymenttypes), falling back to 1 when the list is empty/unreadable.
// Share-out is a rare year-end operation, so the extra read per execute is negligible and
// keeps the facade tenant-agnostic rather than hardcoding a magic id.
func (h *Handler) resolvePaymentTypeID() int64 {
	raw, err := h.Fineract.DoRequest("GET", "paymenttypes", nil, nil)
	if err != nil {
		return 1
	}
	var types []struct {
		ID int64 `json:"id"`
	}
	if err := json.Unmarshal(raw, &types); err != nil || len(types) == 0 {
		return 1
	}
	lowest := types[0].ID
	for _, t := range types {
		if t.ID > 0 && t.ID < lowest {
			lowest = t.ID
		}
	}
	if lowest <= 0 {
		return 1
	}
	return lowest
}

// fineractTxnDate turns the app's executedAt into Fineract's date-only "dd MMMM yyyy".
// It accepts a full ISO-8601 instant ("2026-08-01T09:30:00Z"), a bare date
// ("2026-08-01"), or anything with a leading "YYYY-MM-DD" prefix, and returns that
// calendar day. Empty/unparseable input falls back to the host's today — the only case
// where the facade's own wall clock is used.
func fineractTxnDate(executedAt string) string {
	const out = "02 January 2006"
	s := strings.TrimSpace(executedAt)
	if len(s) >= 10 {
		if t, err := time.Parse("2006-01-02", s[:10]); err == nil {
			return t.Format(out)
		}
	}
	if t, err := time.Parse(time.RFC3339, s); err == nil {
		return t.Format(out)
	}
	return time.Now().Format(out)
}

// recordShareOut provisions dt_share_out (idempotent) then writes one distribution row,
// returning its Fineract row id (the shareoutRecordId).
func (h *Handler) recordShareOut(groupID int64, req shareOutExecuteRequestDto, succeeded, failed int) (int64, error) {
	if err := h.ensureDatatable(shareOutTable, groupApptable, []map[string]interface{}{
		{"name": "cycle_number", "type": "Number", "mandatory": false},
		{"name": "total_pool", "type": "Decimal", "mandatory": false},
		{"name": "shareout_formula", "type": "String", "length": 64, "mandatory": false},
		{"name": "executed_at", "type": "String", "length": 64, "mandatory": false},
		{"name": "succeeded_count", "type": "Number", "mandatory": false},
		{"name": "failed_count", "type": "Number", "mandatory": false},
	}); err != nil {
		return 0, err
	}
	row := map[string]interface{}{
		"cycle_number":     req.CycleNumber,
		"total_pool":       req.TotalPool,
		"shareout_formula": req.ShareoutFormula,
		"executed_at":      req.ExecutedAt,
		"succeeded_count":  succeeded,
		"failed_count":     failed,
		"locale":           "en",
	}
	return h.writeDatatableRow(shareOutTable, groupID, row)
}

// recordRotation provisions dt_rosca_rotation (idempotent) then writes one rotation row,
// returning its Fineract row id (the rotationRecordId).
func (h *Handler) recordRotation(groupID int64, req rotationPayoutExecuteRequestDto, position int) (int64, error) {
	if err := h.ensureDatatable(roscaRotationTable, groupApptable, []map[string]interface{}{
		{"name": "recipient_client_id", "type": "Number", "mandatory": false},
		{"name": "amount", "type": "Decimal", "mandatory": false},
		{"name": "payout_order_method", "type": "String", "length": 32, "mandatory": false},
		{"name": "executed_at", "type": "String", "length": 64, "mandatory": false},
		{"name": "rotation_position", "type": "Number", "mandatory": false},
	}); err != nil {
		return 0, err
	}
	recipientID, _ := strconv.ParseInt(req.RecipientMemberID, 10, 64)
	row := map[string]interface{}{
		"recipient_client_id": recipientID,
		"amount":              req.Amount,
		"payout_order_method": req.PayoutOrderMethod,
		"executed_at":         req.ExecutedAt,
		"rotation_position":   position,
		"locale":              "en",
	}
	return h.writeDatatableRow(roscaRotationTable, groupID, row)
}

// nextRotationPosition returns the position for the NEXT rotation row = existing row
// count + 1 (rounds completed so far, advanced by this payout).
func (h *Handler) nextRotationPosition(groupID int64) (int, error) {
	raw, err := h.Fineract.DoRequest("GET", fmt.Sprintf("datatables/%s/%d", roscaRotationTable, groupID), nil, nil)
	if err != nil {
		// A not-yet-provisioned datatable 400s on read — that just means zero prior rounds.
		return 1, nil
	}
	var rows []json.RawMessage
	if err := json.Unmarshal(raw, &rows); err != nil {
		return 1, nil
	}
	return len(rows) + 1, nil
}

// writeDatatableRow inserts one row into the group's datatable and returns the new row id.
func (h *Handler) writeDatatableRow(table string, groupID int64, row map[string]interface{}) (int64, error) {
	raw, err := h.Fineract.DoRequest("POST", fmt.Sprintf("datatables/%s/%d", table, groupID), row, nil)
	if err != nil {
		return 0, fmt.Errorf("%s", strings.TrimSpace(string(nonEmpty(raw))))
	}
	var resp struct {
		ResourceID int64 `json:"resourceId"`
	}
	if err := json.Unmarshal(raw, &resp); err != nil {
		return 0, fmt.Errorf("decode datatable-write response: %w", err)
	}
	if resp.ResourceID == 0 {
		return 0, fmt.Errorf("fineract returned no row id: %s", string(raw))
	}
	return resp.ResourceID, nil
}

// ensureDatatable registers a datatable against apptable idempotently. It first checks
// the registered-datatable list; if absent it POSTs /datatables, and treats an
// "already exists / registered / duplicate" upstream rejection as success (defence in
// depth against a race or a list-field-name mismatch) — the same idempotent contract
// group_create.go documents for its out-of-band datatable provisioning.
func (h *Handler) ensureDatatable(name, apptable string, columns []map[string]interface{}) error {
	if h.datatableExists(name) {
		return nil
	}
	body := map[string]interface{}{
		"datatableName": name,
		"apptableName":  apptable,
		"multiRow":      true,
		"columns":       columns,
	}
	raw, err := h.Fineract.DoRequest("POST", "datatables", body, nil)
	if err != nil {
		msg := strings.ToLower(strings.TrimSpace(string(nonEmpty(raw))))
		if strings.Contains(msg, "exist") || strings.Contains(msg, "already") ||
			strings.Contains(msg, "registered") || strings.Contains(msg, "duplicate") ||
			strings.Contains(msg, "unique") {
			return nil // already provisioned — idempotent success
		}
		return fmt.Errorf("register %s: %s", name, msg)
	}
	return nil
}

// datatableExists reports whether a datatable is already registered. Fineract's
// GET /datatables returns an array whose rows carry registeredTableName; a decode
// failure or upstream error fails soft to false (the POST's already-exists guard then
// keeps ensureDatatable idempotent).
func (h *Handler) datatableExists(name string) bool {
	raw, err := h.Fineract.DoRequest("GET", "datatables", nil, nil)
	if err != nil {
		return false
	}
	var tables []struct {
		RegisteredTableName  string `json:"registeredTableName"`
		ApplicationTableName string `json:"applicationTableName"`
	}
	if err := json.Unmarshal(raw, &tables); err != nil {
		return false
	}
	for _, t := range tables {
		if t.RegisteredTableName == name {
			return true
		}
	}
	return false
}

// ---- helpers ----

// nextRecipient returns the memberId (client-id string) of the member AFTER recipientID
// in the deterministic rotation order (member id ascending), or nil when the recipient is
// the last member (the final round has no next recipient). An unknown recipient -> nil.
func nextRecipient(members []memberRef, recipientID string) *string {
	if len(members) == 0 {
		return nil
	}
	ordered := make([]memberRef, len(members))
	copy(ordered, members)
	sort.SliceStable(ordered, func(i, j int) bool { return ordered[i].ID < ordered[j].ID })
	for i, m := range ordered {
		if strconv.FormatInt(m.ID, 10) == recipientID {
			if i+1 < len(ordered) {
				next := strconv.FormatInt(ordered[i+1].ID, 10)
				return &next
			}
			return nil // recipient is last in the order -> final round
		}
	}
	return nil
}
