// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use rmcp::schemars;
use rmcp::{ErrorData as McpError, model::{CallToolResult, Content}};
use serde_json::json;
use chrono::Local;

pub fn to_result(val: serde_json::Value) -> Result<CallToolResult, McpError> { Ok(CallToolResult::success(vec![Content::text(serde_json::to_string_pretty(&val).unwrap_or_default())])) }
pub fn to_err(err: anyhow::Error) -> McpError { McpError::internal_error(err.to_string(), None) }
pub fn today() -> String { Local::now().format("%d %B %Y").to_string() }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct SavingsIdReq { pub account_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateSavingsReq { pub client_id: i64, pub product_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct DepositWithdrawReq { 
    pub account_id: i64, 
    #[schemars(description = "The exact dollar amount specified in the original user prompt. MUST be a positive value. NEVER use a default of 100 or 0.0.")]
    pub amount_exactly_from_user_prompt: f64 
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ApplySavingsChargeReq { 
    pub account_id: i64, 
    #[schemars(description = "The exact dollar amount from the user prompt.")]
    pub amount_exactly_from_user_prompt: f64, 
    pub charge_id: Option<i64> 
}

#[derive(Debug, serde::Deserialize, serde::Serialize, schemars::JsonSchema)]
pub struct EmptyReq { pub _unused: Option<bool> }

pub async fn list_savings_products(adapter: &FineractAdapter, _req: EmptyReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get("savingsproducts", None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_savings_account(adapter: &FineractAdapter, req: SavingsIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("savingsaccounts/{}", req.account_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_savings_transactions(adapter: &FineractAdapter, req: SavingsIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("savingsaccounts/{}?associations=transactions", req.account_id), None).await.map_err(to_err)?;
    to_result(json!(res.get("transactions").unwrap_or(&json!([]))))
}

pub async fn create_savings_account(adapter: &FineractAdapter, req: CreateSavingsReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"clientId": req.client_id, "productId": req.product_id.unwrap_or(1), "submittedOnDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post("savingsaccounts", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn approve_and_activate_savings(adapter: &FineractAdapter, req: SavingsIdReq) -> Result<CallToolResult, McpError> {
    let approve_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "approvedOnDate": today()});
    adapter.execute_post(&format!("savingsaccounts/{}?command=approve", req.account_id), &approve_payload).await.map_err(to_err)?;
    let activate_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "activatedOnDate": today()});
    let res = adapter.execute_post(&format!("savingsaccounts/{}?command=activate", req.account_id), &activate_payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn close_savings_account(adapter: &FineractAdapter, req: SavingsIdReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"closedOnDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post(&format!("savingsaccounts/{}?command=close", req.account_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn deposit_savings(adapter: &FineractAdapter, req: DepositWithdrawReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"transactionDate": today(), "transactionAmount": req.amount_exactly_from_user_prompt, "dateFormat": "dd MMMM yyyy", "locale": "en", "paymentTypeId": 1});
    let res = adapter.execute_post(&format!("savingsaccounts/{}/transactions?command=deposit", req.account_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn withdraw_savings(adapter: &FineractAdapter, req: DepositWithdrawReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"transactionDate": today(), "transactionAmount": req.amount_exactly_from_user_prompt, "dateFormat": "dd MMMM yyyy", "locale": "en", "paymentTypeId": 2});
    let res = adapter.execute_post(&format!("savingsaccounts/{}/transactions?command=withdrawal", req.account_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn apply_savings_charge(adapter: &FineractAdapter, req: ApplySavingsChargeReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"chargeId": req.charge_id.unwrap_or(1), "amount": req.amount_exactly_from_user_prompt, "feeOnMonthDay": today().chars().take(5).collect::<String>(), "dueDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post(&format!("savingsaccounts/{}/charges", req.account_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn calculate_and_post_interest(adapter: &FineractAdapter, req: SavingsIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_post(&format!("savingsaccounts/{}?command=postInterest", req.account_id), &json!({})).await.map_err(to_err)?;
    to_result(res)
}
