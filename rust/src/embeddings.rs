// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

//! Semantic embedding engine for intent routing.
//!
//! Embeds user queries and compares them against pre-computed domain
//! description vectors via cosine similarity. This lets the MCP server
//! prune irrelevant tool schemas before sending them to a local LLM.
//!
//! ## Providers
//!
//! Controlled by `MIFOS_EMBEDDING_PROVIDER`:
//!
//! | Value     | Behaviour                                                                 |
//! |-----------|---------------------------------------------------------------------------|
//! | `ollama`  | Call Ollama `/api/embeddings` (recommended for Docker / local setups)     |
//! | `openai`  | Call any OpenAI-compatible `/v1/embeddings` endpoint                      |
//! | `local`   | Download & run `all-MiniLM-L6-v2` with Candle (original behaviour)        |
//!
//! If the chosen provider fails to initialise the server continues with
//! keyword-only routing. Set `MIFOS_DISABLE_SEMANTIC_ROUTER=1` to skip
//! entirely.

use anyhow::{Context, Result, anyhow};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use tokio::sync::RwLock;

// ---------------------------------------------------------------------------
// Domain reference texts
// ---------------------------------------------------------------------------

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

const DEFAULT_LOCAL_MODEL_ID: &str = "sentence-transformers/all-MiniLM-L6-v2";

// ---------------------------------------------------------------------------
// Provider configuration
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum EmbeddingProvider {
    /// Remote Ollama `/api/embeddings`
    Ollama,
    /// Remote OpenAI-compatible `/v1/embeddings`
    OpenAi,
    /// Local Candle inference (downloads from Hugging Face unless a path is given)
    Local,
}

