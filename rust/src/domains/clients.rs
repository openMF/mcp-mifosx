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
pub struct SearchClientsReq { pub name_query: String }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ClientIdReq { pub client_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateClientReq { pub firstname: String, pub lastname: String, pub mobile_no: Option<String>, pub office_id: Option<i64>, pub is_active: Option<bool> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct UpdateMobileReq { pub client_id: i64, pub new_mobile_no: String }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CloseClientReq { pub client_id: i64, pub closure_reason_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateIdentifierReq { pub client_id: i64, pub document_type_id: i64, pub document_key: String }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ApplyClientChargeReq {
    pub client_id: i64,
    /// The charge template ID to apply. If not specified, auto-discovers a valid client charge.
    pub charge_id: Option<i64>,
    /// The dollar amount to charge. Use the exact number from the user's request. Must be > 0.
    pub amount: f64,
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ClientTransactionIdReq { pub client_id: i64, pub transaction_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct PayClientChargeReq {
    pub client_id: i64,
    /// The client charge ID (from the apply_client_charge response, NOT the charge template ID)
    pub client_charge_id: i64,
    /// The dollar amount to pay. Use the exact number from the user's request. Must be > 0.
    pub amount: f64,
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct WaiveClientChargeReq { pub client_id: i64, pub client_charge_id: i64 }
pub async fn search_clients_by_name(adapter: &FineractAdapter, req: SearchClientsReq) -> Result<CallToolResult, McpError> {
    let qs = [("query", req.name_query.as_str()), ("resource", "clients"), ("exactMatch", "false")];
    let res = adapter.execute_get("search", Some(&qs)).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_details(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_accounts(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/accounts", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_client(adapter: &FineractAdapter, req: CreateClientReq) -> Result<CallToolResult, McpError> {
    let payload = json!({
        "firstname": req.firstname, "lastname": req.lastname, "officeId": req.office_id.unwrap_or(1),
        "active": req.is_active.unwrap_or(false), "locale": "en", "dateFormat": "dd MMMM yyyy",
        "activationDate": today(), "mobileNo": req.mobile_no.unwrap_or_default()
    });
    let res = adapter.execute_post("clients", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn activate_client(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "activationDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("clients/{}?command=activate", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn update_client_mobile(adapter: &FineractAdapter, req: UpdateMobileReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "mobileNo": req.new_mobile_no });
    let res = adapter.execute_put(&format!("clients/{}", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn close_client(adapter: &FineractAdapter, req: CloseClientReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "closureDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en", "closureReasonId": req.closure_reason_id.unwrap_or(17) });
    let res = adapter.execute_post(&format!("clients/{}?command=close", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_identifiers(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/identifiers", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_client_identifier(adapter: &FineractAdapter, req: CreateIdentifierReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "documentTypeId": req.document_type_id, "documentKey": req.document_key });
    let res = adapter.execute_post(&format!("clients/{}/identifiers", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_documents(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/documents", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_charges(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/charges", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn apply_client_charge(adapter: &FineractAdapter, req: ApplyClientChargeReq) -> Result<CallToolResult, McpError> {
    if req.amount <= 0.0 {
        return Ok(CallToolResult::error(vec![Content::text("ERROR: amount was 0 which is invalid. You MUST re-read the user's ORIGINAL message to find the dollar amount they specified. Pass that exact number as the amount parameter. DO NOT use any amount from a previous API response.")]));
    }
    let charge_id = match req.charge_id {
        Some(id) => id,
        None => {
            let templates = adapter.execute_get(&format!("clients/{}/charges/template", req.client_id), None).await.map_err(to_err)?;
            let arr = templates.as_array().ok_or_else(|| McpError::internal_error("Unexpected template response format".to_string(), None))?;
            
            if arr.is_empty() {
                return Err(McpError::internal_error("No client-applicable charge templates found. Create a charge with chargeAppliesTo=3 (Client) first.".to_string(), None));
            } else if arr.len() > 1 {
                let options: Vec<String> = arr.iter()
                    .map(|c| format!("{} (ID: {})", c.get("name").and_then(|v| v.as_str()).unwrap_or("Unknown"), c.get("id").unwrap_or(&json!(0))))
                    .collect();
                return Err(McpError::invalid_request(
                    format!("Ambiguous charge request. Multiple templates found. Please specify a charge_id from: {}", options.join(", ")),
                    None
                ));
            }

            arr[0].get("id").and_then(|id| id.as_i64())
                .ok_or_else(|| McpError::internal_error("Could not find ID in charge template.".to_string(), None))?
        }
    };
    let payload = json!({ "chargeId": charge_id, "amount": req.amount, "dueDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("clients/{}/charges", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn pay_client_charge(adapter: &FineractAdapter, req: PayClientChargeReq) -> Result<CallToolResult, McpError> {
    if req.amount <= 0.0 {
        return Ok(CallToolResult::error(vec![Content::text("ERROR: amount was 0 which is invalid. You MUST re-read the user's ORIGINAL message to find the dollar amount they specified. Pass that exact number as the amount parameter. DO NOT use any amount from a previous API response.")]));
    }
    let payload = json!({ "amount": req.amount, "transactionDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("clients/{}/charges/{}?command=paycharge", req.client_id, req.client_charge_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn waive_client_charge(adapter: &FineractAdapter, req: WaiveClientChargeReq) -> Result<CallToolResult, McpError> {
    let payload = json!({});
    let res = adapter.execute_post(&format!("clients/{}/charges/{}?command=waive", req.client_id, req.client_charge_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_transactions(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/transactions", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_transaction(adapter: &FineractAdapter, req: ClientTransactionIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/transactions/{}", req.client_id, req.transaction_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn undo_client_transaction(adapter: &FineractAdapter, req: ClientTransactionIdReq) -> Result<CallToolResult, McpError> {
    let payload = json!({});
    let res = adapter.execute_post(&format!("clients/{}/transactions/{}?command=undo", req.client_id, req.transaction_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_addresses(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/addresses", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn test_today_format() {
        let date = today();
        assert!(date.contains(' '));
        assert!(date.split(' ').count() == 3);
    }

    #[test]
    fn test_to_result_success() {
        let val = json!({"id": 1, "name": "Test"});
        let result = to_result(val).unwrap();
        assert!(result.is_error.unwrap_or(false) == false);
    }

    #[test]
    fn test_to_err() {
        let err = anyhow::anyhow!("test error");
        let mcp_err = to_err(err);
        assert_eq!(mcp_err.message, "test error");
    }
}
