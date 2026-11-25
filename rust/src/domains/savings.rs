// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use rmcp::schemars;
use rmcp::{ErrorData as McpError, model::{CallToolResult, ContentBlock}};
use serde_json::json;
use chrono::Local;

pub fn to_result(val: serde_json::Value) -> Result<CallToolResult, McpError> { Ok(CallToolResult::success(vec![ContentBlock::text(serde_json::to_string_pretty(&val).unwrap_or_default())])) }
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
    pub amount_exactly_from_user_prompt: f64,
    #[schemars(description = "Id of the Fineract payment type (how the money moved). Required by Fineract and different on every server. If you leave it out, nothing is posted and the error lists the configured payment types so you can ask the user which one applies.")]
    pub payment_type_id: Option<i64>,
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

/// Body of a savings deposit or withdrawal, posted to
/// `savingsaccounts/{id}/transactions?command=deposit|withdrawal`.
///
/// `payment_type_id` is not optional here: Fineract's savings transaction validator
/// requires it, and the ids are tenant-specific, so there is no value that is right
/// everywhere. Callers resolve a missing one with [`payment_type_required`] instead.
pub fn savings_transaction_payload(date: &str, amount: f64, payment_type_id: i64) -> serde_json::Value {
    json!({"transactionDate": date, "transactionAmount": amount, "dateFormat": "dd MMMM yyyy", "locale": "en", "paymentTypeId": payment_type_id})
}

/// The payment types a caller can choose from, as `(id, name)` pairs, read from the
/// `GET paymenttypes` response. Entries without a numeric id are skipped.
pub fn payment_type_options(payment_types: &serde_json::Value) -> Vec<(i64, String)> {
    payment_types
        .as_array()
        .map(|types| {
            types
                .iter()
                .filter_map(|t| {
                    let id = t.get("id")?.as_i64()?;
                    let name = t.get("name").and_then(|n| n.as_str()).unwrap_or("").to_string();
                    Some((id, name))
                })
                .collect()
        })
        .unwrap_or_default()
}

/// Message for a deposit or withdrawal sent without a payment type, given the options
/// from [`payment_type_options`]. `action` is "deposit" or "withdrawal".
pub fn payment_type_required_message(action: &str, options: &[(i64, String)]) -> String {
    if options.is_empty() {
        return format!(
            "payment_type_id is required for a {action}, but no payment types are configured. \
             Add a payment type in Fineract first."
        );
    }
    let listed: Vec<String> = options.iter().map(|(id, name)| format!("{id} = {name}")).collect();
    format!(
        "payment_type_id is required for a {action}. Ask the user which payment type applies: {}.",
        listed.join("; ")
    )
}

/// Tool error for a deposit or withdrawal without a payment type. Nothing is posted;
/// the configured payment types are listed so the agent can ask the user which applies.
/// If the payment types cannot be loaded, the error says so and gives the reason.
pub async fn payment_type_required(adapter: &FineractAdapter, action: &str) -> Result<CallToolResult, McpError> {
    let message = match adapter.execute_get("paymenttypes", None).await {
        Ok(types) => payment_type_required_message(action, &payment_type_options(&types)),
        Err(e) => format!(
            "payment_type_id is required for a {action}, and the payment types could not be loaded: {e}"
        ),
    };
    Ok(CallToolResult::error(vec![ContentBlock::text(message)]))
}

