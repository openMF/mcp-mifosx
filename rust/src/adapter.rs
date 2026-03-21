// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

use anyhow::Result;
use reqwest::{Client, Method, RequestBuilder};
use serde_json::Value;
use std::env;

#[derive(Clone, Debug)]
pub struct FineractAdapter {
    base_url: String,
    tenant_id: String,
    client: Client,
}

impl FineractAdapter {
    pub fn new() -> Result<Self> {
        let base_url = env::var("MIFOSX_BASE_URL").unwrap_or_else(|_| "https://localhost:8443/fineract-provider/api/v1".to_string());
        let tenant_id = env::var("MIFOSX_TENANT_ID").unwrap_or_else(|_| "default".to_string());
        let _username = env::var("MIFOSX_USERNAME").expect("MIFOSX_USERNAME must be set");
        let _password = env::var("MIFOSX_PASSWORD").expect("MIFOSX_PASSWORD must be set");

        let client = Client::builder()
            .danger_accept_invalid_certs(true)
            .build()?;

        Ok(Self {
            base_url,
            tenant_id,
            client,
        })
    }

    fn prepare_request(&self, method: Method, endpoint: &str) -> RequestBuilder {
        let username = env::var("MIFOSX_USERNAME").unwrap_or_default();
        let password = env::var("MIFOSX_PASSWORD").unwrap_or_default();
        
        let url = format!("{}/{}", self.base_url, endpoint);
        
        self.client
            .request(method, &url)
            .basic_auth(username, Some(password))
            .header("Fineract-Platform-TenantId", &self.tenant_id)
            .header("Content-Type", "application/json")
    }

    async fn handle_response(response: reqwest::Response) -> Result<Value> {
        let status = response.status();
        if status.is_success() {
            let text = response.text().await?;
            if text.is_empty() {
                return Ok(serde_json::json!({"status": "Success"}));
            }
            match serde_json::from_str(&text) {
                Ok(json) => Ok(json),
                Err(_) => Ok(serde_json::json!({"response": text})),
            }
        } else {
            let err_text = response.text().await.unwrap_or_else(|_| "Failed to unwrap error text".into());
            
            if let Ok(err_json) = serde_json::from_str::<Value>(&err_text) {
                let mut error_messages = Vec::new();
                
                if let Some(msg) = err_json.get("developerMessage").and_then(|m| m.as_str()) {
                    error_messages.push(msg.to_string());
                }
                
                if let Some(errors) = err_json.get("errors").and_then(|e| e.as_array()) {
                    for err in errors {
                        if let Some(msg) = err.get("defaultUserMessage").and_then(|m| m.as_str()) {
                            error_messages.push(msg.to_string());
                        }
                    }
                }

                if !error_messages.is_empty() {
                    // Unique messages only
                    error_messages.sort();
                    error_messages.dedup();
                    return Err(anyhow::anyhow!("Fineract Error: {}", error_messages.join(" | ")));
                }
            }
            
            Err(anyhow::anyhow!("HTTP {}: {}", status, err_text))
        }
    }

    pub async fn execute_get(&self, endpoint: &str, query: Option<&[(&str, &str)]>) -> Result<Value> {
        tracing::info!("Executing GET: {}/{}", self.base_url, endpoint);
        let mut req = self.prepare_request(Method::GET, endpoint);
        if let Some(q) = query {
            req = req.query(q);
        }
        let res = req.send().await?;
        Self::handle_response(res).await
    }

    pub async fn execute_post(&self, endpoint: &str, payload: &Value) -> Result<Value> {
        tracing::info!("Executing POST: {}/{} with payload: {}", self.base_url, endpoint, payload);
        let req = self.prepare_request(Method::POST, endpoint).json(payload);
        let res = req.send().await?;
        Self::handle_response(res).await
    }

    pub async fn execute_put(&self, endpoint: &str, payload: &Value) -> Result<Value> {
        tracing::info!("Executing PUT: {}/{}", self.base_url, endpoint);
        let req = self.prepare_request(Method::PUT, endpoint).json(payload);
        let res = req.send().await?;
        Self::handle_response(res).await
    }

    #[allow(dead_code)]
    pub async fn execute_delete(&self, endpoint: &str) -> Result<Value> {
        tracing::info!("Executing DELETE: {}/{}", self.base_url, endpoint);
        let req = self.prepare_request(Method::DELETE, endpoint);
        let res = req.send().await?;
        Self::handle_response(res).await
    }
}