impl EmbeddingProvider {
    fn from_env() -> Self {
        match std::env::var("MIFOS_EMBEDDING_PROVIDER")
            .unwrap_or_default()
            .trim()
            .to_ascii_lowercase()
            .as_str()
        {
            "ollama" => EmbeddingProvider::Ollama,
            "openai" | "openai-compatible" | "vllm" | "lmstudio" => EmbeddingProvider::OpenAi,
            "local" | "candle" | "hf" | "huggingface" => EmbeddingProvider::Local,
            // Sensible default for containerised deployments: prefer Ollama when no
            // explicit choice is made and a base URL is present, otherwise local.
            "" => {
                if std::env::var("MIFOS_EMBEDDING_BASE_URL").is_ok()
                    || std::env::var("OLLAMA_HOST").is_ok()
                {
                    EmbeddingProvider::Ollama
                } else {
                    EmbeddingProvider::Local
                }
            }
            other => {
                tracing::warn!(
                    "Unknown MIFOS_EMBEDDING_PROVIDER='{}', falling back to local",
                    other
                );
                EmbeddingProvider::Local
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Router state machine
// ---------------------------------------------------------------------------

enum RouterState {
    Loading,
    Ready(RouterInner),
    Failed(String),
    Disabled,
}

/// Holds either a remote HTTP client or a local Candle model.
struct RouterInner {
    backend: EmbeddingBackend,
    domain_embeddings: HashMap<&'static str, Vec<f32>>,
}

enum EmbeddingBackend {
    Remote(RemoteEmbedder),
    #[allow(dead_code)] // kept for the local path
    Local(LocalEmbedder),
}

// ---------------------------------------------------------------------------
// Public handle
// ---------------------------------------------------------------------------

#[derive(Clone)]
pub struct SemanticRouter {
    state: Arc<RwLock<RouterState>>,
}

impl std::fmt::Debug for SemanticRouter {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SemanticRouter").finish()
    }
}

impl SemanticRouter {
    /// Create a new semantic router and start loading in the background.
    pub fn new() -> Self {
        let state = Arc::new(RwLock::new(RouterState::Loading));
        let state_clone = state.clone();

        if is_disabled() {
            tracing::info!(
                "Semantic router disabled via MIFOS_DISABLE_SEMANTIC_ROUTER. \
                 Keyword-only intent routing will be used."
            );
            tokio::spawn(async move {
                let mut lock = state_clone.write().await;
                *lock = RouterState::Disabled;
            });
            return Self { state };
        }

        tokio::spawn(async move {
            match Self::load_internal().await {
                Ok(inner) => {
                    let mut lock = state_clone.write().await;
                    *lock = RouterState::Ready(inner);
                    tracing::info!("Semantic router background loading complete.");
                }
                Err(e) => {
                    let mut lock = state_clone.write().await;
                    let msg = format_load_error(&e);
                    tracing::warn!("{}", msg);
                    *lock = RouterState::Failed(e.to_string());
                }
            }
        });

        Self { state }
    }

    async fn load_internal() -> Result<RouterInner> {
        let provider = EmbeddingProvider::from_env();
        tracing::info!("Semantic router provider: {:?}", provider);

        let backend = match provider {
            EmbeddingProvider::Ollama => {
                let embedder = RemoteEmbedder::from_env_ollama()?;
                EmbeddingBackend::Remote(embedder)
            }
            EmbeddingProvider::OpenAi => {
                let embedder = RemoteEmbedder::from_env_openai()?;
                EmbeddingBackend::Remote(embedder)
            }
            EmbeddingProvider::Local => {
                let embedder = LocalEmbedder::load().await?;
                EmbeddingBackend::Local(embedder)
            }
        };

        // Pre-compute domain vectors
        let mut domain_embeddings = HashMap::new();
        for (name, description) in DOMAIN_DESCRIPTIONS {
            let vec = backend.embed(description).await
                .with_context(|| format!("Failed to embed domain description '{}'", name))?;
            domain_embeddings.insert(*name, vec);
        }

        Ok(RouterInner {
            backend,
            domain_embeddings,
        })
    }

    /// Route a query. Returns empty list while loading / on failure / when disabled.
    pub async fn route(&self, query: &str, threshold: f32) -> Result<Vec<(&'static str, f32)>> {
        let lock = self.state.read().await;
        let inner = match &*lock {
            RouterState::Ready(inner) => inner,
            _ => return Ok(vec![]),
        };

        let query_vec = inner.backend.embed(query).await?;
        let mut scores = Vec::new();

        for (&domain, domain_vec) in &inner.domain_embeddings {
            let score = cosine_similarity(&query_vec, domain_vec);
            if score >= threshold {
                scores.push((domain, score));
            }
        }

        scores.sort_by(|a, b| b.1.partial_cmp(&a.1).unwrap_or(std::cmp::Ordering::Equal));
        Ok(scores)
    }
}

// ---------------------------------------------------------------------------
// Remote embedder (Ollama + OpenAI-compatible)
// ---------------------------------------------------------------------------

struct RemoteEmbedder {
    client: reqwest::Client,
    /// Full URL that accepts the embedding request
    url: String,
    model: String,
    api_key: Option<String>,
    /// `true` → Ollama native shape, `false` → OpenAI shape
    is_ollama: bool,
}

impl RemoteEmbedder {
    fn from_env_ollama() -> Result<Self> {
        // OLLAMA_HOST is the conventional env var used by the Ollama CLI
        let base = std::env::var("MIFOS_EMBEDDING_BASE_URL")
            .or_else(|_| std::env::var("OLLAMA_HOST"))
            .unwrap_or_else(|_| "http://127.0.0.1:11434".into());

        let base = base.trim_end_matches('/');
        let url = format!("{}/api/embeddings", base);

        let model = std::env::var("MIFOS_EMBEDDING_MODEL")
            .unwrap_or_else(|_| "all-minilm".into());

        tracing::info!(
            "Using Ollama embeddings – url={} model={}",
            url, model
        );

        Ok(Self {
            client: reqwest::Client::new(),
            url,
            model,
            api_key: None,
            is_ollama: true,
        })
    }

    fn from_env_openai() -> Result<Self> {
        let base = std::env::var("MIFOS_EMBEDDING_BASE_URL")
            .context(
                "MIFOS_EMBEDDING_BASE_URL is required when MIFOS_EMBEDDING_PROVIDER=openai \
                 (e.g. http://localhost:1234/v1 or https://api.openai.com/v1)"
            )?;
        let base = base.trim_end_matches('/');
        // Accept either a bare host or a host that already ends with /v1
        let url = if base.ends_with("/v1") {
            format!("{}/embeddings", base)
        } else {
            format!("{}/v1/embeddings", base)
        };

        let model = std::env::var("MIFOS_EMBEDDING_MODEL")
            .unwrap_or_else(|_| "text-embedding-3-small".into());

        let api_key = std::env::var("MIFOS_EMBEDDING_API_KEY")
            .or_else(|_| std::env::var("OPENAI_API_KEY"))
            .ok();

        tracing::info!(
            "Using OpenAI-compatible embeddings – url={} model={}",
            url, model
        );

        Ok(Self {
            client: reqwest::Client::new(),
            url,
            model,
            api_key,
            is_ollama: false,
        })
    }

    async fn embed(&self, text: &str) -> Result<Vec<f32>> {
        if self.is_ollama {
            self.embed_ollama(text).await
        } else {
            self.embed_openai(text).await
        }
    }

    async fn embed_ollama(&self, text: &str) -> Result<Vec<f32>> {
        #[derive(Serialize)]
        struct Req<'a> {
            model: &'a str,
            prompt: &'a str,
        }
        #[derive(Deserialize)]
        struct Resp {
            embedding: Vec<f32>,
        }

        let resp = self
            .client
            .post(&self.url)
            .json(&Req {
                model: &self.model,
                prompt: text,
            })
            .send()
            .await
            .with_context(|| format!("Ollama embeddings request failed ({})", self.url))?
            .error_for_status()
            .with_context(|| {
                format!(
                    "Ollama returned an error. Is the model '{}' pulled? \
                     Run: ollama pull {}",
                    self.model, self.model
                )
            })?
            .json::<Resp>()
            .await
            .context("Failed to parse Ollama embeddings response")?;

        Ok(normalize(&resp.embedding))
    }

    async fn embed_openai(&self, text: &str) -> Result<Vec<f32>> {
        #[derive(Serialize)]
        struct Req<'a> {
            model: &'a str,
            input: &'a str,
        }
        #[derive(Deserialize)]
        struct DataItem {
            embedding: Vec<f32>,
        }
        #[derive(Deserialize)]
        struct Resp {
            data: Vec<DataItem>,
        }

        let mut req = self.client.post(&self.url).json(&Req {
            model: &self.model,
            input: text,
        });
        if let Some(key) = &self.api_key {
            req = req.bearer_auth(key);
        }

        let resp = req
            .send()
            .await
            .with_context(|| format!("OpenAI-compatible embeddings request failed ({})", self.url))?
            .error_for_status()
            .context("Embeddings endpoint returned an error status")?
            .json::<Resp>()
            .await
            .context("Failed to parse OpenAI-compatible embeddings response")?;

        let embedding = resp
            .data
            .into_iter()
            .next()
            .map(|d| d.embedding)
            .ok_or_else(|| anyhow!("Empty embedding response"))?;

        Ok(normalize(&embedding))
    }
}

// ---------------------------------------------------------------------------
// Local Candle embedder (kept for offline / no-Ollama environments)
// ---------------------------------------------------------------------------

struct LocalEmbedder {
    model: candle_transformers::models::bert::BertModel,
    tokenizer: tokenizers::Tokenizer,
    device: candle_core::Device,
}

impl LocalEmbedder {
    async fn load() -> Result<Self> {
        use candle_core::{DType, Device};
        use candle_nn::VarBuilder;
        use candle_transformers::models::bert::{BertModel, Config as BertConfig};
        use hf_hub::{api::tokio::Api, Repo, RepoType};
        use tokenizers::Tokenizer;

        let device = Device::Cpu;

        // Prefer explicit local directory
        if let Ok(local_path) = std::env::var("MIFOS_EMBEDDING_MODEL_PATH") {
            let path = PathBuf::from(local_path.trim());
            if path.is_dir() {
                tracing::info!("Loading local Candle model from {}", path.display());
                return Self::from_dir(&path, &device);
            }
            tracing::warn!(
                "MIFOS_EMBEDDING_MODEL_PATH='{}' is not a directory; falling back to HF download",
                path.display()
            );
        }

        tracing::info!(
            "Downloading local model '{}' from Hugging Face…",
            DEFAULT_LOCAL_MODEL_ID
        );

        let api = Api::new().context("Failed to initialise Hugging Face Hub API")?;
        let repo = api.repo(Repo::new(
            DEFAULT_LOCAL_MODEL_ID.to_string(),
            RepoType::Model,
        ));

        let config_path = repo.get("config.json").await.context("Failed to download config.json")?;
        let tokenizer_path = repo.get("tokenizer.json").await.context("Failed to download tokenizer.json")?;
        let weights_path = repo.get("model.safetensors").await.context("Failed to download model.safetensors")?;

        Self::from_files(&config_path, &tokenizer_path, &weights_path, &device)
    }

