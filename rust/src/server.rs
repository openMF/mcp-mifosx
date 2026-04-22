// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use crate::domains::*;
use crate::registry::{DomainRegistry, IntentRouterReq, INTENT_ROUTE_THRESHOLD};
use rmcp::{
    ErrorData as McpError,
    handler::server::{router::tool::ToolRouter, wrapper::Parameters},
    model::{ServerCapabilities, ServerInfo, CallToolResult},
    tool, tool_handler, tool_router, ServerHandler,
};

#[derive(Clone, Debug)]
pub struct MifosMcpServer {
    pub adapter: FineractAdapter,
    pub registry: DomainRegistry,
    tool_router: ToolRouter<Self>,
}

#[tool_router]
impl MifosMcpServer {

    pub fn new(adapter: FineractAdapter) -> Self {
        let registry = DomainRegistry::new();
        Self { adapter, registry, tool_router: Self::tool_router() }
    }

    #[tool(description = "Find the client ID for a given name")]
    async fn search_clients_by_name(&self, Parameters(req): Parameters<clients::SearchClientsReq>) -> Result<CallToolResult, McpError> { clients::search_clients_by_name(&self.adapter, req).await }
    #[tool(description = "Show key details for a specific client")]
    async fn get_client_details(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_details(&self.adapter, req).await }
    #[tool(description = "Show all loans and savings accounts for a client")]
    async fn get_client_accounts(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_accounts(&self.adapter, req).await }
    #[tool(description = "Create a new client")]
    async fn create_client(&self, Parameters(req): Parameters<clients::CreateClientReq>) -> Result<CallToolResult, McpError> { clients::create_client(&self.adapter, req).await }
    #[tool(description = "Activate pending client profile")]
    async fn activate_client(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::activate_client(&self.adapter, req).await }
    #[tool(description = "Update client mobile number. Use PUT.")]
    async fn update_client_mobile(&self, Parameters(req): Parameters<clients::UpdateMobileReq>) -> Result<CallToolResult, McpError> { clients::update_client_mobile(&self.adapter, req).await }
    #[tool(description = "Update client details (firstname, lastname, etc.)")]
    async fn update_client(&self, Parameters(req): Parameters<clients::UpdateClientReq>) -> Result<CallToolResult, McpError> { clients::update_client(&self.adapter, req).await }
    #[tool(description = "Delete client profile")]
    async fn delete_client(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::delete_client(&self.adapter, req).await }
    #[tool(description = "Close client profile")]
    async fn close_client(&self, Parameters(req): Parameters<clients::CloseClientReq>) -> Result<CallToolResult, McpError> { clients::close_client(&self.adapter, req).await }
    #[tool(description = "Fetch ID documents for client")]
    async fn get_client_identifiers(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_identifiers(&self.adapter, req).await }
    #[tool(description = "Create identifier for client")]
    async fn create_client_identifier(&self, Parameters(req): Parameters<clients::CreateIdentifierReq>) -> Result<CallToolResult, McpError> { clients::create_client_identifier(&self.adapter, req).await }
    #[tool(description = "Fetch documents/files for client")]
    async fn get_client_documents(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_documents(&self.adapter, req).await }
    #[tool(description = "Fetch charges for client")]
    async fn get_client_charges(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_charges(&self.adapter, req).await }
    #[tool(description = "Apply a one-time charge to client")]
    async fn apply_client_charge(&self, Parameters(req): Parameters<clients::ApplyClientChargeReq>) -> Result<CallToolResult, McpError> { clients::apply_client_charge(&self.adapter, req).await }
    #[tool(description = "Pay a pending client charge. This generates a client transaction. Use the client_charge_id returned by apply_client_charge.")]
    async fn pay_client_charge(&self, Parameters(req): Parameters<clients::PayClientChargeReq>) -> Result<CallToolResult, McpError> { clients::pay_client_charge(&self.adapter, req).await }
    #[tool(description = "Waive a pending client charge. This generates a client transaction. Use the client_charge_id returned by apply_client_charge.")]
    async fn waive_client_charge(&self, Parameters(req): Parameters<clients::WaiveClientChargeReq>) -> Result<CallToolResult, McpError> { clients::waive_client_charge(&self.adapter, req).await }
    #[tool(description = "Fetch client's financial transactions (list)")]
    async fn get_client_transactions(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_transactions(&self.adapter, req).await }
    #[tool(description = "Retrieve a specific client transaction by ID")]
    async fn get_client_transaction(&self, Parameters(req): Parameters<clients::ClientTransactionIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_transaction(&self.adapter, req).await }
    #[tool(description = "Undo a client transaction. Note: client transactions can only be reversed, not explicitly created, since they are generated naturally via charge payments/waivers.")]
    async fn undo_client_transaction(&self, Parameters(req): Parameters<clients::ClientTransactionIdReq>) -> Result<CallToolResult, McpError> { clients::undo_client_transaction(&self.adapter, req).await }
    #[tool(description = "Fetch home addresses for client")]
    async fn get_client_addresses(&self, Parameters(req): Parameters<clients::ClientIdReq>) -> Result<CallToolResult, McpError> { clients::get_client_addresses(&self.adapter, req).await }
    #[tool(description = "List all client collaterals")]
    async fn list_client_collaterals(&self, Parameters(req): Parameters<collaterals::ClientIdReq>) -> Result<CallToolResult, McpError> { collaterals::list_client_collaterals(&self.adapter, req).await }
    #[tool(description = "Retrieve a specific client collateral")]
    async fn get_client_collateral(&self, Parameters(req): Parameters<collaterals::ClientCollateralIdReq>) -> Result<CallToolResult, McpError> { collaterals::get_client_collateral(&self.adapter, req).await }
    #[tool(description = "Create a client collateral")]
    async fn create_client_collateral(&self, Parameters(req): Parameters<collaterals::CreateClientCollateralReq>) -> Result<CallToolResult, McpError> { collaterals::create_client_collateral(&self.adapter, req).await }
    #[tool(description = "Update a client collateral")]
    async fn update_client_collateral(&self, Parameters(req): Parameters<collaterals::UpdateClientCollateralReq>) -> Result<CallToolResult, McpError> { collaterals::update_client_collateral(&self.adapter, req).await }
    #[tool(description = "Delete a client collateral")]
    async fn delete_client_collateral(&self, Parameters(req): Parameters<collaterals::ClientCollateralIdReq>) -> Result<CallToolResult, McpError> { collaterals::delete_client_collateral(&self.adapter, req).await }

