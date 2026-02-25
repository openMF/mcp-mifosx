package cmd

import (
	"fmt"
	"mifos-cli/internal/api"

	"github.com/spf13/cobra"
)

// ──────────────────────────────────────────
// mifos clients
// ──────────────────────────────────────────
var clientsCmd = &cobra.Command{
	Use:   "clients",
	Short: "👥 Manage clients and groups",
}

// mifos clients search --name <query>
var clientSearchCmd = &cobra.Command{
	Use:     "search",
	Short:   "Search clients by name",
	Example: "  mifos clients search --name \"John Doe\"",
	Run: func(cmd *cobra.Command, args []string) {
		name, _ := cmd.Flags().GetString("name")
		if name == "" {
			fmt.Println("❌  --name is required")
			return
		}
		api.Get(fmt.Sprintf("/api/clients/search?name=%s", name))
	},
}

// mifos clients get <id>
var clientGetCmd = &cobra.Command{
	Use:     "get <client_id>",
	Short:   "Get full details for a client",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos clients get 101",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/clients/%s", args[0]))
	},
}

// mifos clients accounts <id>
var clientAccountsCmd = &cobra.Command{
	Use:     "accounts <client_id>",
	Short:   "List all loans and savings accounts for a client",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos clients accounts 101",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/clients/%s/accounts", args[0]))
	},
}

// mifos clients create --first John --last Doe [--mobile +1234]
var clientCreateCmd = &cobra.Command{
	Use:     "create",
	Short:   "Create a new client",
	Example: "  mifos clients create --first John --last Doe --mobile +525551234567",
	Run: func(cmd *cobra.Command, args []string) {
		first, _ := cmd.Flags().GetString("first")
		last, _ := cmd.Flags().GetString("last")
		mobile, _ := cmd.Flags().GetString("mobile")
		officeID, _ := cmd.Flags().GetInt("office")
		active, _ := cmd.Flags().GetBool("active")

		if first == "" || last == "" {
			fmt.Println("❌  --first and --last are required")
			return
		}

		body := map[string]interface{}{
			"firstname": first,
			"lastname":  last,
			"office_id": officeID,
			"is_active": active,
		}
		if mobile != "" {
			body["mobile_no"] = mobile
		}
		api.Post("/api/clients", body)
	},
}

// mifos clients activate <id>
var clientActivateCmd = &cobra.Command{
	Use:     "activate <client_id>",
	Short:   "Activate a pending client profile",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos clients activate 101",
	Run: func(cmd *cobra.Command, args []string) {
		api.Post(fmt.Sprintf("/api/clients/%s/activate", args[0]), map[string]interface{}{})
	},
}

// mifos clients update-mobile <id> --mobile <number>
var clientUpdateMobileCmd = &cobra.Command{
	Use:     "update-mobile <client_id>",
	Short:   "Update a client's mobile number",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos clients update-mobile 101 --mobile +525559876543",
	Run: func(cmd *cobra.Command, args []string) {
		mobile, _ := cmd.Flags().GetString("mobile")
		if mobile == "" {
			fmt.Println("❌  --mobile is required")
			return
		}
		api.Put(fmt.Sprintf("/api/clients/%s/mobile", args[0]), map[string]string{
			"new_mobile_no": mobile,
		})
	},
}

// mifos clients close <id> [--reason <id>]
var clientCloseCmd = &cobra.Command{
	Use:     "close <client_id>",
	Short:   "Close a client profile",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos clients close 101 --reason 17",
	Run: func(cmd *cobra.Command, args []string) {
		reason, _ := cmd.Flags().GetInt("reason")
		api.Post(fmt.Sprintf("/api/clients/%s/close", args[0]), map[string]int{
			"closure_reason_id": reason,
		})
	},
}

func init() {
	// flags
	clientSearchCmd.Flags().String("name", "", "Name query to search for (required)")

	clientCreateCmd.Flags().String("first", "", "First name (required)")
	clientCreateCmd.Flags().String("last", "", "Last name (required)")
	clientCreateCmd.Flags().String("mobile", "", "Mobile number (optional)")
	clientCreateCmd.Flags().Int("office", 1, "Office ID")
	clientCreateCmd.Flags().Bool("active", true, "Activate immediately")

	clientUpdateMobileCmd.Flags().String("mobile", "", "New mobile number (required)")
	clientCloseCmd.Flags().Int("reason", 17, "Closure reason code ID")

	// assemble tree
	clientsCmd.AddCommand(
		clientSearchCmd,
		clientGetCmd,
		clientAccountsCmd,
		clientCreateCmd,
		clientActivateCmd,
		clientUpdateMobileCmd,
		clientCloseCmd,
	)
	rootCmd.AddCommand(clientsCmd)
}
