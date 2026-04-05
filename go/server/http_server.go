// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"strings"

	"github.com/mark3labs/mcp-go/mcp"
	"github.com/mark3labs/mcp-go/server"
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

func (s *MifosHTTPServer) Serve() error {
	sseServer := server.NewSSEServer(s.McpServer.MCPServer)

	mux := http.NewServeMux()
	mux.Handle("/sse", sseServer.SSEHandler())
	mux.Handle("/message", sseServer.MessageHandler())
	mux.Handle("/metrics", promhttp.Handler())

	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(map[string]string{"status": "UP", "version": "1.0.0-go"})
	})

	for _, def := range s.McpServer.Registry.ToolDefs {
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
		w.Header().Set("Access-Control-Allow-Headers", "Content-Type, X-Mifos-Tenant-Id")

		if r.Method == "OPTIONS" {
			w.WriteHeader(http.StatusOK)
			return
		}

		mux.ServeHTTP(w, r)
	})

	log.Printf("Starting Headless MCP Server on http://localhost:%s", s.Port)
	log.Printf("SSE Endpoint: http://localhost:%s/sse", s.Port)
	log.Printf("REST API Base: http://localhost:%s/api/", s.Port)

	return http.ListenAndServe(":"+s.Port, handler)
}

func (s *MifosHTTPServer) handleRESTToolCall(w http.ResponseWriter, r *http.Request, def tools.BaseToolDef) {
	body := make(map[string]interface{})
	queryParams := make(map[string]string)

	if r.Method == "POST" || r.Method == "PUT" {
		if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
			http.Error(w, "Invalid JSON body", http.StatusBadRequest)
			return
		}
	}

	for k, v := range r.URL.Query() {
		queryParams[k] = v[0]
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

	endpoint := def.EndpointURL

	for _, p := range def.Params {
		if p.IsPathVar {
			val, ok := body[p.Name]
			if !ok {
				val = queryParams[p.Name]
			}
			if val != nil {
				endpoint = strings.Replace(endpoint, "%v", fmt.Sprintf("%v", val), 1)
				delete(body, p.Name)
				delete(queryParams, p.Name)
			}
		}
	}

	respData, err := s.McpServer.Registry.Fineract.DoRequest(def.Method, endpoint, body, queryParams)
	if err != nil {
		log.Printf("[REST Error] Tool: %s, Endpoint: %s, Error: %v", def.Name, endpoint, err)
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusInternalServerError)
		if len(respData) > 0 {
			w.Write(respData)
		} else {
			json.NewEncoder(w).Encode(map[string]string{"error": err.Error()})
		}
		return
	}

	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(http.StatusOK)
	w.Write(respData)
}