    #[tool(description = "List groups")]
    async fn list_groups(&self, Parameters(req): Parameters<groups::ListGroupsReq>) -> Result<CallToolResult, McpError> { groups::list_groups(&self.adapter, req).await }
    #[tool(description = "Show details for group")]
    async fn get_group(&self, Parameters(req): Parameters<groups::GroupIdReq>) -> Result<CallToolResult, McpError> { groups::get_group(&self.adapter, req).await }
    #[tool(description = "Create group")]
    async fn create_group(&self, Parameters(req): Parameters<groups::CreateGroupReq>) -> Result<CallToolResult, McpError> { groups::create_group(&self.adapter, req).await }
    #[tool(description = "Activate group")]
    async fn activate_group(&self, Parameters(req): Parameters<groups::GroupIdReq>) -> Result<CallToolResult, McpError> { groups::activate_group(&self.adapter, req).await }
    #[tool(description = "Add client to group")]
    async fn add_group_member(&self, Parameters(req): Parameters<groups::AddGroupMemberReq>) -> Result<CallToolResult, McpError> { groups::add_group_member(&self.adapter, req).await }
    #[tool(description = "Remove client from group")]
    async fn remove_group_member(&self, Parameters(req): Parameters<groups::RemoveGroupMemberReq>) -> Result<CallToolResult, McpError> { groups::remove_group_member(&self.adapter, req).await }
    #[tool(description = "Get all accounts for a group")]
    async fn get_group_accounts(&self, Parameters(req): Parameters<groups::GroupIdReq>) -> Result<CallToolResult, McpError> { groups::get_group_accounts(&self.adapter, req).await }
    #[tool(description = "Create a savings account for a group")]
    async fn create_group_savings_account(&self, Parameters(req): Parameters<groups::CreateGroupSavingsReq>) -> Result<CallToolResult, McpError> { groups::create_group_savings_account(&self.adapter, req).await }
    #[tool(description = "Update group details")]
    async fn update_group(&self, Parameters(req): Parameters<groups::UpdateGroupReq>) -> Result<CallToolResult, McpError> { groups::update_group(&self.adapter, req).await }
    #[tool(description = "Close a group")]
    async fn close_group(&self, Parameters(req): Parameters<groups::CloseGroupReq>) -> Result<CallToolResult, McpError> { groups::close_group(&self.adapter, req).await }
    #[tool(description = "List centers")]
    async fn list_centers(&self, Parameters(req): Parameters<groups::ListGroupsReq>) -> Result<CallToolResult, McpError> { groups::list_centers(&self.adapter, req).await }
    #[tool(description = "Show center details")]
    async fn get_center(&self, Parameters(req): Parameters<groups::CenterIdReq>) -> Result<CallToolResult, McpError> { groups::get_center(&self.adapter, req).await }
    #[tool(description = "Create center")]
    async fn create_center(&self, Parameters(req): Parameters<groups::CreateGroupReq>) -> Result<CallToolResult, McpError> { groups::create_center(&self.adapter, req).await }

