# Benchmarks

Performance benchmarks for the Mifos MCP Server (Rust) using [Criterion.rs](https://github.com/bheisler/criterion.rs).

## What is Benchmarked

These benchmarks cover the **CPU-bound, non-network** components of the MCP server:

- **Registry Benchmarks** — `DomainRegistry` construction, intent routing (`route_intent`), tool lookup (`get_domain`), and full tool flattening (`get_all_tools`).
- **Domain Helper Benchmarks** — Date formatting (`today()`), MCP result serialization (`to_result`), and error conversion (`to_err`).

> Network-bound functions (anything calling `FineractAdapter`) are excluded since they require a live Fineract backend.

## How to Run

```bash
cd rust
cargo bench
```

### Run a specific group

```bash
cargo bench --bench benchmarks -- registry_benches
cargo bench --bench benchmarks -- domain_helper_benches
```

### View HTML reports

After running, Criterion generates interactive HTML reports:

```bash
open target/criterion/report/index.html
```

## Results

See [results.md](results.md) for the latest benchmark data.

## Files

| File | Description |
|---|---|
| `benchmarks.rs` | Criterion benchmark definitions (12 benchmarks across 2 groups) |
| `results.md` | Latest benchmark results table |
