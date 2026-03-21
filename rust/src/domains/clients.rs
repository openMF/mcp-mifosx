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
pub struct ApplyClientChargeReq { pub client_id: i64, pub charge_id: i64, pub amount_exactly_from_user_prompt: f64 }

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
    let mut payload = json!({
        "officeId": req.office_id.unwrap_or(1), "firstname": req.firstname, "lastname": req.lastname,
        "dateFormat": "dd MMMM yyyy", "locale": "en", "legalFormId": 1
    });
    if req.is_active.unwrap_or(true) {
        payload["activationDate"] = json!(today()); payload["active"] = json!(true);
    } else {
        payload["active"] = json!(false);
    }
    if let Some(mob) = req.mobile_no { payload["mobileNo"] = json!(mob); }
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
    let payload = json!({ "closureDate": today(), "closureReasonId": req.closure_reason_id.unwrap_or(17), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("clients/{}?command=close", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_identifiers(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/identifiers", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_client_identifier(adapter: &FineractAdapter, req: CreateIdentifierReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "documentTypeId": req.document_type_id, "documentKey": req.document_key, "description": "Added via AI Agent" });
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
    let payload = json!({ "chargeId": req.charge_id, "amount": req.amount_exactly_from_user_prompt, "dueDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("clients/{}/charges", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_transactions(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/transactions", req.client_id), None).await.map_err(to_err)?;
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