    #[tool(description = "Get loan details")]
    async fn get_loan_details(&self, Parameters(req): Parameters<loans::LoanIdReq>) -> Result<CallToolResult, McpError> { loans::get_loan_details(&self.adapter, req).await }
    #[tool(description = "Get repayment schedule")]
    async fn get_repayment_schedule(&self, Parameters(req): Parameters<loans::LoanIdReq>) -> Result<CallToolResult, McpError> { loans::get_repayment_schedule(&self.adapter, req).await }
    #[tool(description = "Get loan full transaction history")]
    async fn get_loan_history(&self, Parameters(req): Parameters<loans::LoanIdReq>) -> Result<CallToolResult, McpError> { loans::get_loan_history(&self.adapter, req).await }
    #[tool(description = "Get overdue loans")]
    async fn get_overdue_loans(&self, Parameters(req): Parameters<loans::ClientIdReq>) -> Result<CallToolResult, McpError> { loans::get_overdue_loans(&self.adapter, req).await }
    #[tool(description = "Create an individual loan")]
    async fn create_loan(&self, Parameters(req): Parameters<loans::CreateLoanReq>) -> Result<CallToolResult, McpError> { loans::create_loan(&self.adapter, req).await }
    #[tool(description = "Update a draft/submitted loan application")]
    async fn update_loan(&self, Parameters(req): Parameters<loans::UpdateLoanReq>) -> Result<CallToolResult, McpError> { loans::update_loan(&self.adapter, req).await }
    #[tool(description = "Delete a draft/submitted loan application")]
    async fn delete_loan(&self, Parameters(req): Parameters<loans::LoanIdReq>) -> Result<CallToolResult, McpError> { loans::delete_loan(&self.adapter, req).await }
    #[tool(description = "List all loan products")]
    async fn list_loan_products(&self, Parameters(req): Parameters<loans::EmptyReq>) -> Result<CallToolResult, McpError> { loans::list_loan_products(&self.adapter, req).await }
    #[tool(description = "Create a group loan")]
    async fn create_group_loan(&self, Parameters(req): Parameters<loans::CreateGroupLoanReq>) -> Result<CallToolResult, McpError> { loans::create_group_loan(&self.adapter, req).await }
    #[tool(description = "Approve and disburse loan")]
    async fn approve_and_disburse_loan(&self, Parameters(req): Parameters<loans::ApproveLoanReq>) -> Result<CallToolResult, McpError> { loans::approve_and_disburse_loan(&self.adapter, req).await }
    #[tool(description = "Reject loan")]
    async fn reject_loan_application(&self, Parameters(req): Parameters<loans::RejectLoanReq>) -> Result<CallToolResult, McpError> { loans::reject_loan_application(&self.adapter, req).await }
    #[tool(description = "Make loan repayment")]
    async fn make_loan_repayment(&self, Parameters(req): Parameters<loans::RepaymentReq>) -> Result<CallToolResult, McpError> { loans::make_loan_repayment(&self.adapter, req).await }
    #[tool(description = "Apply late fee")]
    async fn apply_late_fee(&self, Parameters(req): Parameters<loans::ApplyLateFeeReq>) -> Result<CallToolResult, McpError> { loans::apply_late_fee(&self.adapter, req).await }
    #[tool(description = "Waive interest")]
    async fn waive_interest(&self, Parameters(req): Parameters<loans::WaiveInterestReq>) -> Result<CallToolResult, McpError> { loans::waive_interest(&self.adapter, req).await }
    #[tool(description = "List all loan collaterals")]
    async fn list_loan_collaterals(&self, Parameters(req): Parameters<collaterals::LoanIdReq>) -> Result<CallToolResult, McpError> { collaterals::list_loan_collaterals(&self.adapter, req).await }
    #[tool(description = "Retrieve a specific loan collateral")]
    async fn get_loan_collateral(&self, Parameters(req): Parameters<collaterals::LoanCollateralIdReq>) -> Result<CallToolResult, McpError> { collaterals::get_loan_collateral(&self.adapter, req).await }
    #[tool(description = "Create a loan collateral")]
    async fn create_loan_collateral(&self, Parameters(req): Parameters<collaterals::CreateLoanCollateralReq>) -> Result<CallToolResult, McpError> { collaterals::create_loan_collateral(&self.adapter, req).await }
    #[tool(description = "Update a loan collateral")]
    async fn update_loan_collateral(&self, Parameters(req): Parameters<collaterals::UpdateLoanCollateralReq>) -> Result<CallToolResult, McpError> { collaterals::update_loan_collateral(&self.adapter, req).await }
    #[tool(description = "Delete a loan collateral")]
    async fn delete_loan_collateral(&self, Parameters(req): Parameters<collaterals::LoanCollateralIdReq>) -> Result<CallToolResult, McpError> { collaterals::delete_loan_collateral(&self.adapter, req).await }

