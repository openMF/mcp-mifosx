package cmd

import (
	"fmt"
	"strings"

	"mifos-cli/internal/api"

	"github.com/spf13/cobra"
)

var askCmd = &cobra.Command{
	Use:   "ask [query]",
	Short: "🤖 Ask the AI agent via natural language",
	Args:  cobra.MinimumNArgs(1),
	Example: `  mifos ask "Search for clients named John Doe"
  mifos ask "What is the balance of savings account 123?"`,
	Run: func(cmd *cobra.Command, args []string) {
		query := strings.Join(args, " ")
		fmt.Printf("🤔 Thinking about: '%s'...\n", query)

		api.Post("/api/chat", map[string]string{
			"message": query,
		})
	},
}

func init() {
	rootCmd.AddCommand(askCmd)
}
