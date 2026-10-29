import {describe, it, expect, beforeEach, jest} from '@jest/globals';
import {runBenchmark} from '../BenchmarkRunner';
import {modelStore} from '../../store/ModelStore';

jest.mock('../../store/ModelStore', () => {
  return {
    modelStore: {
      isReady: true,
      generate: jest.fn(),
    },
  };
});

describe('BenchmarkRunner', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (modelStore as any).isReady = true;
  });

  it('rejects if modelStore is not ready', async () => {
    (modelStore as any).isReady = false;

    await expect(runBenchmark(3, 256)).rejects.toThrow(
      'Model must be loaded before benchmarking.',
    );
  });

  it('rejects if runs count is less than 1', async () => {
    await expect(runBenchmark(0, 256)).rejects.toThrow(
      'Benchmark requires at least 1 run.',
    );
  });

  it('executes requested number of runs and calculates aggregated metrics accurately', async () => {
    const mockResults = [
      {
        text: 'Output 1',
        tokensGenerated: 100,
        tokPerSec: 20.0,
        ttftMs: 200,
        totalMs: 5000,
        peakMemoryMb: 0,
        stopped: false,
      },
      {
        text: 'Output 2',
        tokensGenerated: 150,
        tokPerSec: 30.0,
        ttftMs: 150,
        totalMs: 5000,
        peakMemoryMb: 0,
        stopped: false,
      },
      {
        text: 'Output 3',
        tokensGenerated: 200,
        tokPerSec: 25.0,
        ttftMs: 250,
        totalMs: 8000,
        peakMemoryMb: 0,
        stopped: false,
      },
    ];

    let callCount = 0;
    (modelStore.generate as any).mockImplementation(() => {
      const res = mockResults[callCount % mockResults.length];
      callCount++;
      return Promise.resolve(res);
    });

    const summary = await runBenchmark(3, 128);

    expect(modelStore.generate).toHaveBeenCalledTimes(3);
    // Check parameters: prompt, maxTokens=128, temperature=0.1
    expect(modelStore.generate).toHaveBeenNthCalledWith(
      1,
      expect.any(String),
      128,
      0.1,
    );

    expect(summary.totalRuns).toBe(3);
    expect(summary.avgTokPerSec).toBe(25.0); // (20 + 30 + 25) / 3 = 25
    expect(summary.minTokPerSec).toBe(20.0);
    expect(summary.maxTokPerSec).toBe(30.0);
    expect(summary.avgTtftMs).toBe(200); // (200 + 150 + 250) / 3 = 200
    expect(summary.avgTotalMs).toBe(6000); // (5000 + 5000 + 8000) / 3 = 6000
    expect(summary.avgTokensGenerated).toBe(150); // (100 + 150 + 200) / 3 = 150
    expect(summary.runs.length).toBe(3);
    expect(summary.runs[0].run).toBe(1);
    expect(summary.runs[0].result).toEqual(mockResults[0]);
  });
});
