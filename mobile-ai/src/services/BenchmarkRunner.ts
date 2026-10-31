/**
 * BenchmarkRunner — On-device inference benchmarking.
 *
 * Measures tokens/sec, time-to-first-token (TTFT), and
 * total generation time for quality assurance.
 */

import {modelStore, type GenerationResult} from '../store/ModelStore';

/** A single benchmark run result */
export interface BenchmarkRun {
  /** Run index (1-based) */
  run: number;
  /** Input prompt used */
  prompt: string;
  /** Generation result with metrics */
  result: GenerationResult;
}

/** Aggregated benchmark summary */
export interface BenchmarkSummary {
  /** Number of runs completed */
  totalRuns: number;
  /** Average tokens per second */
  avgTokPerSec: number;
  /** Average time to first token (ms) */
  avgTtftMs: number;
  /** Average total generation time (ms) */
  avgTotalMs: number;
  /** Average tokens generated per run */
  avgTokensGenerated: number;
  /** Min tokens/sec observed */
  minTokPerSec: number;
  /** Max tokens/sec observed */
  maxTokPerSec: number;
  /** Individual run results */
  runs: BenchmarkRun[];
}

/**
 * Standard benchmark prompts designed to test different
 * aspects of the model's decision capabilities.
 */
const BENCHMARK_PROMPTS = [
  // Simple factual
  'What is a loan amortization schedule?',
  // Decision / structured output
  'Should a client with a credit score of 650 and annual income of $30,000 be approved for a $5,000 personal loan at 12% APR? Provide a structured decision.',
  // Multi-step reasoning
  'A savings account has a principal of $10,000 with 5% annual interest compounded monthly. Calculate the balance after 3 years.',
];

/**
 * Run a benchmark suite against the currently loaded model.
 *
 * @param runs - Number of complete benchmark cycles (default: 3)
 * @param maxTokens - Max tokens per generation (default: 256)
 * @returns BenchmarkSummary with aggregated metrics
 */
export async function runBenchmark(
  runs: number = 3,
  maxTokens: number = 256,
): Promise<BenchmarkSummary> {
  if (!modelStore.isReady) {
    throw new Error('Model must be loaded before benchmarking.');
  }

  if (runs < 1) {
    throw new Error('Benchmark requires at least 1 run.');
  }

  const allRuns: BenchmarkRun[] = [];

  for (let i = 0; i < runs; i++) {
    const prompt = BENCHMARK_PROMPTS[i % BENCHMARK_PROMPTS.length];

    console.log(`[Benchmark] Run ${i + 1}/${runs}: "${prompt.slice(0, 40)}..."`);

    const result = await modelStore.generate(prompt, maxTokens, 0.1); // Low temp for consistency

    allRuns.push({
      run: i + 1,
      prompt,
      result,
    });

    console.log(
      `[Benchmark] Run ${i + 1}: ` +
      `${result.tokPerSec} tok/s, ` +
      `TTFT=${result.ttftMs}ms, ` +
      `total=${result.totalMs}ms, ` +
      `tokens=${result.tokensGenerated}`,
    );
  }

  // Aggregate
  const tokPerSecs = allRuns.map(r => r.result.tokPerSec);
  const summary: BenchmarkSummary = {
    totalRuns: allRuns.length,
    avgTokPerSec: average(tokPerSecs),
    avgTtftMs: average(allRuns.map(r => r.result.ttftMs)),
    avgTotalMs: average(allRuns.map(r => r.result.totalMs)),
    avgTokensGenerated: average(allRuns.map(r => r.result.tokensGenerated)),
    minTokPerSec: Math.min(...tokPerSecs),
    maxTokPerSec: Math.max(...tokPerSecs),
    runs: allRuns,
  };

  console.log(
    `[Benchmark] Summary: ${summary.totalRuns} runs, ` +
    `avg=${summary.avgTokPerSec} tok/s, ` +
    `TTFT=${summary.avgTtftMs}ms, ` +
    `total=${summary.avgTotalMs}ms, ` +
    `range=${summary.minTokPerSec}-${summary.maxTokPerSec} tok/s`,
  );

  return summary;
}

function average(nums: number[]): number {
  if (nums.length === 0) return 0;
  return Math.round((nums.reduce((a, b) => a + b, 0) / nums.length) * 100) / 100;
}
