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

## Live Ollama Evaluation

To measure the real-world impact of `route_intent` on local LLM inference, we ran a local evaluation script that sends the same prompt to Ollama twice — once with Go's full 82-tool flat registry, and once with Rust's 13-tool pruned registry. On an Apple M2 with `llama3.1`:

| Metric | Go (flat) | Rust (pruned) | Speedup |
|---|---|---|---|
| Tools in context | 82 | 13 | 82% reduction |
| Prompt tokens | 3,634 | 761 | 4.8x fewer |
| Prompt eval (ms) | 122,049.6 | 1,660.8 | **73.5x faster** |
| Total duration (ms) | 149,709.0 | 4,742.8 | **31.6x faster** |
| Tool called | `make_loan_repayment` | `make_loan_repayment` | Same correct answer |

> Rust's `route_intent` context pruning costs **2.6 microseconds** but saves the local LLM **~2.4 minutes** of processing time per request.

## Files

| File | Description |
|---|---|
| `benchmarks.rs` | Criterion benchmark definitions (12 benchmarks across 2 groups) |
| `results.md` | Latest benchmark results + live Ollama comparison |
