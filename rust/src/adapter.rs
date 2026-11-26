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
        
        // Default to false for security, only skip verification if explicitly set
        let skip_tls_verify = env::var("MIFOSX_SKIP_TLS_VERIFY")
            .map(|v| v.to_lowercase() == "true")
            .unwrap_or(false);

        let client = Client::builder()
            .danger_accept_invalid_certs(skip_tls_verify)
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

    /// Adapter pointed at `base_url` with the default tenant, without reading the
    /// environment. Used by tests that run the tools against a local stub server.
    #[cfg(test)]
    pub fn with_base_url(base_url: &str) -> Self {
        Self { base_url: base_url.to_string(), tenant_id: "default".to_string(), client: Client::new() }
    }

    #[allow(dead_code)]
    pub async fn execute_delete(&self, endpoint: &str) -> Result<Value> {
        tracing::info!("Executing DELETE: {}/{}", self.base_url, endpoint);
        let req = self.prepare_request(Method::DELETE, endpoint);
        let res = req.send().await?;
        Self::handle_response(res).await
    }
}

/// A local stand-in for Fineract, for offline tests of the tools.
///
/// It records every request it receives (method, path with query, JSON body) so a
/// test can check exactly what a tool sent, or that it sent nothing. `GET paymenttypes`
/// answers with the configured payment types, or HTTP 500 when there are none
/// configured (`None`); every other request answers `{"resourceId": 1}`.
#[cfg(test)]
pub mod stub {
    use super::FineractAdapter;
    use axum::{
        body::Bytes,
        http::{Method, StatusCode, Uri},
        Json, Router,
    };
    use serde_json::{json, Value};
    use std::sync::{Arc, Mutex};

    /// One request the stub received.
    #[derive(Debug, Clone)]
    pub struct Recorded {
        pub method: String,
        /// Path and query, e.g. `/savingsaccounts/3/transactions?command=deposit`.
        pub path: String,
        /// JSON body, or `Value::Null` when there was none.
        pub body: Value,
    }

    /// A running stub: an adapter pointed at it, and the requests it has received.
    pub struct Stub {
        pub adapter: FineractAdapter,
        pub requests: Arc<Mutex<Vec<Recorded>>>,
    }

    impl Stub {
        /// Requests received so far, in order.
        pub fn requests(&self) -> Vec<Recorded> {
            self.requests.lock().unwrap().clone()
        }

        /// Requests received so far with the given method.
        pub fn requests_with(&self, method: &str) -> Vec<Recorded> {
            self.requests().into_iter().filter(|r| r.method == method).collect()
        }
    }

    /// Starts a stub on a free local port. `payment_types` is what `GET paymenttypes`
    /// returns; `None` makes that request fail with HTTP 500.
    pub async fn start(payment_types: Option<Value>) -> Stub {
        let requests: Arc<Mutex<Vec<Recorded>>> = Arc::default();
        let log = requests.clone();
        let app = Router::new().fallback(move |method: Method, uri: Uri, body: Bytes| {
            let log = log.clone();
            let payment_types = payment_types.clone();
            async move {
                let path = uri.path_and_query().map(|p| p.to_string()).unwrap_or_default();
                let body = serde_json::from_slice(&body).unwrap_or(Value::Null);
                log.lock().unwrap().push(Recorded { method: method.to_string(), path: path.clone(), body });
                if method == Method::GET && uri.path() == "/paymenttypes" {
                    return match payment_types {
                        Some(types) => (StatusCode::OK, Json(types)),
                        None => (StatusCode::INTERNAL_SERVER_ERROR, Json(json!({"developerMessage": "database unavailable"}))),
                    };
                }
                (StatusCode::OK, Json(json!({"resourceId": 1})))
            }
        });
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Stub { adapter: FineractAdapter::with_base_url(&format!("http://{addr}")), requests }
    }

    /// The text and `isError` flag of a tool result, read from its JSON form.
    pub fn result_text(result: &rmcp::model::CallToolResult) -> (String, bool) {
        let value = serde_json::to_value(result).unwrap();
        let text = value["content"][0]["text"].as_str().unwrap_or_default().to_string();
        (text, value["isError"].as_bool().unwrap_or(false))
    }
}
