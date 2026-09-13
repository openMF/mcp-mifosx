FROM rust:1-slim-bookworm AS builder
WORKDIR /usr/src/app

RUN apt-get update && \
    apt-get install -y --no-install-recommends \
        pkg-config libssl-dev g++ cmake make && \
    rm -rf /var/lib/apt/lists/*

COPY Cargo.toml Cargo.lock ./

RUN mkdir -p src benches && \
    echo "fn main() {}" > src/main.rs && \
    echo "fn main() {}" > benches/benchmarks.rs && \
    cargo build --release && \
    rm -rf src benches

COPY src ./src

RUN touch src/main.rs && \
    mkdir -p benches && echo "fn main() {}" > benches/benchmarks.rs && \
    cargo build --release

FROM debian:bookworm-slim

LABEL org.opencontainers.image.title="mcp-rust-mifosx" \
      org.opencontainers.image.description="High-performance Rust MCP server for Apache Fineract / Mifos X — 89 typed tools over stdio transport" \
      org.opencontainers.image.source="https://github.com/openMF/mcp-mifosx" \
      org.opencontainers.image.licenses="MPL-2.0" \
      org.opencontainers.image.vendor="Mifos Initiative"

RUN apt-get update && \
    apt-get install -y --no-install-recommends ca-certificates && \
    rm -rf /var/lib/apt/lists/*

RUN adduser --disabled-password --gecos "" mcpuser

WORKDIR /home/mcpuser

COPY --from=builder /usr/src/app/target/release/mcp-rust-mifosx .
RUN chown mcpuser:mcpuser mcp-rust-mifosx

USER mcpuser

ENTRYPOINT ["./mcp-rust-mifosx"]
