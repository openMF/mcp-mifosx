// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use crate::domains::savings;
use rmcp::schemars;
use rmcp::{ErrorData as McpError, model::{CallToolResult, ContentBlock}};
use serde_json::json;
use futures::future::join_all;
use chrono::Local;

pub fn to_result(val: serde_json::Value) -> Result<CallToolResult, McpError> { Ok(CallToolResult::success(vec![ContentBlock::text(serde_json::to_string_pretty(&val).unwrap_or_default())])) }
pub fn today() -> String { Local::now().format("%d %B %Y").to_string() }


#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkSearchClientsReq { pub name_queries: Vec<String> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkLoanStatusReq { pub loan_ids: Vec<i64> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct DisburseItem { pub loan_id: i64, pub amount_exactly_from_user_prompt: f64, pub transaction_date: String }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkDisburseReq { pub disbursements: Vec<DisburseItem> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct RepaymentItem { pub loan_id: i64, pub amount_exactly_from_user_prompt: f64, pub transaction_date: String, pub receipt_number: Option<String> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkRepaymentsReq { pub repayments: Vec<RepaymentItem> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkActivateReq { pub client_ids: Vec<i64> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkSavingsBalancesReq { pub savings_ids: Vec<i64> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ApplyFeeItem { pub client_id: i64, pub charge_id: i64, pub amount_exactly_from_user_prompt: f64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkApplyFeesReq { pub fees: Vec<ApplyFeeItem> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CloseAccountItem { pub savings_id: i64, pub closure_reason_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkCloseAccountsReq { pub closures: Vec<CloseAccountItem> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateSavingsItem { pub client_id: i64, pub product_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkCreateSavingsReq { pub accounts: Vec<CreateSavingsItem> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkApproveActivateSavingsReq { pub savings_ids: Vec<i64> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct DepositItem {
    pub savings_id: i64,
    pub amount_exactly_from_user_prompt: f64,
    #[schemars(description = "Id of the Fineract payment type for this deposit. Required by Fineract and different on every server. If any deposit leaves it out, nothing in the batch is posted and the error lists the configured payment types.")]
    pub payment_type_id: Option<i64>,
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct BulkDepositSavingsReq { pub deposits: Vec<DepositItem> }