    fn from_dir(dir: &Path, device: &candle_core::Device) -> Result<Self> {
        let config_path = dir.join("config.json");
        let tokenizer_path = dir.join("tokenizer.json");
        let weights_path = dir.join("model.safetensors");
        for p in [&config_path, &tokenizer_path, &weights_path] {
            if !p.exists() {
                return Err(anyhow!("Missing required file: {}", p.display()));
            }
        }
        Self::from_files(&config_path, &tokenizer_path, &weights_path, device)
    }

    fn from_files(
        config_path: &Path,
        tokenizer_path: &Path,
        weights_path: &Path,
        device: &candle_core::Device,
    ) -> Result<Self> {
        use candle_core::DType;
        use candle_nn::VarBuilder;
        use candle_transformers::models::bert::{BertModel, Config as BertConfig};
        use tokenizers::Tokenizer;

        let config_str = std::fs::read_to_string(config_path)
            .with_context(|| format!("Failed to read {}", config_path.display()))?;
        let config: BertConfig = serde_json::from_str(&config_str)
            .context("Failed to parse BERT config JSON")?;

        let tokenizer = Tokenizer::from_file(tokenizer_path)
            .map_err(|e| anyhow!("Failed to load tokenizer: {}", e))?;

        let vb = unsafe {
            VarBuilder::from_mmaped_safetensors(
                &[weights_path.to_path_buf()],
                DType::F32,
                device,
            )
            .context("Failed to map model weights")?
        };
        let model = BertModel::load(vb, &config)
            .context("Failed to load BertModel")?;

        Ok(Self {
            model,
            tokenizer,
            device: device.clone(),
        })
    }

