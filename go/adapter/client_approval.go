// Copyright since 2026 Mifos Initiative
// This Source Code Form is subject to the Mozilla Public License, v. 2.0.
package adapter

import (
	"bytes"
	"context"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

// ClientCreation is deliberately limited to the existing create_client fields.
// No commands, endpoints, approval flags or execution identities come from tool arguments.
type ClientCreation struct {
	Firstname      string `json:"firstname"`
	Lastname       string `json:"lastname"`
	OfficeID       int64  `json:"officeId"`
	Active         *bool  `json:"active"`
	ActivationDate string `json:"activationDate"`
	DateFormat     string `json:"dateFormat"`
	Locale         string `json:"locale"`
}

func ParseClientCreation(data []byte) (ClientCreation, error) {
	var action ClientCreation
	dec := json.NewDecoder(bytes.NewReader(data))
	dec.DisallowUnknownFields()
	if dec.Decode(&action) != nil || dec.Decode(new(any)) != io.EOF || action.OfficeID <= 0 || action.Active == nil {
		return ClientCreation{}, errors.New("invalid create_client arguments")
	}
	for _, value := range []string{action.Firstname, action.Lastname, action.ActivationDate, action.DateFormat, action.Locale} {
		if strings.TrimSpace(value) == "" || len(value) > 200 || strings.ContainsAny(value, "\x00\r\n") {
			return ClientCreation{}, errors.New("invalid create_client arguments")
		}
	}
	return action, nil
}

// ApprovalBridge has no pending state. The gateway's ApprovalStore remains the
// sole authority for expiry, identity binding, rejection and atomic consumption.
type ApprovalBridge struct {
	baseURL, token string
	http           *http.Client
}

func NewApprovalBridge() *ApprovalBridge {
	base := strings.TrimRight(os.Getenv("MIFOSX_APPROVAL_GATEWAY_URL"), "/")
	token := os.Getenv("MCP_APPROVAL_BRIDGE_TOKEN")
	u, err := url.Parse(base)
	if err != nil || u.Host == "" || (u.Scheme != "https" && u.Scheme != "http") || u.User != nil || u.RawQuery != "" || u.Fragment != "" || len(token) < 32 || strings.ContainsAny(token, "\r\n") {
		base, token = "", ""
	}
	return &ApprovalBridge{baseURL: base, token: token, http: &http.Client{
		Timeout:       45 * time.Second,
		CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse },
	}}
}

func (b *ApprovalBridge) Authorized(token string) bool {
	return b.token != "" && subtle.ConstantTimeCompare([]byte(token), []byte(b.token)) == 1
}

func (b *ApprovalBridge) request(ctx context.Context, path, session, ticket string, body any) ([]byte, error) {
	call, err := callerContext(ctx)
	if err != nil {
		return nil, err
	}
	if b.baseURL == "" || session == "" {
		return nil, errors.New("human approval gateway is unavailable")
	}
	data, err := json.Marshal(body)
	if err != nil {
		return nil, errors.New("invalid approval request")
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, b.baseURL+"/copilot/api/v1/internal/mcp/"+path, bytes.NewReader(data))
	if err != nil {
		return nil, errors.New("invalid approval request")
	}
	req.Header.Set("Authorization", call.authorization)
	req.Header.Set("Fineract-Platform-TenantId", call.tenant)
	req.Header.Set("X-MCP-Session-Id", session)
	req.Header.Set("X-MCP-Approval-Bridge", b.token)
	if ticket != "" {
		req.Header.Set("X-MCP-Decision-Token", ticket)
	}
	req.Header.Set("Content-Type", "application/json")
	resp, err := b.http.Do(req)
	if err != nil {
		return nil, errors.New("human approval gateway request failed")
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, errors.New("human approval is unavailable or no longer valid")
	}
	data, err = io.ReadAll(io.LimitReader(resp.Body, 65537))
	if err != nil || len(data) > 65536 {
		return nil, errors.New("invalid approval gateway response")
	}
	return safeCallerResponse(data, call)
}

