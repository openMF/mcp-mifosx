# Benchmarks

Performance benchmarks for the Mifos MCP Server (Go) using Go's standard `testing.B` framework.

## What is Benchmarked

These benchmarks cover the **CPU-bound, non-network** components of the MCP server, directly mirroring the [Rust Criterion benchmarks](../../rust/benches/benchmarks.rs):

| Group | Benchmarks | Rust Equivalent |
|---|---|---|
| **Registry** | `RegistryInit`, `GetClientToolDefs`, `GetLoanToolDefs`, `GetAllToolDefs`, `ToolLookupByName` | `DomainRegistry::new`, `get_domain`, `get_all_tools` |
| **Serialization** | `JSONSerializeSmall`, `JSONSerializeLarge`, `JSONDeserializeLarge` | `to_result (small)`, `to_result (large)` |
| **Validation** | `TodayFormatting`, `FormatAmountFloat`, `FormatAmountString`, `ErrorFormatting` | `today()`, `to_err` |
| **URL Building** | `EndpointPathSubstitution` | *(Go-specific hot path)* |

> Network-bound functions (anything calling `FineractClient.DoRequest`) are excluded since they require a live Fineract backend.

## How to Run

```bash
cd go
go test -bench=. -benchmem ./benches/
```

### Run a specific benchmark

```bash
go test -bench=BenchmarkRegistryInit -benchmem ./benches/
go test -bench=BenchmarkJSON -benchmem ./benches/
```

### Save results for comparison

```bash
go test -bench=. -benchmem -count=5 ./benches/ | tee benches/results.txt
```

## Rust vs Go: Which is Better for Local LLMs (Ollama)?

When running MCP servers locally with Ollama, the bottleneck shifts from network I/O to **CPU-bound operations**. Both languages perform exceptionally well, but they have distinct architectural advantages based on our Apple M2 benchmarks.

### Key Benchmark Findings

| Area | Winner | Why it matters for Ollama |
|---|---|---|
| **Context Window Pruning** | **Rust** | **Killer Feature.** Rust's `route_intent` NLP router intelligently prunes the tool schema payload by 60-90% before sending it to Ollama. Go sends the entire flat registry every time. Smaller context windows = faster, smarter inference. |
| **Tool Lookup Speed** | **Go** (*6x faster*) | Go's flat `map[string]` hash probe is incredibly fast (6ns) compared to Rust's domain vector cloning (37ns). |
| **JSON Serialization** | **Parity** | Surprisingly, Go 1.26's `encoding/json` performed at near-parity with Rust's `serde_json` for MCP payloads on the M2 chip. |
| **Cold Start / Init** | **Rust** (*104x faster*) | Rust initializes the registry in ~1µs using zero-copy `&'static str` slices. Go takes ~106µs due to memory allocations and `mcp-go` reflection. (Note: This is a one-time startup cost). |
| **Date Formatting** | **Go** (*3.4x faster*) | Go's lightweight `time.Format` (86ns) beats Rust's timezone-aware `chrono` (294ns), speeding up Fineract payload generation. |
| **Memory Footprint** | **Rust** | Rust's zero-allocation registry ensures a significantly lower memory footprint, which is critical when Ollama is already consuming most of the RAM for model weights. |

### Final Verdict

**Choose Rust if you are RAM-constrained or need maximum Ollama speed.**
Rust's `route_intent` is the decisive advantage. By actively pruning the tool schemas sent to the LLM, it drastically reduces the context window size. This leads to faster token generation, lower VRAM usage, and less LLM hallucination. Combined with a lower memory footprint, Rust is the undisputed choice for production Ollama deployments.

**Choose Go for Cloud LLMs and developer velocity.**
Go is extremely well-suited for Cloud LLMs (like OpenAI GPT-4 or Anthropic Claude 3.5). In cloud environments, network round-trip latency completely swamps the local CPU cost of evaluating JSON schema tokens. Because cloud models have massive context windows (128k+ tokens) and process them in milliseconds via GPU clusters, Rust's pruning advantage becomes negligible. Instead, Go's slightly faster internal execution speeds (6x faster map lookups, 3.4x faster date formatting), instant compilation, and industry-standard networking stack make it the superior choice for cloud-native microservices.

### Live Ollama Proof (llama3.1, Apple M2)

We validated the above findings with a live end-to-end test. The same prompt was sent to Ollama twice — once with Go's full flat registry (82 tools) and once with Rust's pruned registry (13 tools).

| Metric | Go (flat) | Rust (pruned) | Speedup |
|---|---|---|---|
| Tools in context | 82 | 13 | 82% reduction |
| Prompt tokens | 3,634 | 761 | 4.8x fewer |
| Prompt eval (ms) | 122,049.6 | 1,660.8 | **73.5x faster** |
| Total duration (ms) | 149,709.0 | 4,742.8 | **31.6x faster** |
| Tool called | `make_loan_repayment` | `make_loan_repayment` | Same correct answer |

> Go took **~2.5 minutes**. Rust took **~5 seconds**. Both produced the exact same correct tool call.

## Files

| File | Description |
|---|---|
| `benchmarks_test.go` | Go benchmark definitions (13 benchmarks across 4 groups) |
| `results.md` | Latest benchmark results + live Ollama comparison |