    #[tool(description = "Get savings account balance")]
    async fn get_savings_account(&self, Parameters(req): Parameters<savings::SavingsIdReq>) -> Result<CallToolResult, McpError> { savings::get_savings_account(&self.adapter, req).await }
    #[tool(description = "Get savings transactions")]
    async fn get_savings_transactions(&self, Parameters(req): Parameters<savings::SavingsIdReq>) -> Result<CallToolResult, McpError> { savings::get_savings_transactions(&self.adapter, req).await }
    #[tool(description = "Create savings account")]
    async fn create_savings_account(&self, Parameters(req): Parameters<savings::CreateSavingsReq>) -> Result<CallToolResult, McpError> { savings::create_savings_account(&self.adapter, req).await }
    #[tool(description = "List all savings products")]
    async fn list_savings_products(&self, Parameters(req): Parameters<savings::EmptyReq>) -> Result<CallToolResult, McpError> { savings::list_savings_products(&self.adapter, req).await }
    #[tool(description = "Approve and activate savings")]
    async fn approve_and_activate_savings(&self, Parameters(req): Parameters<savings::SavingsIdReq>) -> Result<CallToolResult, McpError> { savings::approve_and_activate_savings(&self.adapter, req).await }
    #[tool(description = "Close savings")]
    async fn close_savings_account(&self, Parameters(req): Parameters<savings::SavingsIdReq>) -> Result<CallToolResult, McpError> { savings::close_savings_account(&self.adapter, req).await }
    #[tool(description = "Deposit savings")]
    async fn deposit_savings(&self, Parameters(req): Parameters<savings::DepositWithdrawReq>) -> Result<CallToolResult, McpError> { savings::deposit_savings(&self.adapter, req).await }
    #[tool(description = "Withdraw savings")]
    async fn withdraw_savings(&self, Parameters(req): Parameters<savings::DepositWithdrawReq>) -> Result<CallToolResult, McpError> { savings::withdraw_savings(&self.adapter, req).await }
    #[tool(description = "Apply savings charge")]
    async fn apply_savings_charge(&self, Parameters(req): Parameters<savings::ApplySavingsChargeReq>) -> Result<CallToolResult, McpError> { savings::apply_savings_charge(&self.adapter, req).await }
    #[tool(description = "Calculate and post interest")]
    async fn calculate_and_post_interest(&self, Parameters(req): Parameters<savings::SavingsIdReq>) -> Result<CallToolResult, McpError> { savings::calculate_and_post_interest(&self.adapter, req).await }