func (c *CallerClient) ProposeClient(ctx context.Context, b *ApprovalBridge, session string, args any) ([]byte, error) {
	raw, err := json.Marshal(args)
	if err != nil {
		return nil, errors.New("invalid create_client arguments")
	}
	action, err := ParseClientCreation(raw)
	if err != nil {
		return nil, err
	}
	permission, err := c.ClientPermissions(ctx)
	if err != nil || !permission.Create {
		return nil, errors.New("create_client is not available for the authenticated caller")
	}
	payload, _ := json.Marshal(action)
	call, err := callerContext(ctx)
	if err != nil {
		return nil, err
	}
	if _, err = safeCallerResponse(payload, call); err != nil {
		return nil, errors.New("invalid create_client arguments")
	}
	return b.request(ctx, "proposals", session, "", map[string]string{"payload": string(payload)})
}

var approvalCardID = regexp.MustCompile(`^card-[a-f0-9-]{36}$`)

// ExecuteApprovedClient is reachable only from the trusted gateway callback.
// It fetches the immutable payload through ApprovalStore.take; even duplicate
// or concurrent callbacks cannot claim the same action twice. Never retry writes.
func (c *CallerClient) ExecuteApprovedClient(ctx context.Context, b *ApprovalBridge, session, cardID, ticket string) (data []byte, resultErr error) {
	call, err := callerContext(ctx)
	if err != nil {
		return nil, err
	}
	if !approvalCardID.MatchString(cardID) {
		return nil, errors.New("invalid approval")
	}
	permission, err := c.ClientPermissions(ctx)
	if err != nil || !permission.Create {
		return nil, errors.New("create_client is not available for the authenticated caller")
	}
	raw, err := b.request(ctx, "claims/"+cardID, session, ticket, struct{}{})
	if err != nil {
		return nil, err
	}
	// A successful claim means executing was durably recorded. Report a bounded,
	// typed outcome even if the incoming callback is cancelled. Never retry the POST.
	outcome := clientAuditOutcome{FailureClass: "invalid_claim"}
	defer func() {
		reportCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 10*time.Second)
		defer cancel()
		if _, err := b.request(reportCtx, "results/"+cardID, session, ticket, outcome); err != nil {
			data = nil
			resultErr = errors.New("create_client outcome could not be audited; do not retry")
		}
	}()
	var claim struct {
		Payload        string `json:"payload"`
		IdempotencyKey string `json:"idempotency_key"`
		CardID         string `json:"card_id"`
	}
	if json.Unmarshal(raw, &claim) != nil || claim.CardID != cardID || !regexp.MustCompile(`^cop-[a-f0-9-]{36}$`).MatchString(claim.IdempotencyKey) {
		return nil, errors.New("invalid approval gateway response")
	}
	if _, err := ParseClientCreation([]byte(claim.Payload)); err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.baseURL+"/clients", strings.NewReader(claim.Payload))
	if err != nil {
		return nil, errors.New("could not construct Fineract request")
	}
	// net/http otherwise treats Idempotency-Key plus GetBody as permission to
	// replay a POST on a reused connection. The store grants only one attempt.
	req.GetBody = nil
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("Idempotency-Key", claim.IdempotencyKey)
	req.Header.Set("X-Correlation-Id", cardID)
	data, status, err := c.doCallerRequestWithStatus(req, call)
	outcome.HTTPStatus = status
	if err != nil {
		switch {
		case status == 0:
			outcome.FailureClass = "transport_error"
		case status < 200 || status >= 300:
			outcome.FailureClass = "http_error"
		default:
			outcome.FailureClass = "response_error"
		}
		return nil, err
	}
	// Record the actual Fineract outcome even when response screening refuses
	// to return a body that echoes credentials. No response body enters the audit.
	outcome.Success, outcome.FailureClass = true, ""
	return safeCallerResponse(data, call)
}

// Correlation is the approval card ID; the idempotency key remains solely a
// duplicate-execution safeguard. Neither the key nor payload is outcome metadata.
type clientAuditOutcome struct {
	Success      bool   `json:"success"`
	HTTPStatus   int    `json:"httpStatus"`
	FailureClass string `json:"failureClass,omitempty"`
}