pub async fn deposit_savings(adapter: &FineractAdapter, req: DepositWithdrawReq) -> Result<CallToolResult, McpError> {
    let Some(payment_type_id) = req.payment_type_id else {
        return payment_type_required(adapter, "deposit").await;
    };
    let payload = savings_transaction_payload(&today(), req.amount_exactly_from_user_prompt, payment_type_id);
    let res = adapter.execute_post(&format!("savingsaccounts/{}/transactions?command=deposit", req.account_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn withdraw_savings(adapter: &FineractAdapter, req: DepositWithdrawReq) -> Result<CallToolResult, McpError> {
    let Some(payment_type_id) = req.payment_type_id else {
        return payment_type_required(adapter, "withdrawal").await;
    };
    let payload = savings_transaction_payload(&today(), req.amount_exactly_from_user_prompt, payment_type_id);
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::adapter::stub;

    fn configured_types() -> serde_json::Value {
        json!([{"id": 1, "name": "Money Transfer"}, {"id": 4, "name": "Cash"}])
    }

    fn deposit(payment_type_id: Option<i64>) -> DepositWithdrawReq {
        DepositWithdrawReq { account_id: 3, amount_exactly_from_user_prompt: 500.0, payment_type_id }
    }

    #[test]
    fn savings_transaction_payload_carries_the_given_payment_type() {
        let payload = savings_transaction_payload("26 November 2026", 500.0, 4);
        assert_eq!(payload["paymentTypeId"], 4);
        assert_eq!(payload["transactionAmount"], 500.0);
    }

    #[test]
    fn payment_type_options_skip_entries_without_a_numeric_id() {
        let types = json!([{"id": 4, "name": "Cash"}, {"name": "No id"}, {"id": "x", "name": "Bad id"}]);
        assert_eq!(payment_type_options(&types), vec![(4, "Cash".to_string())]);
        assert!(payment_type_options(&json!({"error": "not a list"})).is_empty());
    }

    #[test]
    fn required_message_lists_the_options() {
        let message = payment_type_required_message("deposit", &[(1, "Money Transfer".into()), (4, "Cash".into())]);
        assert!(message.contains("payment_type_id is required for a deposit"));
        assert!(message.contains("1 = Money Transfer; 4 = Cash"));
    }

    #[test]
    fn required_message_says_when_none_are_configured() {
        let message = payment_type_required_message("withdrawal", &[]);
        assert!(message.contains("no payment types are configured"));
    }

    #[tokio::test]
    async fn deposit_posts_the_payment_type_it_was_given() {
        let stub = stub::start(Some(configured_types())).await;
        deposit_savings(&stub.adapter, deposit(Some(4))).await.unwrap();

        let posts = stub.requests_with("POST");
        assert_eq!(posts.len(), 1);
        assert_eq!(posts[0].path, "/savingsaccounts/3/transactions?command=deposit");
        assert_eq!(posts[0].body["paymentTypeId"], 4);
    }

    #[tokio::test]
    async fn withdrawal_posts_the_payment_type_it_was_given() {
        let stub = stub::start(Some(configured_types())).await;
        withdraw_savings(&stub.adapter, deposit(Some(1))).await.unwrap();

        let posts = stub.requests_with("POST");
        assert_eq!(posts.len(), 1);
        assert_eq!(posts[0].path, "/savingsaccounts/3/transactions?command=withdrawal");
        assert_eq!(posts[0].body["paymentTypeId"], 1);
    }

    #[tokio::test]
    async fn deposit_without_a_payment_type_posts_nothing_and_lists_the_options() {
        let stub = stub::start(Some(configured_types())).await;
        let result = deposit_savings(&stub.adapter, deposit(None)).await.unwrap();

        let (text, is_error) = stub::result_text(&result);
        assert!(is_error);
        assert!(text.contains("1 = Money Transfer; 4 = Cash"), "{text}");
        assert!(stub.requests_with("POST").is_empty());
    }

    #[tokio::test]
    async fn withdrawal_without_a_payment_type_posts_nothing() {
        let stub = stub::start(Some(configured_types())).await;
        let result = withdraw_savings(&stub.adapter, deposit(None)).await.unwrap();

        let (text, is_error) = stub::result_text(&result);
        assert!(is_error);
        assert!(text.contains("required for a withdrawal"), "{text}");
        assert!(stub.requests_with("POST").is_empty());
    }

    #[tokio::test]
    async fn payment_types_that_cannot_be_loaded_are_reported_not_hidden() {
        let stub = stub::start(None).await;
        let result = deposit_savings(&stub.adapter, deposit(None)).await.unwrap();

        let (text, is_error) = stub::result_text(&result);
        assert!(is_error);
        assert!(text.contains("could not be loaded"), "{text}");
        assert!(stub.requests_with("POST").is_empty());
    }
}
