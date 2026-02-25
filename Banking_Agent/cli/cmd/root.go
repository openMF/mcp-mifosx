package cmd

import (
	"fmt"
	"mifos-cli/internal/api"
	"os"

	"github.com/spf13/cobra"
)

var serverURL string

var rootCmd = &cobra.Command{
	Use:   "mifos",
	Short: "🏦 Mifos Banking CLI — control Fineract from your terminal",
	Long: `mifos is a CLI for the Mifos Headless MCP Server.
It lets you manage clients, loans, savings, and groups directly
from the command line by calling the FastAPI server's REST endpoints.

Start the server first:
  cd Banking_Agent && python -m uvicorn core.api_server:app --port 8000

Then use:
  mifos clients search --name "John"
  mifos loans get 42
  mifos savings deposit 7 --amount 500`,
}

func Execute() {
	if err := rootCmd.Execute(); err != nil {
		fmt.Fprintln(os.Stderr, err)
		os.Exit(1)
	}
}

func init() {
	rootCmd.PersistentPreRun = func(cmd *cobra.Command, args []string) {
		api.ServerURL = serverURL
	}
	rootCmd.PersistentFlags().StringVar(&serverURL, "server", "http://localhost:8000",
		"Base URL of the MCP API server (env: MIFOS_SERVER)")

	// Allow env-var override
	if env := os.Getenv("MIFOS_SERVER"); env != "" {
		serverURL = env
	}
}
