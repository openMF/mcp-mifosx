// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use crate::adapter::FineractAdapter;
use rmcp::schemars;
use rmcp::{ErrorData as McpError, model::{CallToolResult, Content}};

pub fn to_result(val: serde_json::Value) -> Result<CallToolResult, McpError> {
    Ok(CallToolResult::success(vec![Content::text(serde_json::to_string_pretty(&val).unwrap_or_default())]))
}
pub fn to_err(err: anyhow::Error) -> McpError { McpError::internal_error(err.to_string(), None) }

#[derive(Debug, serde::Deserialize, serde::Serialize, schemars::JsonSchema)]
pub struct EmptyReq { pub _unused: Option<bool> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ListStaffReq { pub office_id: Option<i64>, pub status: Option<String> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct StaffIdReq { pub staff_id: i64 }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct OfficeIdReq { pub office_id: i64 }

pub async fn list_staff(adapter: &FineractAdapter, req: ListStaffReq) -> Result<CallToolResult, McpError> {
    let mut endpoint = "staff".to_string();
    let mut params = Vec::new();
    if let Some(o) = req.office_id { params.push(format!("officeId={}", o)); }
    if let Some(s) = &req.status { params.push(format!("status={}", s)); }
    if !params.is_empty() { endpoint.push_str(&format!("?{}", params.join("&"))); }
    let res = adapter.execute_get(&endpoint, None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_staff_details(adapter: &FineractAdapter, req: StaffIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("staff/{}", req.staff_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn list_offices(adapter: &FineractAdapter, _req: EmptyReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get("offices", None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_office_details(adapter: &FineractAdapter, req: OfficeIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("offices/{}", req.office_id), None).await.map_err(to_err)?;
    to_result(res)
}
