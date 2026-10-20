// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package main

import (
	"os"

	"github.com/joho/godotenv"
	"github.com/openMF/mcp-mifosx/go/adapter"
	mifosserver "github.com/openMF/mcp-mifosx/go/server"
)

func main() {
	// Load PORT before selecting the execution identity, including .env setups.
	_ = godotenv.Load()
	port := os.Getenv("PORT")
	if port != "" {
		httpSrv := mifosserver.NewHTTPServer(adapter.NewCaller(), port)
		if err := httpSrv.Serve(); err != nil {
			os.Exit(1)
		}
	} else {
		srv := mifosserver.NewMifosMcpServer(adapter.NewConfigured())
		if err := srv.Serve(); err != nil {
			os.Exit(1)
		}
	}
}
