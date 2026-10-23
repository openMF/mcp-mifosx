// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"context"
	"encoding/json"
	"log"
	"net/http"
	"strings"
	"sync"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type MifosHTTPServer struct {
	caller *adapter.CallerClient
	Port   string
}

// NewHTTPServer accepts only a caller executor, never a configured local server.
func NewHTTPServer(caller *adapter.CallerClient, port string) *MifosHTTPServer {
	return &MifosHTTPServer{
		caller: caller,
		Port:   port,
	}
}

// Handler exposes only the caller-scoped HTTP pilot, independent of stdio.
func (s *MifosHTTPServer) Handler() http.Handler {
	// Ownership lives only as long as the transport session. Hooks run before
	// the endpoint is advertised and when the SSE connection is torn down.
	var owners sync.Map // session ID -> caller fingerprint; no raw credentials.
	hooks := &server.Hooks{}
	hooks.AddOnRegisterSession(func(ctx context.Context, session server.ClientSession) {
		if fingerprint, err := adapter.CallerFingerprint(ctx); err == nil {
			owners.Store(session.SessionID(), fingerprint)
		}
	})
	hooks.AddOnUnregisterSession(func(_ context.Context, session server.ClientSession) {
		owners.Delete(session.SessionID())
	})

	// The restricted catalog has no reference to the configured executor.
	bridge := adapter.NewApprovalBridge()
	owns := func(ctx context.Context, id string) bool {
		fingerprint, err := adapter.CallerFingerprint(ctx)
		owner, ok := owners.Load(id)
		return err == nil && ok && owner == fingerprint
	}
	pilot := newCallerScopedHTTPServer(s.caller, hooks)
	pilot.Registry.RegisterCreateClientProposal(func(ctx context.Context, req mcp.CallToolRequest) (*mcp.CallToolResult, error) {
		session := server.ClientSessionFromContext(ctx)
		if session == nil || !owns(ctx, session.SessionID()) {
			return mcp.NewToolResultError("a live owned MCP session is required"), nil
		}
		data, err := s.caller.ProposeClient(ctx, bridge, session.SessionID(), req.Params.Arguments)
		if err != nil {
			return mcp.NewToolResultError(err.Error()), nil
		}
		return mcp.NewToolResultText(string(data)), nil
	})
	sseServer := server.NewSSEServer(pilot.MCPServer)

	mux := http.NewServeMux()
	mux.Handle("/sse", sseServer.SSEHandler())
	mux.Handle("/message", sseServer.MessageHandler())
	mux.Handle("/metrics", promhttp.Handler())
	mux.HandleFunc("/api/capabilities", s.handleCapabilities)
	// Deliberately outside the tool catalog and REST tool dispatcher. Only the
	// human-decision gateway can invoke execution, never an MCP tool request.
	mux.HandleFunc("/internal/approved-create-client/", func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}
		if !bridge.Authorized(r.Header.Get("X-MCP-Approval-Bridge")) || !owns(r.Context(), r.Header.Get("X-MCP-Session-Id")) {
			http.Error(w, "approval execution is not authorized", http.StatusForbidden)
			return
		}
		data, err := s.caller.ExecuteApprovedClient(r.Context(), bridge, r.Header.Get("X-MCP-Session-Id"), strings.TrimPrefix(r.URL.Path, "/internal/approved-create-client/"), r.Header.Get("X-MCP-Decision-Token"))
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(data)
	})

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "UP", "version": "1.0.0-go"})
	})

	for _, def := range pilot.Registry.ToolDefs {
		d := def
		// Writes require the MCP session; no sessionless REST proposal route.
		if d.Name == "create_client" {
			continue
		}
		path := "/api/" + d.Name
		mux.HandleFunc(path, func(w http.ResponseWriter, r *http.Request) {
			s.handleRESTToolCall(w, r, d)
		})
	}

	handler := http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		log.Printf("[HTTP] %s %s from %s", r.Method, r.URL.Path, r.RemoteAddr)

		w.Header().Set("Access-Control-Allow-Origin", "*")
		w.Header().Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Mifos-Tenant-Id")
		if r.URL.Path == "/api/capabilities" {
			w.Header().Set("Cache-Control", "no-store")
		}

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		if strings.HasPrefix(r.URL.Path, "/internal/approved-create-client/") || r.URL.Path == "/sse" || r.URL.Path == "/message" || r.URL.Path == "/api/get_client" || r.URL.Path == "/api/search_clients" || r.URL.Path == "/api/get_client_accounts" || r.URL.Path == "/api/capabilities" {
			// Exactly one value per identity header; no query/body/session defaults.
			auth, tenant := r.Header.Values("Authorization"), r.Header.Values("X-Mifos-Tenant-Id")
			if len(auth) != 1 || len(tenant) != 1 {
				http.Error(w, "caller Authorization and tenant headers are required", http.StatusUnauthorized)
				return
			}
			ctx, err := adapter.WithCallerContext(r.Context(), auth[0], tenant[0])
			if err != nil {
				http.Error(w, err.Error(), http.StatusUnauthorized)
				return
			}
			r = r.WithContext(ctx)
		}
		if r.URL.Path == "/message" {
			fingerprint, err := adapter.CallerFingerprint(r.Context())
			owner, ok := owners.Load(r.URL.Query().Get("sessionId"))
			if err != nil || !ok || owner != fingerprint {
				http.Error(w, "session does not belong to the authenticated caller", http.StatusForbidden)
				return
			}
		}
		mux.ServeHTTP(w, r)
	})
	return handler
}

