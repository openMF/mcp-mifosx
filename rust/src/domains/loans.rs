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
pub struct LoanIdReq { pub loan_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ClientIdReq { pub client_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateLoanReq { pub client_id: i64, pub amount_exactly_from_user_prompt: f64, pub months: i32, pub product_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct UpdateLoanReq { 
    pub loan_id: i64, 
    pub amount_exactly_from_user_prompt: Option<f64>, 
    pub months: Option<i32>, 
    pub product_id: Option<i64> 
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateGroupLoanReq { pub group_id: i64, pub amount_exactly_from_user_prompt: f64, pub months: i32, pub product_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ApproveLoanReq { pub loan_id: i64, pub amount_exactly_from_user_prompt: Option<f64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct RejectLoanReq { pub loan_id: i64, pub note: Option<String> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct RepaymentReq { pub loan_id: i64, pub amount_exactly_from_user_prompt: f64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ApplyLateFeeReq { pub loan_id: i64, pub amount_exactly_from_user_prompt: f64, pub charge_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct WaiveInterestReq { pub loan_id: i64, pub amount_exactly_from_user_prompt: f64, pub note: Option<String> }

#[derive(Debug, serde::Deserialize, serde::Serialize, schemars::JsonSchema)]
pub struct EmptyReq { pub _unused: Option<bool> }

pub async fn list_loan_products(adapter: &FineractAdapter, _req: EmptyReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get("loanproducts", None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_loan_details(adapter: &FineractAdapter, req: LoanIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans/{}", req.loan_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_repayment_schedule(adapter: &FineractAdapter, req: LoanIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans/{}?associations=repaymentSchedule", req.loan_id), None).await.map_err(to_err)?;
    to_result(json!(res.get("repaymentSchedule").unwrap_or(&json!({}))))
}

pub async fn get_loan_history(adapter: &FineractAdapter, req: LoanIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans/{}?associations=transactions,repaymentSchedule,charges", req.loan_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_overdue_loans(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans?clientId={}&loanStatus=300&inArrears=true&associations=repaymentSchedule", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_loan(adapter: &FineractAdapter, req: CreateLoanReq) -> Result<CallToolResult, McpError> {
    let payload = json!({
        "clientId": req.client_id, "productId": req.product_id.unwrap_or(1),
        "principal": req.amount_exactly_from_user_prompt.to_string(), "loanTermFrequency": req.months, "loanTermFrequencyType": 2,
        "numberOfRepayments": req.months, "repaymentEvery": 1, "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 5.0, "amortizationType": 1, "interestType": 0, "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy", "expectedDisbursementDate": today(),
        "submittedOnDate": today(), "locale": "en", "dateFormat": "dd MMMM yyyy", "loanType": "individual"
    });
    let res = adapter.execute_post("loans", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_group_loan(adapter: &FineractAdapter, req: CreateGroupLoanReq) -> Result<CallToolResult, McpError> {
    let payload = json!({
        "groupId": req.group_id, "productId": req.product_id.unwrap_or(1),
        "principal": req.amount_exactly_from_user_prompt.to_string(), "loanTermFrequency": req.months, "loanTermFrequencyType": 2,
        "numberOfRepayments": req.months, "repaymentEvery": 1, "repaymentFrequencyType": 2,
        "interestRatePerPeriod": 5.0, "amortizationType": 1, "interestType": 0, "interestCalculationPeriodType": 1,
        "transactionProcessingStrategyCode": "mifos-standard-strategy", "expectedDisbursementDate": today(),
        "submittedOnDate": today(), "locale": "en", "dateFormat": "dd MMMM yyyy", "loanType": "group"
    });
    let res = adapter.execute_post("loans", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn update_loan(adapter: &FineractAdapter, req: UpdateLoanReq) -> Result<CallToolResult, McpError> {
    // 1. Fetch current state to satisfy Fineract's mandatory field requirement on PUT
    let current = adapter.execute_get(&format!("loans/{}", req.loan_id), None).await.map_err(to_err)?;
    
    // Detect if the product is changing to avoid mixing configurations
    let current_pid = current.get("productId").and_then(|v| v.as_i64())
        .or_else(|| current.get("product").and_then(|p| p.get("id")).and_then(|id| id.as_i64()))
        .unwrap_or(1);
    let target_pid = req.product_id.unwrap_or(current_pid);
    let is_switching_product = target_pid != current_pid;

    let timeline = current.get("timeline");
    let fmt_date = |key: &str| {
        timeline.and_then(|t| t.get(key))
            .and_then(|d| d.as_array())
            .filter(|a| a.len() >= 3)
            .map(|a| format!("{} {} {}", a[2], a[1], a[0]))
    };

    // 2. Prepare payload with existing mandatory fields as baseline
    // If switching product, we use create_loan defaults instead of old baseline state
    let mut payload = json!({
        "productId": target_pid,
        "principal": current.get("principal").and_then(|v| v.as_f64()).unwrap_or(0.0).to_string(),
        "loanTermFrequency": current.get("termFrequency").and_then(|v| v.as_i64())
            .or_else(|| current.get("loanTermFrequency").and_then(|v| v.as_i64()))
            .unwrap_or(1),
        "loanTermFrequencyType": if is_switching_product { 2 } else { current.get("termPeriodFrequencyType").and_then(|v| v.get("id")).and_then(|v| v.as_i64()).unwrap_or(2) },
        "numberOfRepayments": current.get("numberOfRepayments").and_then(|v| v.as_i64()).unwrap_or(1),
        "repaymentEvery": if is_switching_product { 1 } else { current.get("repaymentEvery").and_then(|v| v.as_i64()).unwrap_or(1) },
        "repaymentFrequencyType": if is_switching_product { 2 } else { current.get("repaymentFrequencyType").and_then(|v| v.get("id")).and_then(|v| v.as_i64()).unwrap_or(2) },
        "interestRatePerPeriod": if is_switching_product { 5.0 } else { current.get("interestRatePerPeriod").and_then(|v| v.as_f64()).unwrap_or(5.0) },
        "amortizationType": if is_switching_product { 1 } else { current.get("amortizationType").and_then(|v| v.get("id")).and_then(|v| v.as_i64()).unwrap_or(1) },
        "interestType": if is_switching_product { 0 } else { current.get("interestType").and_then(|v| v.get("id")).and_then(|v| v.as_i64()).unwrap_or(0) },
        "interestCalculationPeriodType": if is_switching_product { 1 } else { current.get("interestCalculationPeriodType").and_then(|v| v.get("id")).and_then(|v| v.as_i64()).unwrap_or(1) },
        "transactionProcessingStrategyCode": if is_switching_product { "mifos-standard-strategy" } else { current.get("transactionProcessingStrategyCode").and_then(|v| v.as_str()).unwrap_or("mifos-standard-strategy") },
        "loanType": current.get("loanType").and_then(|v| v.get("value")).and_then(|v| v.as_str()).map(|s| s.to_lowercase()).unwrap_or_else(|| "individual".to_string()),
        "locale": "en",
        "dateFormat": "dd MMMM yyyy"
    });

    // Only set dates if they exist to avoid mutating historical audit data
    if let Some(d) = fmt_date("expectedDisbursementDate") { payload["expectedDisbursementDate"] = json!(d); }
    if let Some(d) = fmt_date("submittedOnDate") { payload["submittedOnDate"] = json!(d); }

    // 3. Overlay the user's updates
    if let Some(amt) = req.amount_exactly_from_user_prompt { payload["principal"] = json!(amt.to_string()); }
    if let Some(m) = req.months { 
        payload["loanTermFrequency"] = json!(m);
        payload["numberOfRepayments"] = json!(m);
        payload["loanTermFrequencyType"] = json!(2); // Monthly
        payload["repaymentFrequencyType"] = json!(2); // Monthly
        payload["repaymentEvery"] = json!(1); // Every 1 month
    }
    
    let res = adapter.execute_put(&format!("loans/{}", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn delete_loan(adapter: &FineractAdapter, req: LoanIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_delete(&format!("loans/{}", req.loan_id)).await.map_err(to_err)?;
    to_result(res)
}

pub async fn approve_and_disburse_loan(adapter: &FineractAdapter, req: ApproveLoanReq) -> Result<CallToolResult, McpError> {
    let approve_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "approvedOnDate": today(), "note": "AI Approved"});
    adapter.execute_post(&format!("loans/{}?command=approve", req.loan_id), &approve_payload).await.map_err(to_err)?;
    
    let mut disburse_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "actualDisbursementDate": today()});
    if let Some(amt) = req.amount_exactly_from_user_prompt { disburse_payload["transactionAmount"] = json!(amt); }
    let res = adapter.execute_post(&format!("loans/{}?command=disburse", req.loan_id), &disburse_payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn reject_loan_application(adapter: &FineractAdapter, req: RejectLoanReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"rejectedOnDate": today(), "note": req.note.unwrap_or("Rejected via AI Agent".into()), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post(&format!("loans/{}?command=reject", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn make_loan_repayment(adapter: &FineractAdapter, req: RepaymentReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"transactionDate": today(), "transactionAmount": req.amount_exactly_from_user_prompt, "dateFormat": "dd MMMM yyyy", "locale": "en", "paymentTypeId": 1});
    let res = adapter.execute_post(&format!("loans/{}/transactions?command=repayment", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn apply_late_fee(adapter: &FineractAdapter, req: ApplyLateFeeReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"chargeId": req.charge_id.unwrap_or(2), "amount": req.amount_exactly_from_user_prompt, "dueDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post(&format!("loans/{}/charges", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn waive_interest(adapter: &FineractAdapter, req: WaiveInterestReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"transactionDate": today(), "transactionAmount": req.amount_exactly_from_user_prompt, "note": req.note.unwrap_or("AI Authorized Waiver".into()), "dateFormat": "dd MMMM yyyy", "locale": "en"});
    let res = adapter.execute_post(&format!("loans/{}/transactions?command=waiveinterest", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}
