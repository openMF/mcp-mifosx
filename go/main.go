// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package main

import (
	"os"

	"github.com/openMF/mcp-mifosx/go/adapter"
	mifosserver "github.com/openMF/mcp-mifosx/go/server"
)

func main() {
	fineractClient := adapter.New()

	srv := mifosserver.NewMifosMcpServer(fineractClient)

	port := os.Getenv("PORT")
	if port != "" {
		httpSrv := mifosserver.NewHTTPServer(srv, port)
		if err := httpSrv.Serve(); err != nil {
			os.Exit(1)
		}
	} else {
		if err := srv.Serve(); err != nil {
			os.Exit(1)
		}
	}
}
