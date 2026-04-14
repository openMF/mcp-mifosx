# Go Benchmark Results — Apple M2 (arm64)

Run: `go test -bench=. -benchmem -count=1 ./benches/`

## Registry Benchmarks

| Benchmark | Latency (ns/op) | Memory (B/op) | Allocations (allocs/op) |
|---|---|---|---|
| RegistryInit | 106,106 | 299,442 | 2,677 |
| GetClientToolDefs | 396.5 | 2,512 | 11 |
| GetLoanToolDefs | 662.8 | 4,704 | 13 |
| GetAllToolDefs | 2,312 | 18,288 | 37 |
| ToolLookupByName | 5.99 | 0 | 0 |

## Serialization Benchmarks

| Benchmark | Latency (ns/op) | Memory (B/op) | Allocations (allocs/op) |
|---|---|---|---|
| JSONSerializeSmall | 364.6 | 272 | 8 |
| JSONSerializeLarge | 4,298 | 3,153 | 71 |
| JSONDeserializeLarge | 3,759 | 2,392 | 64 |

## Validation & Formatting Benchmarks

| Benchmark | Latency (ns/op) | Memory (B/op) | Allocations (allocs/op) |
|---|---|---|---|
| TodayFormatting | 86.51 | 16 | 1 |
| FormatAmountFloat | 72.92 | 16 | 2 |
| FormatAmountString | 98.01 | 16 | 2 |
| ErrorFormatting | 104.5 | 96 | 3 |

## URL Building Benchmarks

| Benchmark | Latency (ns/op) | Memory (B/op) | Allocations (allocs/op) |
|---|---|---|---|
| EndpointPathSubstitution | 63.26 | 48 | 1 |

---

## Live Ollama Benchmark — Rust vs Go Context Window (llama3.1, Apple M2)

This test sends the **exact same prompt** to the same local Ollama model (`llama3.1`) twice — once with Go's full flat registry (82 tools), and once with Rust's pruned registry (13 tools after `route_intent`). The only variable is the number of tool schemas in the context window.

**Prompt:** `"I need to make a loan repayment of $500 for loan ID 42."`

### Go Server (flat registry, no pruning)

| Metric | Value |
|---|---|
| Tools in context | 82 |
| Schema size | 21,469 bytes |
| Prompt tokens | 3,634 |
| Prompt eval (reading) | 122,049.6 ms |
| Generation (writing) | 26,513.2 ms |
| **Total duration** | **149,709.0 ms (~2.5 min)** |
| Tool called | ✅ `make_loan_repayment` |

### Rust Server (route_intent pruned to loans)

| Metric | Value |
|---|---|
| Tools in context | 13 |
| Schema size | 3,814 bytes |
| Prompt tokens | 761 |
| Prompt eval (reading) | 1,660.8 ms |
| Generation (writing) | 2,349.5 ms |
| **Total duration** | **4,742.8 ms (~5 sec)** |
| Tool called | ✅ `make_loan_repayment` |

### Comparison

| Metric | Go (flat) | Rust (pruned) | Speedup |
|---|---|---|---|
| Tools in context | 82 | 13 | 82% reduction |
| Schema size (bytes) | 21,469 | 3,814 | 82% reduction |
| Prompt tokens | 3,634 | 761 | 4.8x fewer |
| Prompt eval (ms) | 122,049.6 | 1,660.8 | **73.5x faster** |
| Total duration (ms) | 149,709.0 | 4,742.8 | **31.6x faster** |

> Both servers produced the **exact same correct tool call** (`make_loan_repayment`), but Rust's context pruning made Ollama respond **31.6x faster** end-to-end. The prompt evaluation alone was **73.5x faster** because the LLM only had to process 761 tokens instead of 3,634.
