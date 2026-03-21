// Copyright since 2025 Mifos Initiative
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

mod adapter;
mod domains;
mod registry;
mod server;

use anyhow::Result;
use dotenvy::dotenv;
use rmcp::{ServiceExt, transport::stdio};
use tracing_subscriber::{self, EnvFilter};
use adapter::FineractAdapter;
use server::MifosMcpServer;

#[tokio::main]
async fn main() -> Result<()> {
    // Load environment variables
    let _ = dotenv();

    // Initialize tracing
    tracing_subscriber::fmt()
        .with_env_filter(EnvFilter::from_default_env().add_directive(tracing::Level::INFO.into()))
        .with_writer(std::io::stderr)
        .with_ansi(false)
        .init();

    tracing::info!("Starting Mifos Banking Assistant MCP Server (Rust)");

    // Initialize the Fineract Adapter
    let adapter = FineractAdapter::new()?;

    // Create the server with the tool router
    tracing::info!("Starting Mifos MCP server...");
    let server = MifosMcpServer::new(adapter);
    let service = server.serve(stdio()).await.inspect_err(|e| {
        tracing::error!("serving error: {:?}", e);
    })?;

    service.waiting().await?;
    Ok(())
}