func (s *MifosHTTPServer) handleCapabilities(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}
	capabilities, err := tools.CallerCapabilities(r.Context(), s.caller)
	if err != nil {
		http.Error(w, "caller permissions could not be verified", http.StatusBadGateway)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(struct {
		Tools []tools.ToolCapability `json:"tools"`
	}{Tools: capabilities}); err != nil {
		log.Printf("[HTTP] encode capabilities response: %v", err)
	}
}

func (s *MifosHTTPServer) Serve() error {
	log.Printf("Starting Headless MCP Server on http://localhost:%s", s.Port)
	log.Printf("SSE Endpoint: http://localhost:%s/sse", s.Port)
	log.Printf("REST API Base: http://localhost:%s/api/", s.Port)

	return http.ListenAndServe(":"+s.Port, s.Handler())
}

func (s *MifosHTTPServer) handleRESTToolCall(w http.ResponseWriter, r *http.Request, def tools.BaseToolDef) {
	body := make(map[string]interface{})

	if r.Method == "POST" || r.Method == "PUT" {
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "Invalid JSON body", http.StatusBadRequest)
			return
		}
	}

	for k, v := range r.URL.Query() {
		body[k] = v[0]
	}

	if def.Handler != nil {
		// REST bypasses the MCP SDK's call-time tool filter, so apply the same
		// capability decision here before reaching the caller-scoped handler.
		capabilities, err := tools.CallerCapabilities(r.Context(), s.caller)
		if err != nil || !tools.ToolAvailable(capabilities, def.Name) {
			http.Error(w, "tool is not available for the authenticated caller", http.StatusForbidden)
			return
		}
		req := mcp.CallToolRequest{
			Params: mcp.CallToolParams{
				Name:      def.Name,
				Arguments: body,
			},
		}
		result, err := def.Handler(r.Context(), req)
		if err != nil {
			http.Error(w, err.Error(), http.StatusInternalServerError)
			return
		}
		if result.IsError {
			w.WriteHeader(http.StatusInternalServerError)
		} else {
			w.WriteHeader(http.StatusOK)
		}
		for _, msgRaw := range result.Content {
			switch msg := msgRaw.(type) {
			case mcp.TextContent:
				w.Write([]byte(msg.Text))
			}
		}
		return
	}

	http.Error(w, "tool is not supported in caller-scoped HTTP mode", http.StatusForbidden)
}
