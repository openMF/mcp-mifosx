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
	"sync"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"
	"github.com/prometheus/client_golang/prometheus/promhttp"
)

type MifosHTTPServer struct {
	McpServer *MifosMcpServer
	Port      string
}

func NewHTTPServer(srv *MifosMcpServer, port string) *MifosHTTPServer {
	return &MifosHTTPServer{
		McpServer: srv,
		Port:      port,
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

	// Always build the restricted catalog here, even if a caller constructs
	// MifosHTTPServer directly with the full stdio server.
	pilot := newCallerScopedHTTPServer(s.McpServer.Registry.Fineract, hooks)
	sseServer := server.NewSSEServer(pilot.MCPServer)

	mux := http.NewServeMux()
	mux.Handle("/sse", sseServer.SSEHandler())
	mux.Handle("/message", sseServer.MessageHandler())
	mux.Handle("/metrics", promhttp.Handler())

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "UP", "version": "1.0.0-go"})
	})

	for _, def := range pilot.Registry.ToolDefs {
		d := def
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

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		if r.URL.Path == "/sse" || r.URL.Path == "/message" || r.URL.Path == "/api/get_client" || r.URL.Path == "/api/search_clients" || r.URL.Path == "/api/get_client_accounts" {
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
