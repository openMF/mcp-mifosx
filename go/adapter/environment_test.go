// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.
package adapter_test

import (
	"errors"
	"os"
	"strings"
	"testing"

	"github.com/openMF/mcp-mifosx/go/adapter"
)

func TestEnvironmentLoading(t *testing.T) {
	for _, tc := range []struct {
		name, contents       string
		directory, wantError bool
	}{
		{name: "missing optional file"},
		{name: "valid file", contents: "PORT=18080\n"},
		{name: "malformed file", contents: "PORT=18080\nMIFOSX_PASSWORD=\"dotenv-private-secret\n", wantError: true},
		{name: "unreadable file", directory: true, wantError: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			t.Chdir(t.TempDir())
			// Ensure .env supplies PORT while restoring any original value afterward.
			t.Setenv("PORT", "")
			if err := os.Unsetenv("PORT"); err != nil {
				t.Fatal(err)
			}
			if tc.directory {
				if err := os.Mkdir(".env", 0700); err != nil {
					t.Fatal(err)
				}
			} else if tc.contents != "" {
				if err := os.WriteFile(".env", []byte(tc.contents), 0600); err != nil {
					t.Fatal(err)
				}
			}
			// Direct constructors must also reject load errors without returning a
			// default client, even when main has not loaded the environment first.
			initializers := []func() error{
				func() error {
					client, err := adapter.NewConfigured()
					if err != nil && client != nil {
						t.Fatal("configured client returned alongside a load error")
					}
					return err
				},
				func() error {
					client, err := adapter.NewCaller()
					if err != nil && client != nil {
						t.Fatal("caller client returned alongside a load error")
					}
					return err
				},
				func() error {
					client, err := adapter.New()
					if err != nil && client != nil {
						t.Fatal("legacy client returned alongside a load error")
					}
					return err
				},
				adapter.LoadEnvironment,
			}
			for _, initialize := range initializers {
				err := initialize()
				if (err != nil) != tc.wantError {
					t.Fatalf("unexpected load result: %v", err)
				}
				if err != nil {
					if !strings.Contains(err.Error(), "load .env") || strings.Contains(err.Error(), "dotenv-private-secret") {
						t.Fatal("load error lacks a safe diagnostic")
					}
					var pathError *os.PathError
					if tc.directory && !errors.As(err, &pathError) {
						t.Fatal("load error lost its underlying cause")
					}
				}
			}
			if tc.name == "valid file" && os.Getenv("PORT") != "18080" {
				t.Fatal("valid .env did not supply PORT")
			}
		})
	}
}