pub async fn bulk_search_clients(adapter: &FineractAdapter, req: BulkSearchClientsReq) -> Result<CallToolResult, McpError> {
    let futures = req.name_queries.into_iter().map(|q| {
        let adapter_ref = adapter.clone();
        async move {
            let qs: [(&str, &str); 3] = [("query", &q), ("resource", "clients"), ("exactMatch", "false")];
            let res = adapter_ref.execute_get("search", Some(&qs)).await;
            json!({"query": q, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    let results = join_all(futures).await;
    to_result(json!(results))
}


pub async fn bulk_get_loan_status(adapter: &FineractAdapter, req: BulkLoanStatusReq) -> Result<CallToolResult, McpError> {
    let futures = req.loan_ids.into_iter().map(|id| {
        let adapter_ref = adapter.clone();
        async move {
            let res = adapter_ref.execute_get(&format!("loans/{}", id), None).await;
            json!({"loanId": id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_disburse_loans(adapter: &FineractAdapter, req: BulkDisburseReq) -> Result<CallToolResult, McpError> {
    let futures = req.disbursements.into_iter().map(|item| {
        let adapter_ref = adapter.clone();
        async move {
            let payload = json!({ "actualDisbursementDate": item.transaction_date, "transactionAmount": item.amount_exactly_from_user_prompt, "dateFormat": "dd MMMM yyyy", "locale": "en" });
            let res = adapter_ref.execute_post(&format!("loans/{}?command=disburse", item.loan_id), &payload).await;
            json!({"loanId": item.loan_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_make_repayments(adapter: &FineractAdapter, req: BulkRepaymentsReq) -> Result<CallToolResult, McpError> {
    let futures = req.repayments.into_iter().map(|item| {
        let adapter_ref = adapter.clone();
        async move {
            let mut payload = json!({ "transactionDate": item.transaction_date, "transactionAmount": item.amount_exactly_from_user_prompt, "dateFormat": "dd MMMM yyyy", "locale": "en" });
            if let Some(r) = item.receipt_number { payload["receiptNumber"] = json!(r); }
            let res = adapter_ref.execute_post(&format!("loans/{}/transactions?command=repayment", item.loan_id), &payload).await;
            json!({"loanId": item.loan_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_activate_clients(adapter: &FineractAdapter, req: BulkActivateReq) -> Result<CallToolResult, McpError> {
    let date = today();
    let futures = req.client_ids.into_iter().map(|id| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let payload = json!({ "activationDate": date_ref, "dateFormat": "dd MMMM yyyy", "locale": "en" });
            let res = adapter_ref.execute_post(&format!("clients/{}?command=activate", id), &payload).await;
            json!({"clientId": id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_get_savings_balances(adapter: &FineractAdapter, req: BulkSavingsBalancesReq) -> Result<CallToolResult, McpError> {
    let futures = req.savings_ids.into_iter().map(|id| {
        let adapter_ref = adapter.clone();
        async move {
            let res = adapter_ref.execute_get(&format!("savingsaccounts/{}", id), None).await;
            json!({"savingsId": id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_apply_fees(adapter: &FineractAdapter, req: BulkApplyFeesReq) -> Result<CallToolResult, McpError> {
    let date = today();
    let futures = req.fees.into_iter().map(|item| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let payload = json!({ "chargeId": item.charge_id, "amount": item.amount_exactly_from_user_prompt, "dueDate": date_ref, "dateFormat": "dd MMMM yyyy", "locale": "en" });
            let res = adapter_ref.execute_post(&format!("clients/{}/charges", item.client_id), &payload).await;
            json!({"clientId": item.client_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_close_accounts(adapter: &FineractAdapter, req: BulkCloseAccountsReq) -> Result<CallToolResult, McpError> {
    let date = today();
    let futures = req.closures.into_iter().map(|item| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let payload = json!({ "closedOnDate": date_ref, "closureReasonId": item.closure_reason_id, "dateFormat": "dd MMMM yyyy", "locale": "en" });
            let res = adapter_ref.execute_post(&format!("savingsaccounts/{}?command=close", item.savings_id), &payload).await;
            json!({"savingsId": item.savings_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}

pub async fn bulk_create_savings_accounts(adapter: &FineractAdapter, req: BulkCreateSavingsReq) -> Result<CallToolResult, McpError> {
    let date = today();
    let futures = req.accounts.into_iter().map(|item| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let payload = json!({"clientId": item.client_id, "productId": item.product_id.unwrap_or(1), "submittedOnDate": date_ref, "dateFormat": "dd MMMM yyyy", "locale": "en"});
            let res = adapter_ref.execute_post("savingsaccounts", &payload).await;
            json!({"clientId": item.client_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


pub async fn bulk_approve_and_activate_savings(adapter: &FineractAdapter, req: BulkApproveActivateSavingsReq) -> Result<CallToolResult, McpError> {
    let date = today();
    let futures = req.savings_ids.into_iter().map(|id| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let approve_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "approvedOnDate": date_ref});
            let _ = adapter_ref.execute_post(&format!("savingsaccounts/{}?command=approve", id), &approve_payload).await;
            let activate_payload = json!({"dateFormat": "dd MMMM yyyy", "locale": "en", "activatedOnDate": date_ref});
            let res = adapter_ref.execute_post(&format!("savingsaccounts/{}?command=activate", id), &activate_payload).await;
            json!({"savingsId": id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}


/// Deposits into several savings accounts in parallel.
///
/// Every deposit needs its own payment type. The batch is checked before anything is
/// sent: if any deposit has no `payment_type_id`, no deposit is posted and the error
/// lists the configured payment types, so a batch is never half-posted.
pub async fn bulk_deposit_savings(adapter: &FineractAdapter, req: BulkDepositSavingsReq) -> Result<CallToolResult, McpError> {
    let Some(deposits) = req
        .deposits
        .into_iter()
        .map(|item| item.payment_type_id.map(|payment_type_id| (item, payment_type_id)))
        .collect::<Option<Vec<_>>>()
    else {
        return savings::payment_type_required(adapter, "deposit").await;
    };
    let date = today();
    let futures = deposits.into_iter().map(|(item, payment_type_id)| {
        let adapter_ref = adapter.clone();
        let date_ref = date.clone();
        async move {
            let payload = savings::savings_transaction_payload(&date_ref, item.amount_exactly_from_user_prompt, payment_type_id);
            let res = adapter_ref.execute_post(&format!("savingsaccounts/{}/transactions?command=deposit", item.savings_id), &payload).await;
            json!({"savingsId": item.savings_id, "result": res.unwrap_or_else(|e| json!({"error": e.to_string()}))})
        }
    });
    to_result(json!(join_all(futures).await))
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adapter::stub;

    fn item(savings_id: i64, payment_type_id: Option<i64>) -> DepositItem {
        DepositItem { savings_id, amount_exactly_from_user_prompt: 100.0, payment_type_id }
    }

    #[tokio::test]
    async fn bulk_deposit_posts_each_deposit_with_its_own_payment_type() {
        let stub = stub::start(Some(json!([]))).await;
        let req = BulkDepositSavingsReq { deposits: vec![item(3, Some(4)), item(5, Some(1))] };
        bulk_deposit_savings(&stub.adapter, req).await.unwrap();

        let mut sent: Vec<(String, serde_json::Value)> =
            stub.requests_with("POST").into_iter().map(|r| (r.path, r.body["paymentTypeId"].clone())).collect();
        sent.sort_by(|a, b| a.0.cmp(&b.0));
        assert_eq!(sent, vec![
            ("/savingsaccounts/3/transactions?command=deposit".to_string(), json!(4)),
            ("/savingsaccounts/5/transactions?command=deposit".to_string(), json!(1)),
        ]);
    }

    #[tokio::test]
    async fn bulk_deposit_with_one_missing_payment_type_posts_nothing() {
        let stub = stub::start(Some(json!([{"id": 4, "name": "Cash"}]))).await;
        let req = BulkDepositSavingsReq { deposits: vec![item(3, Some(4)), item(5, None)] };
        let result = bulk_deposit_savings(&stub.adapter, req).await.unwrap();

        let (text, is_error) = stub::result_text(&result);
        assert!(is_error);
        assert!(text.contains("4 = Cash"), "{text}");
        assert!(stub.requests_with("POST").is_empty());
    }
}
