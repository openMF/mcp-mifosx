package cmd

import (
	"fmt"
	"mifos-cli/internal/api"

	"github.com/spf13/cobra"
)

// ──────────────────────────────────────────
// mifos savings
// ──────────────────────────────────────────
var savingsCmd = &cobra.Command{
	Use:   "savings",
	Short: "🏦 Manage savings accounts",
}

// mifos savings get <id>
var savingsGetCmd = &cobra.Command{
	Use:     "get <account_id>",
	Short:   "Get details of a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings get 7",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/savings/%s", args[0]))
	},
}

// mifos savings transactions <id>
var savingsTransactionsCmd = &cobra.Command{
	Use:     "transactions <account_id>",
	Short:   "Get transaction history for a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings transactions 7",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/savings/%s/transactions", args[0]))
	},
}

// mifos savings create --client <id> [--product <id>]
var savingsCreateCmd = &cobra.Command{
	Use:     "create",
	Short:   "Open a new savings account for a client",
	Example: "  mifos savings create --client 101",
	Run: func(cmd *cobra.Command, args []string) {
		clientID, _ := cmd.Flags().GetInt("client")
		productID, _ := cmd.Flags().GetInt("product")
		if clientID == 0 {
			fmt.Println("❌  --client is required")
			return
		}
		api.Post("/api/savings", map[string]int{
			"client_id":  clientID,
			"product_id": productID,
		})
	},
}

// mifos savings approve <id>
var savingsApproveCmd = &cobra.Command{
	Use:     "approve <account_id>",
	Short:   "Approve and activate a pending savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings approve 7",
	Run: func(cmd *cobra.Command, args []string) {
		api.Post(fmt.Sprintf("/api/savings/%s/approve-activate", args[0]), map[string]interface{}{})
	},
}

// mifos savings close <id>
var savingsCloseCmd = &cobra.Command{
	Use:     "close <account_id>",
	Short:   "Close a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings close 7",
	Run: func(cmd *cobra.Command, args []string) {
		api.Post(fmt.Sprintf("/api/savings/%s/close", args[0]), map[string]interface{}{})
	},
}

// mifos savings deposit <id> --amount <n>
var savingsDepositCmd = &cobra.Command{
	Use:     "deposit <account_id>",
	Short:   "Deposit money into a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings deposit 7 --amount 500",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/savings/%s/deposit", args[0]), map[string]float64{
			"amount": amount,
		})
	},
}

// mifos savings withdraw <id> --amount <n>
var savingsWithdrawCmd = &cobra.Command{
	Use:     "withdraw <account_id>",
	Short:   "Withdraw money from a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings withdraw 7 --amount 100",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/savings/%s/withdraw", args[0]), map[string]float64{
			"amount": amount,
		})
	},
}

// mifos savings charge <id> --amount <n> [--charge-id <n>]
var savingsChargeCmd = &cobra.Command{
	Use:     "charge <account_id>",
	Short:   "Apply a fee/charge to a savings account",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings charge 7 --amount 15",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		chargeID, _ := cmd.Flags().GetInt("charge-id")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/savings/%s/charge", args[0]), map[string]interface{}{
			"amount":    amount,
			"charge_id": chargeID,
		})
	},
}

// mifos savings post-interest <id>
var savingsPostInterestCmd = &cobra.Command{
	Use:     "post-interest <account_id>",
	Short:   "Calculate and post accrued interest",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos savings post-interest 7",
	Run: func(cmd *cobra.Command, args []string) {
		api.Post(fmt.Sprintf("/api/savings/%s/post-interest", args[0]), map[string]interface{}{})
	},
}

func init() {
	savingsCreateCmd.Flags().Int("client", 0, "Client ID (required)")
	savingsCreateCmd.Flags().Int("product", 1, "Savings product ID")

	savingsDepositCmd.Flags().Float64("amount", 0, "Deposit amount (required)")
	savingsWithdrawCmd.Flags().Float64("amount", 0, "Withdrawal amount (required)")
	savingsChargeCmd.Flags().Float64("amount", 0, "Charge amount (required)")
	savingsChargeCmd.Flags().Int("charge-id", 1, "Fineract charge type ID")

	savingsCmd.AddCommand(
		savingsGetCmd,
		savingsTransactionsCmd,
		savingsCreateCmd,
		savingsApproveCmd,
		savingsCloseCmd,
		savingsDepositCmd,
		savingsWithdrawCmd,
		savingsChargeCmd,
		savingsPostInterestCmd,
	)
	rootCmd.AddCommand(savingsCmd)
}