    fn embed(&self, text: &str) -> Result<Vec<f32>> {
        use candle_core::{DType, Tensor};

        let encoding = self
            .tokenizer
            .encode(text, true)
            .map_err(|e| anyhow!("Tokenization failed: {}", e))?;
        let token_ids = encoding.get_ids().to_vec();
        let attention_mask = encoding.get_attention_mask().to_vec();

        let token_ids_t = Tensor::new(token_ids.as_slice(), &self.device)?.unsqueeze(0)?;
        let attn_t = Tensor::new(attention_mask.as_slice(), &self.device)?.unsqueeze(0)?;
        let token_type_ids = token_ids_t.zeros_like()?;

        let output = self.model.forward(&token_ids_t, &token_type_ids, Some(&attn_t))?;

        // Mean pooling
        let attn_f32 = attn_t.to_dtype(DType::F32)?;
        let mask_exp = attn_f32.unsqueeze(2)?.broadcast_as(output.shape())?;
        let sum_emb = (output * mask_exp)?.sum(1)?;
        let sum_mask = attn_f32.sum(1)?.unsqueeze(1)?.broadcast_as(sum_emb.shape())?;
        let mean = (sum_emb / sum_mask)?;

        // L2 normalise
        let norm = mean.sqr()?.sum_keepdim(1)?.sqrt()?;
        let normalised = mean.broadcast_div(&norm)?;

        let vec: Vec<f32> = normalised.squeeze(0)?.to_vec1()?;
        Ok(vec)
    }
}

// ---------------------------------------------------------------------------
// Backend dispatch
// ---------------------------------------------------------------------------

impl EmbeddingBackend {
    async fn embed(&self, text: &str) -> Result<Vec<f32>> {
        match self {
            EmbeddingBackend::Remote(r) => r.embed(text).await,
            EmbeddingBackend::Local(l) => l.embed(text),
        }
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

fn cosine_similarity(a: &[f32], b: &[f32]) -> f32 {
    if a.len() != b.len() || a.is_empty() {
        return 0.0;
    }
    let mut dot = 0.0f32;
    let mut na = 0.0f32;
    let mut nb = 0.0f32;
    for (x, y) in a.iter().zip(b.iter()) {
        dot += x * y;
        na += x * x;
        nb += y * y;
    }
    let denom = na.sqrt() * nb.sqrt();
    if denom == 0.0 {
        0.0
    } else {
        dot / denom
    }
}

/// L2-normalise a vector (remote providers may already do this, but we
/// normalise again so cosine similarity stays consistent).
fn normalize(v: &[f32]) -> Vec<f32> {
    let norm = v.iter().map(|x| x * x).sum::<f32>().sqrt();
    if norm == 0.0 {
        return v.to_vec();
    }
    v.iter().map(|x| x / norm).collect()
}

fn is_disabled() -> bool {
    match std::env::var("MIFOS_DISABLE_SEMANTIC_ROUTER") {
        Ok(v) => {
            let v = v.trim().to_ascii_lowercase();
            matches!(v.as_str(), "1" | "true" | "yes" | "on")
        }
        Err(_) => false,
    }
}

fn format_load_error(e: &anyhow::Error) -> String {
    let root = e
        .chain()
        .map(|c| c.to_string())
        .collect::<Vec<_>>()
        .join(" → ");

    format!(
        "Semantic router failed to load ({root}). \
         Server continues with keyword-only routing. \
         Fix options: \
         (1) MIFOS_EMBEDDING_PROVIDER=ollama + ollama pull all-minilm, \
         (2) MIFOS_EMBEDDING_PROVIDER=openai + MIFOS_EMBEDDING_BASE_URL=…, \
         (3) MIFOS_EMBEDDING_PROVIDER=local + network/HF_TOKEN, \
         (4) MIFOS_DISABLE_SEMANTIC_ROUTER=1 to silence this warning."
    )
}