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
pub struct ListGLAccountsReq { pub type_id: Option<i64> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct GetJournalEntriesReq { pub account_id: Option<i64>, pub transaction_id: Option<String> }

#[derive(Debug, serde::Deserialize, serde::Serialize, schemars::JsonSchema)]
pub struct JournalEntryLine {
    pub glAccountId: i64,
    pub amount: f64,
}

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateJournalEntryReq { 
    pub office_id: i64, 
    pub date: String, 
    pub credits: Vec<JournalEntryLine>, 
    pub debits: Vec<JournalEntryLine>, 
    pub comment: Option<String> 
}

pub async fn list_gl_accounts(adapter: &FineractAdapter, req: ListGLAccountsReq) -> Result<CallToolResult, McpError> {
    let mut endpoint = "glaccounts".to_string();
    if let Some(t) = req.type_id { endpoint.push_str(&format!("?type={}", t)); }
    let res = adapter.execute_get(&endpoint, None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_journal_entries(adapter: &FineractAdapter, req: GetJournalEntriesReq) -> Result<CallToolResult, McpError> {
    let mut endpoint = "journalentries".to_string();
    let mut params = Vec::new();
    if let Some(a) = req.account_id { params.push(format!("glAccountId={}", a)); }
    if let Some(t) = &req.transaction_id { params.push(format!("transactionId={}", t)); }
    if !params.is_empty() { endpoint.push_str(&format!("?{}", params.join("&"))); }
    let res = adapter.execute_get(&endpoint, None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_journal_entry(adapter: &FineractAdapter, req: CreateJournalEntryReq) -> Result<CallToolResult, McpError> {
    let payload = json!({"officeId": req.office_id, "transactionDate": req.date, "comments": req.comment.unwrap_or_default(), "dateFormat": "dd MMMM yyyy", "locale": "en", "credits": req.credits, "debits": req.debits});
    let res = adapter.execute_post("journalentries", &payload).await.map_err(to_err)?;
    to_result(res)
}
