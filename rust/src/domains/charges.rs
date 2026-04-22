// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use rmcp::schemars;
use rmcp::{ErrorData as McpError, model::{CallToolResult, Content}};
use serde_json::json;

pub fn to_result(val: serde_json::Value) -> Result<CallToolResult, McpError> {
    Ok(CallToolResult::success(vec![Content::text(serde_json::to_string_pretty(&val).unwrap_or_default())]))
}

pub fn to_err(err: anyhow::Error) -> McpError {
    McpError::internal_error(err.to_string(), None)
}

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ChargeIdReq { pub charge_id: i64 }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateChargeReq {
    /// The name of the charge
    pub name: String,
    /// CRITICAL: Use the EXACT dollar amount the user stated in their message. Do NOT substitute or round this value.
    pub amount: f64,
    /// 3-letter currency code (e.g., USD)
    pub currency_code: String,
    
    /// Defines where this charge applies. 1: Loan, 2: Savings, 3: Client.
    pub charge_applies_to: i64,
    
    /// Defines when the charge is triggered.
    /// For Loans (chargeAppliesTo=1): 1=Disbursement, 2=Specified due date, 8=Installment fee, 9=Overdue Installment fee, 12=Disbursement Paid With Repayment.
    /// For Savings (chargeAppliesTo=2): 2=Specified due date, 3=Savings Activation, 4=Withdrawal Fee, 5=Annual Fee, 6=Monthly Fee, 7=Overdraft Fee, 10=Weekly Fee, 11=No Activity Fee, 16=Savings Closure.
    pub charge_time_type: i64,
    
    /// Defines how the amount is calculated. 1: Flat, 2: % Amount, 3: % Loan amount + Interest, 4: % Interest.
    pub charge_calculation_type: i64,
    
    /// How the charge is paid. 0=Regular (default), 1=Account Transfer.
    pub charge_payment_mode: Option<i64>,
    
    /// Is the charge active and available for use
    pub active: Option<bool>,
    
    /// Set to true to model it as a Penalty, false to model it as a standard Fee.
    pub penalty: Option<bool>,
    
    /// Required for scheduled savings charges (Annual Fee=5, Monthly Fee=6). Format: "dd MMMM" e.g. "15 January".
    pub fee_on_month_day: Option<String>,
    
    /// Required for scheduled savings charges. Interval between fee occurrences (e.g. 1 for every year/month).
    pub fee_interval: Option<i64>,
}

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct UpdateChargeReq {
    pub charge_id: i64,
    pub name: Option<String>,
    pub amount: Option<f64>,
    pub active: Option<bool>,
}

pub async fn retrieve_charge(adapter: &FineractAdapter, req: ChargeIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("charges/{}", req.charge_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_charge(adapter: &FineractAdapter, req: CreateChargeReq) -> Result<CallToolResult, McpError> {
    let mut payload = json!({
        "name": req.name,
        "amount": req.amount,
        "currencyCode": req.currency_code,
        "chargeAppliesTo": req.charge_applies_to,
        "chargeTimeType": req.charge_time_type,
        "chargeCalculationType": req.charge_calculation_type,
        "chargePaymentMode": req.charge_payment_mode.unwrap_or(0),
        "active": req.active.unwrap_or(true),
        "penalty": req.penalty.unwrap_or(false),
        "locale": "en"
    });
    if let Some(day) = req.fee_on_month_day {
        payload["feeOnMonthDay"] = json!(day);
        payload["monthDayFormat"] = json!("dd MMMM");
    }
    if let Some(interval) = req.fee_interval {
        payload["feeInterval"] = json!(interval);
    }
    let res = adapter.execute_post("charges", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn update_charge(adapter: &FineractAdapter, req: UpdateChargeReq) -> Result<CallToolResult, McpError> {
    let mut payload = json!({ "locale": "en" });
    if let Some(n) = req.name { payload["name"] = json!(n); }
    if let Some(a) = req.amount { payload["amount"] = json!(a); }
    if let Some(act) = req.active { payload["active"] = json!(act); }
    
    let res = adapter.execute_put(&format!("charges/{}", req.charge_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn delete_charge(adapter: &FineractAdapter, req: ChargeIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_delete(&format!("charges/{}", req.charge_id)).await.map_err(to_err)?;
    to_result(res)
}
