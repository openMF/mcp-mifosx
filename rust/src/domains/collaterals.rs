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
pub struct LoanIdReq { pub loan_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ClientIdReq { pub client_id: i64 }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct LoanCollateralIdReq { pub loan_id: i64, pub collateral_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateLoanCollateralReq {
    pub loan_id: i64,
    /// The collateral type code value ID. If unsure, leave as None and the system will auto-discover or create one.
    pub collateral_type_id: Option<i64>,
    pub value: Option<f64>,
    pub description: Option<String>,
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct UpdateLoanCollateralReq { pub loan_id: i64, pub collateral_id: i64, pub value: Option<f64>, pub description: Option<String> }

#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct ClientCollateralIdReq { pub client_id: i64, pub collateral_id: i64 }
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct CreateClientCollateralReq {
    pub client_id: i64,
    /// The collateral type ID from the system's code values. If unsure, leave as None and the system will auto-discover available types.
    pub collateral_type_id: Option<i64>,
    /// The monetary value of the collateral asset.
    pub value: Option<f64>,
    /// Number of units of this collateral (e.g. 1 for a single property). Defaults to 1.
    pub quantity: Option<i64>,
    /// Description of the collateral asset (e.g. "3-bedroom house on Main St").
    pub description: Option<String>,
}
#[derive(Debug, serde::Deserialize, schemars::JsonSchema)]
pub struct UpdateClientCollateralReq { pub client_id: i64, pub collateral_id: i64, pub value: Option<f64>, pub description: Option<String>, pub quantity: Option<i64> }


pub async fn list_loan_collaterals(adapter: &FineractAdapter, req: LoanIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans/{}/collaterals", req.loan_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_loan_collateral(adapter: &FineractAdapter, req: LoanCollateralIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("loans/{}/collaterals/{}", req.loan_id, req.collateral_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_loan_collateral(adapter: &FineractAdapter, req: CreateLoanCollateralReq) -> Result<CallToolResult, McpError> {
    let ctype_id = match req.collateral_type_id {
        Some(id) => id,
        None => {
            let template = adapter.execute_get(&format!("loans/{}/collaterals/template", req.loan_id), None).await.map_err(to_err)?;
            let types = template.get("allowedCollateralTypes")
                .and_then(|opts| opts.as_array())
                .ok_or_else(|| McpError::internal_error("Unexpected loan collateral template format".to_string(), None))?;

            if types.len() > 1 {
                let options: Vec<String> = types.iter()
                    .map(|c| format!("{} (ID: {})", c.get("name").and_then(|v| v.as_str()).unwrap_or("Unknown"), c.get("id").unwrap_or(&json!(0))))
                    .collect();
                return Err(McpError::internal_error(
                    format!("Ambiguous collateral request. Multiple types allowed for this loan. Please specify a collateral_type_id from: {}", options.join(", ")),
                    None
                ));
            }

            match types.first().and_then(|c| c.get("id")).and_then(|id| id.as_i64()) {
                Some(id) => id,
                None => {
                    let codes = adapter.execute_get("codes", None).await.map_err(to_err)?;
                    let code_id = codes.as_array()
                        .and_then(|arr| arr.iter().find(|c| c.get("name").and_then(|n| n.as_str()) == Some("LoanCollateral")))
                        .and_then(|c| c.get("id"))
                        .and_then(|id| id.as_i64())
                        .ok_or_else(|| McpError::internal_error("LoanCollateral code not found in Fineract.".to_string(), None))?;

                    let existing_vals = adapter.execute_get(&format!("codes/{}/codevalues", code_id), None).await.map_err(to_err)?;
                    let name_query = req.description.clone().unwrap_or_else(|| "Vehicle".to_string());
                    let found_id = existing_vals.as_array()
                        .and_then(|arr| arr.iter().find(|v| v.get("name").and_then(|n| n.as_str()) == Some(&name_query)))
                        .and_then(|v| v.get("id"))
                        .and_then(|id| id.as_i64());

                    if let Some(id) = found_id {
                        id
                    } else {
                        let new_val = json!({ "name": &name_query, "isActive": true, "position": 1 });
                        let created = adapter.execute_post(&format!("codes/{}/codevalues", code_id), &new_val).await.map_err(to_err)?;
                        created.get("subResourceId").or_else(|| created.get("resourceId")).and_then(|id| id.as_i64())
                            .ok_or_else(|| McpError::internal_error(format!("Failed to create LoanCollateral code value. Response: {}", created), None))?
                    }
                }
            }
        }
    };
    let mut payload = json!({ "collateralTypeId": ctype_id, "locale": "en" });
    if let Some(v) = req.value { payload["value"] = json!(v); }
    if let Some(d) = req.description { payload["description"] = json!(d); }
    
    let res = adapter.execute_post(&format!("loans/{}/collaterals", req.loan_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn update_loan_collateral(adapter: &FineractAdapter, req: UpdateLoanCollateralReq) -> Result<CallToolResult, McpError> {
    let existing = adapter.execute_get(&format!("loans/{}/collaterals/{}", req.loan_id, req.collateral_id), None).await.map_err(to_err)?;
    let ctype_id = existing.get("type")
        .and_then(|t| t.get("id"))
        .and_then(|id| id.as_i64())
        .ok_or_else(|| McpError::internal_error("Could not find collateral type ID in existing record. Update aborted to prevent data corruption.".to_string(), None))?;

    let mut payload = json!({
        "collateralTypeId": ctype_id,
        "locale": "en"
    });
    if let Some(v) = req.value { payload["value"] = json!(v); }
    if let Some(d) = req.description { payload["description"] = json!(d); }
    
    let res = adapter.execute_put(&format!("loans/{}/collaterals/{}", req.loan_id, req.collateral_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn delete_loan_collateral(adapter: &FineractAdapter, req: LoanCollateralIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_delete(&format!("loans/{}/collaterals/{}", req.loan_id, req.collateral_id)).await.map_err(to_err)?;
    to_result(res)
}


pub async fn list_client_collaterals(adapter: &FineractAdapter, req: ClientIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/collaterals", req.client_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn get_client_collateral(adapter: &FineractAdapter, req: ClientCollateralIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_get(&format!("clients/{}/collaterals/{}", req.client_id, req.collateral_id), None).await.map_err(to_err)?;
    to_result(res)
}

pub async fn create_client_collateral(adapter: &FineractAdapter, req: CreateClientCollateralReq) -> Result<CallToolResult, McpError> {
    let ctype_id = match req.collateral_type_id {
        Some(id) => id,
        None => {
            let mgmt = adapter.execute_get("collateral-management", None).await;
            let arr = mgmt.ok().and_then(|v| v.as_array().cloned()).unwrap_or_default();

            if arr.len() > 1 {
                let options: Vec<String> = arr.iter()
                    .map(|c| format!("{} (ID: {})", c.get("name").and_then(|v| v.as_str()).unwrap_or("Unknown"), c.get("id").unwrap_or(&json!(0))))
                    .collect();
                return Err(McpError::internal_error(
                    format!("Ambiguous collateral request. Multiple global collateral types found. Please specify a collateral_type_id from: {}", options.join(", ")),
                    None
                ));
            }

            match arr.first().and_then(|c| c.get("id")).and_then(|id| id.as_i64()) {
                Some(id) => id,
                None => {
                    let new_type = json!({
                        "name": req.description.clone().unwrap_or_else(|| "General Collateral".to_string()),
                        "quality": "Super",
                        "basePrice": req.value.unwrap_or(1.0),
                        "unitType": "WHOLE",
                        "pctToBase": 100,
                        "currency": "USD",
                        "locale": "en"
                    });
                    let created = adapter.execute_post("collateral-management", &new_type).await.map_err(to_err)?;
                    created.get("resourceId").and_then(|id| id.as_i64())
                        .ok_or_else(|| McpError::internal_error(format!("Failed to auto-create collateral type. Response: {}", created), None))?
                }
            }
        }
    };
    let mut payload = json!({ "collateralId": ctype_id, "quantity": req.quantity.unwrap_or(1), "locale": "en" });
    if let Some(v) = req.value { payload["value"] = json!(v); }
    if let Some(d) = req.description { payload["description"] = json!(d); }
    
    let res = adapter.execute_post(&format!("clients/{}/collaterals", req.client_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn update_client_collateral(adapter: &FineractAdapter, req: UpdateClientCollateralReq) -> Result<CallToolResult, McpError> {
    let existing = adapter.execute_get(&format!("clients/{}/collaterals/{}", req.client_id, req.collateral_id), None).await.map_err(to_err)?;
    let collateral_id = existing.get("collateralId")
        .or_else(|| existing.get("collateral").and_then(|c| c.get("id")))
        .and_then(|id| id.as_i64())
        .ok_or_else(|| McpError::internal_error("Could not find collateral ID in existing record. Update aborted to prevent data corruption.".to_string(), None))?;
    let existing_qty = existing.get("quantity").and_then(|q| q.as_i64()).unwrap_or(1);

    let mut payload = json!({
        "collateralId": collateral_id,
        "quantity": req.quantity.unwrap_or(existing_qty),
        "locale": "en"
    });
    if let Some(v) = req.value { payload["value"] = json!(v); }
    if let Some(d) = req.description { payload["description"] = json!(d); }
    
    let res = adapter.execute_put(&format!("clients/{}/collaterals/{}", req.client_id, req.collateral_id), &payload).await.map_err(to_err)?;
    to_result(res)
}

pub async fn delete_client_collateral(adapter: &FineractAdapter, req: ClientCollateralIdReq) -> Result<CallToolResult, McpError> {
    let res = adapter.execute_delete(&format!("clients/{}/collaterals/{}", req.client_id, req.collateral_id)).await.map_err(to_err)?;
    to_result(res)
}
