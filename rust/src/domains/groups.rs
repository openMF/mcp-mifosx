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
pub struct ListGroupsReq { pub office_id: Option<i64> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct GroupIdReq { pub group_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateGroupReq { pub name: String, pub office_id: i64, pub external_id: Option<String>, pub client_members: Option<Vec<i64>> }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct AddGroupMemberReq { pub group_id: i64, pub client_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CenterIdReq { pub center_id: i64 }

pub async fn list_groups(adapter: &FineractAdapter, req: ListGroupsReq) -> Result<CallToolResult, McpError> {
    let mut endpoint = "groups".to_string();
    if let Some(id) = req.office_id { endpoint.push_str(&format!("?officeId={}", id)); }
    let res = adapter.execute_get(&endpoint, None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_group(adapter: &FineractAdapter, req: GroupIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("groups/{}?associations=all", req.group_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_group(adapter: &FineractAdapter, req: CreateGroupReq) -> Result<CallToolResult, McpError> {
    let mut payload = json!({ "name": req.name, "officeId": req.office_id, "active": false, "externalId": req.external_id });
    if let Some(members) = req.client_members { payload["clientMembers"] = json!(members); }
    let res = adapter.execute_post("groups", &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn activate_group(adapter: &FineractAdapter, req: GroupIdReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "activationDate": today(), "dateFormat": "dd MMMM yyyy", "locale": "en" });
    let res = adapter.execute_post(&format!("groups/{}?command=activate", req.group_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn add_group_member(adapter: &FineractAdapter, req: AddGroupMemberReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "clientMembers": [req.client_id] });
    let res = adapter.execute_post(&format!("groups/{}?command=associateClients", req.group_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn list_centers(adapter: &FineractAdapter, req: ListGroupsReq) -> Result<CallToolResult, McpError> {
    let mut endpoint = "centers".to_string();
    if let Some(id) = req.office_id { endpoint.push_str(&format!("?officeId={}", id)); }
    let res = adapter.execute_get(&endpoint, None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_center(adapter: &FineractAdapter, req: CenterIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("centers/{}?associations=groupMembers,collectionSheet", req.center_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_center(adapter: &FineractAdapter, req: CreateGroupReq) -> Result<CallToolResult, McpError> {
    let payload = json!({ "name": req.name, "officeId": req.office_id, "active": false, "externalId": req.external_id });
    let res = adapter.execute_post("centers", &payload).await.map_err(to_err)?;
    to_result(res)
}
