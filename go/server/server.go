// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"
)

type MifosMcpServer struct {
	MCPServer *mcpserver.MCPServer
	Registry  *tools.Registry
}

func NewMifosMcpServer(fineractClient *adapter.FineractClient) *MifosMcpServer {
	appServer := mcpserver.NewMCPServer("Mifos-Banking-Agent-Go", "1.0.0")

	registry := &tools.Registry{
		Server:   appServer,
		Fineract: fineractClient,
	}

	registry.RegisterAllTools()

	return &MifosMcpServer{
		MCPServer: appServer,
		Registry:  registry,
	}
}

func (s *MifosMcpServer) Serve() error {
	return mcpserver.ServeStdio(s.MCPServer)
}
