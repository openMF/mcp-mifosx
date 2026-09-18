// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

mod adapter;
mod domains;
mod embeddings;
mod registry;
mod server;

use anyhow::{Context, Result};
use dotenvy::dotenv;
use tracing_subscriber::{self, EnvFilter};
use adapter::FineractAdapter;
use server::MifosMcpServer;

#[tokio::main]
async fn main() -> Result<()> {
    let _ = dotenv();

    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive(tracing::Level::INFO.into()))
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    tracing::info!("Starting Mifos Banking Assistant MCP Server (Rust)");

    let adapter = FineractAdapter::new()?;

    // Transport selection:
    //   MCP_TRANSPORT=stdio   (default) — classic stdio for Claude Desktop / local clients
    //   MCP_TRANSPORT=http    — Streamable HTTP (+ SSE) on MCP_HOST:MCP_PORT
    //
    // Also auto-selects HTTP when PORT or MCP_PORT is set (common in containers).
    let transport = resolve_transport();

    match transport.as_str() {
        "http" | "sse" | "streamable-http" | "streamable_http" => {
            run_http(adapter).await
        }
        _ => run_stdio(adapter).await,
    }
}

fn resolve_transport() -> String {
    if let Ok(t) = std::env::var("MCP_TRANSPORT") {
        return t.trim().to_ascii_lowercase();
    }
    // Auto-detect: if a port is configured, prefer HTTP (Docker / k8s style)
    if std::env::var("MCP_PORT").is_ok() || std::env::var("PORT").is_ok() {
        return "http".into();
    }
    "stdio".into()
}

async fn run_stdio(adapter: FineractAdapter) -> Result<()> {
    use rmcp::{ServiceExt, transport::stdio};

    tracing::info!("Transport: stdio");
    let server = MifosMcpServer::new(adapter);
    let service = server.serve(stdio()).await.inspect_err(|e| {
        tracing::error!("serving error: {:?}", e);
    })?;
    service.waiting().await?;
    Ok(())
}

async fn run_http(adapter: FineractAdapter) -> Result<()> {
    use axum::Router;
    use rmcp::transport::streamable_http_server::{
        session::local::LocalSessionManager,
        tower::{StreamableHttpServerConfig, StreamableHttpService},
    };
    use std::net::SocketAddr;
    use tower_http::cors::{Any, CorsLayer};

    let host = std::env::var("MCP_HOST").unwrap_or_else(|_| "0.0.0.0".into());
    let port: u16 = std::env::var("MCP_PORT")
        .or_else(|_| std::env::var("PORT"))
        .unwrap_or_else(|_| "8080".into())
        .parse()
        .context("Invalid MCP_PORT / PORT")?;

    let path = std::env::var("MCP_PATH").unwrap_or_else(|_| "/mcp".into());
    let bind_addr: SocketAddr = format!("{}:{}", host, port)
        .parse()
        .context("Invalid bind address")?;

    tracing::info!(
        "Transport: Streamable HTTP (SSE-capable) – listening on http://{}{} ",
        bind_addr,
        path
    );

    // 1. Read ALLOWED_HOSTS from environment (comma-separated).
    // Defaults to a safe local list that includes 0.0.0.0 for Docker.
    let allowed_hosts_env = std::env::var("ALLOWED_HOSTS").unwrap_or_else(|_| {
        "localhost,127.0.0.1,::1,0.0.0.0".to_string()
    });

    // 2. Parse into a Vec<String>, trimming whitespace
    let allowed_hosts: Vec<String> = allowed_hosts_env
        .split(',')
        .map(|s| s.trim().to_string())
        .filter(|s| !s.is_empty())
        .collect();

    // 3. Apply configuration (support "*" to disable validation entirely for local dev)
    let config = if allowed_hosts.contains(&"*".to_string()) {
        tracing::warn!("ALLOWED_HOSTS contains '*', disabling Host header validation. Not recommended for public deployments.");
        StreamableHttpServerConfig::default().disable_allowed_hosts()
    } else {
        StreamableHttpServerConfig::default().with_allowed_hosts(allowed_hosts.clone())
    };

    let adapter_for_factory = adapter.clone();
    let mcp_service = StreamableHttpService::new(
        move || Ok(MifosMcpServer::new(adapter_for_factory.clone())),
        LocalSessionManager::default().into(),
        config,
    );

    let cors = CorsLayer::new()
        .allow_origin(Any)
        .allow_methods(Any)
        .allow_headers(Any);

    let app = Router::new()
        .nest_service(&path, mcp_service)
        .route("/health", axum::routing::get(|| async { "ok" }))
        .layer(cors);

    let listener = tokio::net::TcpListener::bind(bind_addr)
        .await
        .with_context(|| format!("Failed to bind {}", bind_addr))?;

    tracing::info!("MCP Streamable HTTP endpoint ready at http://{}{}", bind_addr, path);
    tracing::info!("Health check: http://{}/health", bind_addr);
    tracing::info!("Allowed Hosts: {:?}", allowed_hosts); // <-- Helpful debug log

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await
        .context("HTTP server error")?;

    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };

    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => tracing::info!("Received Ctrl+C, shutting down"),
        _ = terminate => tracing::info!("Received SIGTERM, shutting down"),
    }
}