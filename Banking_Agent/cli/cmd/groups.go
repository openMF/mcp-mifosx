package cmd

import (
	"fmt"
	"mifos-cli/internal/api"

	"github.com/spf13/cobra"
)

// ──────────────────────────────────────────
// mifos groups
// ──────────────────────────────────────────
var groupsCmd = &cobra.Command{
	Use:   "groups",
	Short: "🏢 Manage lending groups",
}

// mifos groups create --name <name> [--office <id>]
var groupCreateCmd = &cobra.Command{
	Use:     "create",
	Short:   "Create a new lending group",
	Example: "  mifos groups create --name \"The Innovators\" --office 1",
	Run: func(cmd *cobra.Command, args []string) {
		name, _ := cmd.Flags().GetString("name")
		officeID, _ := cmd.Flags().GetInt("office")

		if name == "" {
			fmt.Println("❌  --name is required")
			return
		}

		api.Post("/api/groups", map[string]interface{}{
			"name":      name,
			"office_id": officeID,
		})
	},
}

// mifos groups get <id>
var groupGetCmd = &cobra.Command{
	Use:     "get <group_id>",
	Short:   "Get details and members of a group",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos groups get 5",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/groups/%s", args[0]))
	},
}

func init() {
	groupCreateCmd.Flags().String("name", "", "Group name (required)")
	groupCreateCmd.Flags().Int("office", 1, "Office ID")

	groupsCmd.AddCommand(groupCreateCmd, groupGetCmd)
	rootCmd.AddCommand(groupsCmd)
}
