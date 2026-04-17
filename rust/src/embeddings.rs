// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//! Semantic embedding engine for intent routing.
//!
//! Uses `all-MiniLM-L6-v2` (CPU-only) to embed user queries and compare them
//! against pre-computed domain description vectors via cosine similarity.
//! This allows the MCP server to intelligently prune irrelevant tool schemas
//! from the context window before sending them to a local LLM (Ollama).

use anyhow::{Context, Result, anyhow};
use candle_core::{DType, Device, Tensor};
use candle_nn::VarBuilder;
use candle_transformers::models::bert::{BertModel, Config as BertConfig};
use hf_hub::{api::tokio::Api, Repo, RepoType};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::RwLock;
use tokenizers::Tokenizer;

/// Bank-staff-centric domain descriptions used to generate reference embeddings.
const DOMAIN_DESCRIPTIONS: &[(&str, &str)] = &[
    ("clients", "Client onboarding, KYC verification, customer activation, identity documents, search by name, mobile number updates, client charges, kyc"),
    ("groups", "Lending groups, group membership, center operations, solidarity groups, village banking, group savings, member enrollment"),
    ("loans", "Loan disbursement, repayment, arrears, overdue, interest waiver, late fee, loan products, principal outstanding, default, recovery"),
    ("collaterals", "Collateral management, security pledges, asset valuation, lien, mortgage, guarantee, pledged assets"),
    ("savings", "Savings account, deposit, withdrawal, interest posting, fixed deposit, term deposit, balance inquiry, account statement"),
    ("staff", "Staff directory, loan officer, branch management, office hierarchy, Relationship Manager, Teller, Cashier"),
    ("accounting", "General ledger, journal entries, debit/credit posting, GL account, trial balance, chart of accounts, reconciliation"),
    ("bulk", "Bulk operations, batch processing, mass activation, parallel disbursement, batch repayments, concurrent operations"),
    ("charges", "Charge definitions, fee templates, penalty configuration, tax setup, service charge, surcharge, Processing Fee"),
];

/// The inner state of a fully loaded Semantic Router.
struct RouterInner {
    model: BertModel,
    tokenizer: Tokenizer,
    domain_embeddings: HashMap<&'static str, Tensor>,
    device: Device,
}

/// The state of the semantic router during its lifestyle.
enum RouterState {
    Loading,
    Ready(RouterInner),
    Failed(String),
}

/// The semantic embedding engine.
/// Refactored to support background loading to avoid blocking server startup.
#[derive(Clone)]
pub struct SemanticRouter {
    state: Arc<RwLock<RouterState>>,
}

impl std::fmt::Debug for SemanticRouter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SemanticRouter").finish() // Simplified debug
    }
}

impl SemanticRouter {
    /// Create a new semantic router handle and start model loading in the background.
    /// This returns immediately and does not block.
    pub fn new() -> Self {
        let state = Arc::new(RwLock::new(RouterState::Loading));
        let state_clone = state.clone();

        tokio::spawn(async move {
            match Self::load_internal().await {
                Ok(inner) => {
                    let mut lock = state_clone.write().await;
                    *lock = RouterState::Ready(inner);
                    tracing::info!("Semantic router background loading complete.");
                }
                Err(e) => {
                    let mut lock = state_clone.write().await;
                    let err_msg = e.to_string();
                    tracing::error!("Semantic router failed to load: {}", err_msg);
                    *lock = RouterState::Failed(err_msg);
                }
            }
        });

        Self { state }
    }

    /// Internal async loader for the model and embeddings.
    async fn load_internal() -> Result<RouterInner> {
        let device = Device::Cpu;

        let api = Api::new().context("Failed to initialize HuggingFace Hub API")?;
        let repo = api.repo(Repo::new(
            "sentence-transformers/all-MiniLM-L6-v2".to_string(),
            RepoType::Model,
        ));

        let config_path = repo.get("config.json").await.context("Failed to download config.json")?;
        let tokenizer_path = repo.get("tokenizer.json").await.context("Failed to download tokenizer.json")?;
        let weights_path = repo.get("model.safetensors").await.context("Failed to download model.safetensors")?;

        let config_str = std::fs::read_to_string(&config_path).context("Failed to read config file")?;
        let config: BertConfig = serde_json::from_str(&config_str).context("Failed to parse config JSON")?;

        let tokenizer = Tokenizer::from_file(&tokenizer_path).map_err(|e| anyhow!("{}", e))?;

        let vb = unsafe {
            VarBuilder::from_mmaped_safetensors(&[weights_path], DType::F32, &device).context("Failed to map model weights")?
        };
        let model = BertModel::load(vb, &config).context("Failed to load BertModel into memory")?;

        let mut domain_embeddings = HashMap::new();
        for (domain_name, description) in DOMAIN_DESCRIPTIONS {
            let embedding = Self::embed_text_internal(&model, &tokenizer, &device, description)?;
            domain_embeddings.insert(*domain_name, embedding);
        }

        Ok(RouterInner {
            model,
            tokenizer,
            domain_embeddings,
            device,
        })
    }

    /// Embed a text string into a 384-dimensional normalized vector.
    fn embed_text_internal(
        model: &BertModel,
        tokenizer: &Tokenizer,
        device: &Device,
        text: &str,
    ) -> Result<Tensor> {
        let encoding = tokenizer.encode(text, true).map_err(|e| anyhow!("Tokenization failed: {}", e))?;
        let token_ids = encoding.get_ids().to_vec();
        let attention_mask = encoding.get_attention_mask().to_vec();

        let token_ids_tensor = Tensor::new(token_ids.as_slice(), device)?.unsqueeze(0)?;
        let attention_mask_tensor = Tensor::new(attention_mask.as_slice(), device)?.unsqueeze(0)?;
        let token_type_ids = token_ids_tensor.zeros_like()?;

        let output = model.forward(&token_ids_tensor, &token_type_ids, Some(&attention_mask_tensor))?;

        let attention_mask_f32 = attention_mask_tensor.to_dtype(DType::F32)?;
        let mask_expanded = attention_mask_f32.unsqueeze(2)?.broadcast_as(output.shape())?;
        let sum_embeddings = (output * mask_expanded)?.sum(1)?;
        let sum_mask = attention_mask_f32.sum(1)?.unsqueeze(1)?.broadcast_as(sum_embeddings.shape())?;
        let mean_pooled = (sum_embeddings / sum_mask)?;

        let norm = mean_pooled.sqr()?.sum_keepdim(1)?.sqrt()?;
        let normalized = mean_pooled.broadcast_div(&norm)?;

        Ok(normalized)
    }

    /// Route a user query using semantic similarity. 
    /// Non-blocking: returns empty results if the model is still loading.
    pub async fn route(&self, query: &str, threshold: f32) -> Result<Vec<(&'static str, f32)>> {
        let lock = self.state.read().await;
        
        let inner = match &*lock {
            RouterState::Ready(inner) => inner,
            _ => return Ok(vec![]), // Fallback to keyword-only while loading or if failed
        };

        let query_embedding = Self::embed_text_internal(&inner.model, &inner.tokenizer, &inner.device, query)?;

        let mut scores = Vec::new();
        for (&domain, domain_emb) in &inner.domain_embeddings {
            let score = (query_embedding.clone() * domain_emb.clone())?
                .sum_all()?
                .to_scalar::<f32>()?;
            
            if score >= threshold {
                scores.push((domain, score));
            }
        }

        scores.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        Ok(scores)
    }
}