    #[tool(description = "List GL Accounts")]
    async fn list_gl_accounts(&self, Parameters(req): Parameters<accounting::ListGLAccountsReq>) -> Result<CallToolResult, McpError> { accounting::list_gl_accounts(&self.adapter, req).await }
    #[tool(description = "Get Journal Entries")]
    async fn get_journal_entries(&self, Parameters(req): Parameters<accounting::GetJournalEntriesReq>) -> Result<CallToolResult, McpError> { accounting::get_journal_entries(&self.adapter, req).await }
    #[tool(description = "Create manual journal entry")]
    async fn create_journal_entry(&self, Parameters(req): Parameters<accounting::CreateJournalEntryReq>) -> Result<CallToolResult, McpError> { accounting::create_journal_entry(&self.adapter, req).await }

    #[tool(description = "List staff")]
    async fn list_staff(&self, Parameters(req): Parameters<staff::ListStaffReq>) -> Result<CallToolResult, McpError> { staff::list_staff(&self.adapter, req).await }
    #[tool(description = "Get staff details")]
    async fn get_staff_details(&self, Parameters(req): Parameters<staff::StaffIdReq>) -> Result<CallToolResult, McpError> { staff::get_staff_details(&self.adapter, req).await }
    #[tool(description = "List offices")]
    async fn list_offices(&self, Parameters(req): Parameters<staff::EmptyReq>) -> Result<CallToolResult, McpError> { staff::list_offices(&self.adapter, req).await }
    #[tool(description = "Get office details")]
    async fn get_office_details(&self, Parameters(req): Parameters<staff::OfficeIdReq>) -> Result<CallToolResult, McpError> { staff::get_office_details(&self.adapter, req).await }

    #[tool(description = "Retrieve charge template details")]
    async fn retrieve_charge(&self, Parameters(req): Parameters<charges::ChargeIdReq>) -> Result<CallToolResult, McpError> { charges::retrieve_charge(&self.adapter, req).await }
    #[tool(description = "Create new charge/fee template")]
    async fn create_charge(&self, Parameters(req): Parameters<charges::CreateChargeReq>) -> Result<CallToolResult, McpError> { charges::create_charge(&self.adapter, req).await }
    #[tool(description = "Update existing charge template")]
    async fn update_charge(&self, Parameters(req): Parameters<charges::UpdateChargeReq>) -> Result<CallToolResult, McpError> { charges::update_charge(&self.adapter, req).await }
    #[tool(description = "Delete charge template")]
    async fn delete_charge(&self, Parameters(req): Parameters<charges::ChargeIdReq>) -> Result<CallToolResult, McpError> { charges::delete_charge(&self.adapter, req).await }

