// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package main

import (
	"context"
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMainRejectsInvalidEnvironment(t *testing.T) {
	if os.Getenv("MCP_TEST_INVALID_ENV") == "1" {
		main()
		return
	}
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, ".env"), []byte("PORT=18080\nMIFOSX_PASSWORD=\"dotenv-private-secret\n"), 0600); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	cmd := exec.CommandContext(ctx, os.Args[0], "-test.run=^TestMainRejectsInvalidEnvironment$")
	cmd.Dir = dir
	cmd.Env = append(os.Environ(), "MCP_TEST_INVALID_ENV=1", "PORT=")
	output, err := cmd.CombinedOutput()
	if ctx.Err() != nil {
		t.Fatal("startup did not stop after an invalid .env")
	}
	var exitError *exec.ExitError
	if !errors.As(err, &exitError) || exitError.ExitCode() != 1 {
		t.Fatalf("startup did not exit with status 1: %v", err)
	}
	if !strings.Contains(string(output), "load .env") || strings.Contains(string(output), "dotenv-private-secret") {
		t.Fatal("startup did not report a safe .env diagnostic")
	}
}
