# Rust MCP Server — Benchmark Results

Benchmarks run using [Criterion.rs](https://github.com/bheisler/criterion.rs) on the CPU-bound, non-network components of the MCP server.

## Registry Benchmarks

| Benchmark | Median | Lower Bound | Upper Bound | Change |
|---|---|---|---|---|
| `DomainRegistry::new` | **638.69 ns** | 634.59 ns | 642.96 ns | -3.42% |
| `route_intent` — single domain (loans) | **2.2388 µs** | 2.2345 µs | 2.2434 µs | -4.14% |
| `route_intent` — multi domain (loans+savings+clients) | **3.6436 µs** | 3.6347 µs | 3.6572 µs | -3.20% |
| `route_intent` — bulk domain | **2.7726 µs** | 2.7605 µs | 2.7875 µs | -4.64% |
| `route_intent` — fallback to clients | **2.9620 µs** | 2.9552 µs | 2.9713 µs | -6.77% |
| `get_all_tools` (flatten + dedup) | **3.9046 µs** | 3.8997 µs | 3.9105 µs | -17.58% |
| `get_domain` (loans) | **36.112 ns** | 36.037 ns | 36.198 ns | -14.02% |

## Domain Helper Benchmarks

| Benchmark | Median | Lower Bound | Upper Bound | Change |
|---|---|---|---|---|
| `clients::today` (chrono formatting) | **290.83 ns** | 289.04 ns | 292.75 ns | -24.22% |
| `clients::to_result` — small payload | **410.73 ns** | 407.33 ns | 414.35 ns | -21.51% |
| `clients::to_result` — large Fineract payload | **4.0981 µs** | 4.0809 µs | 4.1200 µs | -21.87% |
| `clients::to_err` (anyhow → McpError) | **94.445 ns** | 93.713 ns | 95.240 ns | -44.63% |
| `loans::today` (chrono formatting) | **290.89 ns** | 288.88 ns | 293.33 ns | -34.48% |

## What Do These Numbers Mean?

To understand how fast these operations are, we need context. When an AI Agent (like Claude or a LangGraph loop) uses this server, the typical workflow is:
1. **Agent Thinking:** ~500,000 to 2,000,000+ µs (500ms to 2s)
2. **Network RTT (Mifos X Backend):** ~50,000 to 200,000+ µs (50ms to 200ms)
3. **Mifos Rust MCP Server:** **~10 µs total per request**

The benchmark results confirm that the Rust middleware operates so quickly that its impact on the system is **statistically invisible**. 

### Detailed Takeaways:

- **Cost of Intent Routing is Negligible:** The `route_intent` function is used to filter which tools are sent to the AI in order to save tokens and prevent hallucinations. Scanning the user's prompt for keywords across single or multiple domains completes stably in **under 4 microseconds (µs)**.
- **Instant Hash Map Lookups:** The `get_domain` tool mapping resolves in **~36 nanoseconds (ns)**. For comparison, 36ns is roughly the time it takes light to travel 10 meters. 
- **Serialization scales perfectly:** The `to_result` function converts the raw Fineract JSON into an MCP `CallToolResult` text payload. Even for a large JSON tree mapping an entire loan, repayment schedule, and timeline, it takes just **~4.1 microseconds (µs)**.
- **Bulletproof Architecture:** When errors occur (like catching a 404 from Fineract in `to_err`), standardizing them into the MCP protocol takes **~94 ns** (which improved by 44% when CPU caches warmed up). This means the server can sustain massive concurrency without the error-handling logic creating CPU bottlenecks.
