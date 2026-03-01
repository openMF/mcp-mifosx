package cmd

import (
	"fmt"
	"mifos-cli/internal/api"

	"github.com/spf13/cobra"
)

// mifos route --prompt <text>
var routeCmd = &cobra.Command{
	Use:   "route",
	Short: "🔀 Ask the AI router which tools to load for a given intent",
	Example: `  mifos route --prompt "I need to check a client's loan balance"
  mifos route --prompt "deposit money into savings"`,
	Run: func(cmd *cobra.Command, args []string) {
		prompt, _ := cmd.Flags().GetString("prompt")
		if prompt == "" {
			fmt.Println("❌  --prompt is required")
			return
		}
		api.Post("/api/router/intent", map[string]string{
			"prompt": prompt,
		})
	},
}

func init() {
	routeCmd.Flags().String("prompt", "", "Natural language intent (required)")
	rootCmd.AddCommand(routeCmd)
}
