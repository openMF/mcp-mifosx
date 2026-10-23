// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package server

import (
	"context"

	"github.com/mark3labs/mcp-go/mcp"
	mcpserver "github.com/mark3labs/mcp-go/server"
	"github.com/openMF/mcp-mifosx/go/adapter"
	"github.com/openMF/mcp-mifosx/go/tools"
)

type MifosMcpServer struct {
	MCPServer *mcpserver.MCPServer
	Registry  *tools.Registry
}

// NewMifosMcpServer builds the trusted local catalog with configured identity.
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

// newCallerScopedHTTPServer uses a separate catalog so HTTP cannot reach
// configured-credential tools, including handlers registered outside ToolDefs.
func newCallerScopedHTTPServer(client *adapter.CallerClient, hooks *mcpserver.Hooks) *MifosMcpServer {
	filter := callerToolFilter(func(ctx context.Context) ([]tools.ToolCapability, error) {
		return tools.CallerCapabilities(ctx, client)
	})
	appServer := mcpserver.NewMCPServer("Mifos-Banking-Agent-Go", "1.0.0",
		mcpserver.WithHooks(hooks), mcpserver.WithToolFilter(filter))
	registry := &tools.Registry{Server: appServer}
	registry.RegisterCallerScopedHTTPTools(client)
	return &MifosMcpServer{MCPServer: appServer, Registry: registry}
}

// The SDK applies this filter before list pagination and independently before
// invoking a handler. Resolve once per MCP request; never mutate the registry
// or retain capabilities across caller/tenant contexts.
func callerToolFilter(resolve func(context.Context) ([]tools.ToolCapability, error)) mcpserver.ToolFilterFunc {
	return func(ctx context.Context, candidates []mcp.Tool) []mcp.Tool {
		capabilities, err := resolve(ctx)
		visible := make([]mcp.Tool, 0, len(candidates))
		if err != nil {
			return visible
		}
		for _, tool := range candidates {
			if tools.ToolAvailable(capabilities, tool.Name) {
				visible = append(visible, tool)
			}
		}
		return visible
	}
}