    #[tool(description = "Bulk parallel search clients by name")]
    async fn bulk_search_clients(&self, Parameters(req): Parameters<bulk::BulkSearchClientsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_search_clients(&self.adapter, req).await }
    #[tool(description = "Bulk parallel fetch loan status")]
    async fn bulk_get_loan_status(&self, Parameters(req): Parameters<bulk::BulkLoanStatusReq>) -> Result<CallToolResult, McpError> { bulk::bulk_get_loan_status(&self.adapter, req).await }
    #[tool(description = "Bulk parallel disburse loans")]
    async fn bulk_disburse_loans(&self, Parameters(req): Parameters<bulk::BulkDisburseReq>) -> Result<CallToolResult, McpError> { bulk::bulk_disburse_loans(&self.adapter, req).await }
    #[tool(description = "Bulk parallel make loan repayments")]
    async fn bulk_make_repayments(&self, Parameters(req): Parameters<bulk::BulkRepaymentsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_make_repayments(&self.adapter, req).await }
    #[tool(description = "Bulk parallel activate clients")]
    async fn bulk_activate_clients(&self, Parameters(req): Parameters<bulk::BulkActivateReq>) -> Result<CallToolResult, McpError> { bulk::bulk_activate_clients(&self.adapter, req).await }
    #[tool(description = "Bulk parallel fetch savings balances")]
    async fn bulk_get_savings_balances(&self, Parameters(req): Parameters<bulk::BulkSavingsBalancesReq>) -> Result<CallToolResult, McpError> { bulk::bulk_get_savings_balances(&self.adapter, req).await }
    #[tool(description = "Bulk parallel apply fees")]
    async fn bulk_apply_fees(&self, Parameters(req): Parameters<bulk::BulkApplyFeesReq>) -> Result<CallToolResult, McpError> { bulk::bulk_apply_fees(&self.adapter, req).await }
    #[tool(description = "Bulk parallel close accounts")]
    async fn bulk_close_accounts(&self, Parameters(req): Parameters<bulk::BulkCloseAccountsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_close_accounts(&self.adapter, req).await }
    #[tool(description = "MANDATORY: Bulk parallel create savings accounts for multiple clients (both/all)")]
    async fn bulk_create_savings_accounts(&self, Parameters(req): Parameters<bulk::BulkCreateSavingsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_create_savings_accounts(&self.adapter, req).await }
    #[tool(description = "MANDATORY: Bulk parallel approve and activate savings accounts for both/all/multiple IDs")]
    async fn bulk_approve_and_activate_savings(&self, Parameters(req): Parameters<bulk::BulkApproveActivateSavingsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_approve_and_activate_savings(&self.adapter, req).await }
    #[tool(description = "MANDATORY: Bulk parallel deposit into savings accounts for both/all. Use THE EXACT dollar amount from the prompt.")]
    async fn bulk_deposit_savings(&self, Parameters(req): Parameters<bulk::BulkDepositSavingsReq>) -> Result<CallToolResult, McpError> { bulk::bulk_deposit_savings(&self.adapter, req).await }

    #[tool(description = "High-performance hybrid intent router. Returns the list of relevant tools for a given banking query to prune the context window.")]
    async fn get_relevant_tools(&self, Parameters(req): Parameters<IntentRouterReq>) -> Result<CallToolResult, McpError> {
        let tools = self.registry.route_intent_hybrid(&req.query, INTENT_ROUTE_THRESHOLD).await;
        
        Ok(CallToolResult::success(vec![rmcp::model::Content::text(
            serde_json::to_string_pretty(&tools).unwrap_or_else(|_| "[]".to_string())
        )]))
    }
}

#[tool_handler]
impl ServerHandler for MifosMcpServer {
    fn get_info(&self) -> ServerInfo {
        ServerInfo::new(ServerCapabilities::builder().enable_tools().build())
            .with_instructions("Mifos Banking Assistant MCP Server (Domains Architecture)".to_string())
    }
}
