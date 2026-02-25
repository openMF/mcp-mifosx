package cmd

import (
	"fmt"
	"mifos-cli/internal/api"
	"strconv"

	"github.com/spf13/cobra"
)

// ──────────────────────────────────────────
// mifos loans
// ──────────────────────────────────────────
var loansCmd = &cobra.Command{
	Use:   "loans",
	Short: "💰 Manage loans",
}

// mifos loans get <id>
var loanGetCmd = &cobra.Command{
	Use:     "get <loan_id>",
	Short:   "Get full details for a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans get 42",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/loans/%s", args[0]))
	},
}

// mifos loans schedule <id>
var loanScheduleCmd = &cobra.Command{
	Use:     "schedule <loan_id>",
	Short:   "Get the repayment schedule for a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans schedule 42",
	Run: func(cmd *cobra.Command, args []string) {
		api.Get(fmt.Sprintf("/api/loans/%s/schedule", args[0]))
	},
}

// mifos loans create --client <id> --principal <n> --months <n> [--product <id>]
var loanCreateCmd = &cobra.Command{
	Use:     "create",
	Short:   "Apply for a new loan",
	Example: "  mifos loans create --client 101 --principal 20000 --months 12",
	Run: func(cmd *cobra.Command, args []string) {
		clientID, _ := cmd.Flags().GetInt("client")
		principal, _ := cmd.Flags().GetFloat64("principal")
		months, _ := cmd.Flags().GetInt("months")
		productID, _ := cmd.Flags().GetInt("product")

		if clientID == 0 || principal == 0 || months == 0 {
			fmt.Println("❌  --client, --principal, and --months are required")
			return
		}

		api.Post("/api/loans", map[string]interface{}{
			"client_id":  clientID,
			"principal":  principal,
			"months":     months,
			"product_id": productID,
		})
	},
}

// mifos loans approve <loan_id> [--amount <n>]
var loanApproveCmd = &cobra.Command{
	Use:     "approve <loan_id>",
	Short:   "Approve and disburse a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans approve 42\n  mifos loans approve 42 --amount 18000",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		body := map[string]interface{}{}
		if amount > 0 {
			body["amount"] = amount
		}
		api.Post(fmt.Sprintf("/api/loans/%s/approve-disburse", args[0]), body)
	},
}

// mifos loans reject <loan_id> [--note <text>]
var loanRejectCmd = &cobra.Command{
	Use:     "reject <loan_id>",
	Short:   "Reject a loan application",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans reject 42 --note \"High credit risk\"",
	Run: func(cmd *cobra.Command, args []string) {
		note, _ := cmd.Flags().GetString("note")
		api.Post(fmt.Sprintf("/api/loans/%s/reject", args[0]), map[string]string{
			"note": note,
		})
	},
}

// mifos loans repay <loan_id> --amount <n>
var loanRepayCmd = &cobra.Command{
	Use:     "repay <loan_id>",
	Short:   "Make a repayment on a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans repay 42 --amount 1500",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/loans/%s/repayment", args[0]), map[string]float64{
			"amount": amount,
		})
	},
}

// mifos loans late-fee <loan_id> --amount <n>
var loanLateFeeCmd = &cobra.Command{
	Use:     "late-fee <loan_id>",
	Short:   "Apply a late fee to a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans late-fee 42 --amount 50",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/loans/%s/late-fee", args[0]), map[string]float64{
			"fee_amount": amount,
		})
	},
}

// mifos loans waive <loan_id> --amount <n> [--note <text>]
var loanWaiveCmd = &cobra.Command{
	Use:     "waive <loan_id>",
	Short:   "Waive interest on a loan",
	Args:    cobra.ExactArgs(1),
	Example: "  mifos loans waive 42 --amount 200 --note \"Hardship waiver\"",
	Run: func(cmd *cobra.Command, args []string) {
		amount, _ := cmd.Flags().GetFloat64("amount")
		note, _ := cmd.Flags().GetString("note")
		if amount == 0 {
			fmt.Println("❌  --amount is required")
			return
		}
		api.Post(fmt.Sprintf("/api/loans/%s/waive-interest", args[0]), map[string]interface{}{
			"amount": amount,
			"note":   note,
		})
	},
}

func init() {
	// unused but keeps strconv import valid if needed later
	_ = strconv.Itoa

	loanCreateCmd.Flags().Int("client", 0, "Client ID (required)")
	loanCreateCmd.Flags().Float64("principal", 0, "Loan principal amount (required)")
	loanCreateCmd.Flags().Int("months", 0, "Loan term in months (required)")
	loanCreateCmd.Flags().Int("product", 1, "Loan product ID")

	loanApproveCmd.Flags().Float64("amount", 0, "Override disbursement amount (optional)")

	loanRejectCmd.Flags().String("note", "Rejected via AI Agent due to risk profile", "Rejection reason")

	loanRepayCmd.Flags().Float64("amount", 0, "Repayment amount (required)")
	loanLateFeeCmd.Flags().Float64("amount", 0, "Late fee amount (required)")
	loanWaiveCmd.Flags().Float64("amount", 0, "Amount to waive (required)")
	loanWaiveCmd.Flags().String("note", "AI Authorized Waiver", "Waiver note")

	loansCmd.AddCommand(
		loanGetCmd,
		loanScheduleCmd,
		loanCreateCmd,
		loanApproveCmd,
		loanRejectCmd,
		loanRepayCmd,
		loanLateFeeCmd,
		loanWaiveCmd,
	)
	rootCmd.AddCommand(loansCmd)
}
