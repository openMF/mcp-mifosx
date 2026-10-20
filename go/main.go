// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package main

import (
	"fmt"
	"log"
	"os"

	"github.com/openMF/mcp-mifosx/go/adapter"
	mifosserver "github.com/openMF/mcp-mifosx/go/server"
)

func main() {
	if err := run(); err != nil {
		log.Print(err)
		os.Exit(1)
	}
}

func run() error {
	// Load PORT before selecting the execution identity, including .env setups.
	if err := adapter.LoadEnvironment(); err != nil {
		return fmt.Errorf("initialize environment: %w", err)
	}
	port := os.Getenv("PORT")
	if port != "" {
		caller, err := adapter.NewCaller()
		if err != nil {
			return fmt.Errorf("initialize HTTP server: %w", err)
		}
		httpSrv := mifosserver.NewHTTPServer(caller, port)
		if err := httpSrv.Serve(); err != nil {
			return fmt.Errorf("serve HTTP: %w", err)
		}
	} else {
		configured, err := adapter.NewConfigured()
		if err != nil {
			return fmt.Errorf("initialize stdio server: %w", err)
		}
		srv := mifosserver.NewMifosMcpServer(configured)
		if err := srv.Serve(); err != nil {
			return fmt.Errorf("serve stdio: %w", err)
		}
	}
	return nil
}
