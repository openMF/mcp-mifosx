# Build stage
FROM rustlang/rust:nightly-slim AS builder
WORKDIR /usr/src/app

# Install OpenSSL/TLS dependencies required for Reqwest
RUN apt-get update && apt-get install -y pkg-config libssl-dev

COPY Cargo.toml Cargo.lock* ./
COPY src ./src

RUN cargo build --release

# Runtime stage
FROM debian:bookworm-slim
WORKDIR /usr/local/bin

# Install local root certificates so the adapter can connect via HTTPS natively
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*

COPY --from=builder /usr/src/app/target/release/mcp-rust-mifosx .

# MCP uses standard I/O streams for JSON-RPC communication
# Ensure the container starts up ready to accept IO via agent connections natively
ENTRYPOINT ["mcp-rust-mifosx"]
