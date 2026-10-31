/**
 * ModelStore — MobX store managing the full model lifecycle.
 *
 * Handles model downloading, loading into llama.rn context,
 * generation with streaming tokens, and safe cleanup with
 * the "generation lease" pattern to prevent use-after-free crashes.
 *
 * Architecture mirrors pocketpal-ai's ModelStore:
 * - Single active context at a time
 * - Lease-based generation lifecycle
 * - Abort-before-free safety
 */

import {makeAutoObservable, runInAction} from 'mobx';
import {initLlama, type LlamaContext} from '@pocketpalai/llama.rn';
import RNFS from 'react-native-fs';
import type {ModelConfig} from '../config/ModelRegistry';
import {getDefaultModel} from '../config/ModelRegistry';
import {
  profileDevice,
  gateModel,
  type DeviceProfile,
  type GateResult,
} from '../services/DeviceGate';

/** Possible states of the model lifecycle */
export type ModelState =
  | 'idle'
  | 'downloading'
  | 'loading'
  | 'ready'
  | 'generating'
  | 'unloading'
  | 'error';

/** Download progress info */
export interface DownloadProgress {
  bytesWritten: number;
  contentLength: number;
  percent: number;
}

/** Generation result with benchmarking metrics */
export interface GenerationResult {
  text: string;
  tokensGenerated: number;
  /** Tokens per second */
  tokPerSec: number;
  /** Time to first token in ms */
  ttftMs: number;
  /** Total generation time in ms */
  totalMs: number;
  /** Peak memory during generation (estimated) */
  peakMemoryMb: number;
  /** Whether generation was stopped early */
  stopped: boolean;
}

/**
 * MobX store for model lifecycle management.
 */
export class ModelStore {
  // ── Observable State ───────────────────────────────────────────────
  state: ModelState = 'idle';
  activeModel: ModelConfig | null = null;
  deviceProfile: DeviceProfile | null = null;
  gateResult: GateResult | null = null;
  error: string | null = null;

  downloadProgress: DownloadProgress | null = null;
  loadProgress: number = 0; // 0-100

  lastResult: GenerationResult | null = null;
  streamingTokens: string = '';

  // ── Private State ──────────────────────────────────────────────────
  private context: LlamaContext | null = null;
  private generationLease: AbortController | null = null;
  private generationPromise: Promise<GenerationResult> | null = null;
  private downloadJobId: number | null = null;

  constructor() {
    makeAutoObservable(this, {}, {autoBind: true});
  }

  // ── Computed ───────────────────────────────────────────────────────
  get isReady(): boolean {
    return this.state === 'ready';
  }

  get isGenerating(): boolean {
    return this.state === 'generating';
  }

  get modelFilePath(): string {
    if (!this.activeModel) return '';
    return `${RNFS.DocumentDirectoryPath}/models/${this.activeModel.ggufFilename}`;
  }

  // ── Device Profiling ───────────────────────────────────────────────

  /**
   * Profile the device and gate the given model.
   */
  async profileAndGate(model?: ModelConfig): Promise<GateResult> {
    const target = model ?? getDefaultModel();
    const [profile, exists] = await Promise.all([
      profileDevice(),
      this.checkModelExists(target),
    ]);

    const result = gateModel(target, profile, exists);

    runInAction(() => {
      this.deviceProfile = profile;
      this.gateResult = result;
      this.activeModel = target;
    });

    return result;
  }

  // ── Model Download ─────────────────────────────────────────────────

  /**
   * Check if the model file already exists on disk.
   */
  async checkModelExists(model?: ModelConfig): Promise<boolean> {
    const target = model ?? this.activeModel ?? getDefaultModel();
    const path = `${RNFS.DocumentDirectoryPath}/models/${target.ggufFilename}`;
    return RNFS.exists(path);
  }

  /**
   * Download the model GGUF from HuggingFace to local storage.
   *
   * Downloads to a temporary .part file first, then moves to the
   * final path on success. Cleans up the .part file on failure
   * or cancellation.
   */
  async downloadModel(model?: ModelConfig): Promise<void> {
    const target = model ?? this.activeModel ?? getDefaultModel();

    // Ensure models directory exists
    const modelsDir = `${RNFS.DocumentDirectoryPath}/models`;
    const dirExists = await RNFS.exists(modelsDir);
    if (!dirExists) {
      await RNFS.mkdir(modelsDir);
    }

    const destPath = `${modelsDir}/${target.ggufFilename}`;
    const partPath = `${destPath}.part`;

    // Skip if already downloaded
    const exists = await RNFS.exists(destPath);
    if (exists) {
      console.log(`[ModelStore] Model already downloaded: ${destPath}`);
      runInAction(() => {
        this.activeModel = target;
        this.state = 'idle';
      });
      return;
    }

    runInAction(() => {
      this.state = 'downloading';
      this.activeModel = target;
      this.error = null;
      this.downloadProgress = {bytesWritten: 0, contentLength: target.fileSizeBytes, percent: 0};
    });

    try {
      const download = RNFS.downloadFile({
        fromUrl: target.downloadUrl,
        toFile: partPath,
        progressInterval: 500,
        progress: (res) => {
          runInAction(() => {
            const contentLength = res.contentLength > 0 ? res.contentLength : target.fileSizeBytes;
            this.downloadProgress = {
              bytesWritten: res.bytesWritten,
              contentLength,
              percent: Math.round((res.bytesWritten / contentLength) * 100),
            };
          });
        },
      });

      this.downloadJobId = download.jobId;
      const result = await download.promise;

      if (result.statusCode !== 200) {
        // Clean up failed download
        const partExists = await RNFS.exists(partPath);
        if (partExists) {
          await RNFS.unlink(partPath);
        }
        throw new Error(`Download failed with status ${result.statusCode}`);
      }

      // Move .part to final destination
      await RNFS.moveFile(partPath, destPath);

      runInAction(() => {
        this.state = 'idle';
        this.downloadProgress = null;
        this.downloadJobId = null;
      });

      console.log(`[ModelStore] Download complete: ${destPath}`);
    } catch (err: any) {
      // Clean up partial file on error
      try {
        const partExists = await RNFS.exists(partPath);
        if (partExists) {
          await RNFS.unlink(partPath);
        }
      } catch {
        // Ignore cleanup errors
      }

      // If cancelled via cancelDownload(), state is already 'idle' —
      // don't overwrite it to 'error'.
      runInAction(() => {
        if (this.state === 'downloading') {
          this.state = 'error';
          this.error = `Download failed: ${err.message}`;
        }
        this.downloadProgress = null;
        this.downloadJobId = null;
      });
    }
  }

  /**
   * Cancel an in-progress download.
   */
  cancelDownload(): void {
    if (this.downloadJobId !== null) {
      RNFS.stopDownload(this.downloadJobId);
      runInAction(() => {
        this.state = 'idle';
        this.downloadProgress = null;
        this.downloadJobId = null;
      });
    }
  }

  // ── Model Loading (llama.rn Context) ───────────────────────────────

  /**
   * Load the GGUF model into a llama.rn context.
   *
   * This allocates native memory for the model weights, KV-cache,
   * and tokenizer. The context must be released before loading
   * another model.
   *
   * Rejects immediately unless state is 'idle', and sets state to
   * 'loading' synchronously before any async work to prevent
   * concurrent loadModel calls from invoking initLlama twice.
   */
  async loadModel(): Promise<void> {
    if (this.state !== 'idle') {
      throw new Error(`Cannot load model in state '${this.state}'. Model must be in 'idle' state.`);
    }

    if (!this.activeModel) {
      throw new Error('No active model set. Call profileAndGate() first.');
    }

    // Set loading state synchronously to prevent concurrent calls
    this.state = 'loading';
    this.error = null;
    this.loadProgress = 0;

    // Safety: unload any existing context
    if (this.context) {
      await this.unloadModel();
      // Re-check state after unload
      runInAction(() => {
        this.state = 'loading';
        this.error = null;
        this.loadProgress = 0;
      });
    }

    const modelPath = this.modelFilePath;
    const exists = await RNFS.exists(modelPath);
    if (!exists) {
      runInAction(() => {
        this.state = 'error';
        this.error = `Model file not found at ${modelPath}. Download it first.`;
      });
      throw new Error(`Model file not found at ${modelPath}. Download it first.`);
    }

    try {
      const gpuLayers = this.gateResult?.recommendedGpuLayers ?? 0;

      const ctx = await initLlama({
        model: modelPath,
        n_ctx: this.activeModel.maxContextLength,
        n_gpu_layers: gpuLayers,
        use_mlock: true,
        use_mmap: true,
        // Progress callback for model loading
        use_progress_callback: true,
      });

      runInAction(() => {
        this.context = ctx;
        this.state = 'ready';
        this.loadProgress = 100;
      });

      console.log(
        `[ModelStore] Model loaded: ${this.activeModel.name} ` +
        `(${gpuLayers} GPU layers, ctx=${this.activeModel.maxContextLength})`,
      );
    } catch (err: any) {
      runInAction(() => {
        this.state = 'error';
        this.error = `Failed to load model: ${err.message}`;
        this.loadProgress = 0;
      });
      throw err;
    }
  }

  /**
   * Unload the model context and free native memory.
   *
   * CRITICAL: This implements the "generation lease" pattern.
   * If a generation is in progress, we stop the completion,
   * await the in-flight promise, then release the context.
   * Releasing a llama.rn context while a completion is running
   * causes use-after-free crashes on Android.
   */
  async unloadModel(): Promise<void> {
    if (!this.context) {
      runInAction(() => {
        this.state = 'idle';
      });
      return;
    }

    runInAction(() => {
      this.state = 'unloading';
    });

    // Step 1: Stop any active generation and await its completion
    if (this.generationLease) {
      console.log('[ModelStore] Stopping active generation before unload...');
      this.generationLease.abort();

      // Stop the llama.cpp decode thread
      try {
        await this.context?.stopCompletion();
      } catch {
        // Ignore — completion may have already finished
      }

      // Wait for the in-flight generation promise to settle
      if (this.generationPromise) {
        try {
          await this.generationPromise;
        } catch {
          // Expected — aborted generations may reject
        }
      }

      runInAction(() => {
        this.generationLease = null;
        this.generationPromise = null;
      });
    }

    // Step 2: Release the native context
    try {
      await this.context.release();
      console.log('[ModelStore] Context released.');
    } catch (err: any) {
      console.warn('[ModelStore] Error releasing context:', err.message);
    }

    runInAction(() => {
      this.context = null;
      this.state = 'idle';
      this.loadProgress = 0;
      this.streamingTokens = '';
    });
  }

  // ── Text Generation ────────────────────────────────────────────────

  /**
   * Run a completion against the loaded model.
   *
   * Uses the generation lease pattern: acquires a lease (AbortController)
   * before starting, and the lease MUST be held until the completion
   * finishes or is aborted.
   *
   * @param prompt - The input prompt
   * @param maxTokens - Maximum tokens to generate (default: 512)
   * @param temperature - Sampling temperature (default: 0.7)
   * @param systemPrompt - System prompt (default: banking assistant)
   * @returns GenerationResult with metrics
   */
  async generate(
    prompt: string,
    maxTokens: number = 512,
    temperature: number = 0.7,
    systemPrompt: string = 'You are a helpful banking assistant for Mifos X / Apache Fineract.',
  ): Promise<GenerationResult> {
    if (!this.context) {
      throw new Error('No model loaded. Call loadModel() first.');
    }

    if (this.state !== 'ready') {
      throw new Error(`Cannot generate in state '${this.state}'. Model must be in 'ready' state.`);
    }

    // Acquire generation lease and set state synchronously
    const lease = new AbortController();
    this.generationLease = lease;
    this.state = 'generating';
    this.streamingTokens = '';
    this.lastResult = null;
    this.error = null;

    const startTime = performance.now();
    let firstTokenTime: number | null = null;
    let tokenCount = 0;

    const generationWork = (async (): Promise<GenerationResult> => {
      try {
        const result = await this.context!.completion(
          {
            messages: [
              {role: 'system', content: systemPrompt},
              {role: 'user', content: prompt},
            ],
            n_predict: maxTokens,
            temperature,
            top_p: 0.9,
            top_k: 40,
            stop: ['</s>', '<|endoftext|>', '<|im_end|>'],
          },
          (data) => {
            // Streaming token callback
            if (lease.signal.aborted) return;

            if (firstTokenTime === null) {
              firstTokenTime = performance.now();
            }
            tokenCount++;

            runInAction(() => {
              this.streamingTokens += data.token;
            });
          },
        );

        const endTime = performance.now();
        const totalMs = endTime - startTime;
        const ttftMs = firstTokenTime !== null ? firstTokenTime - startTime : totalMs;
        // Use decode duration (after first token) for fallback tok/s calculation
        const decodeDuration = firstTokenTime !== null ? endTime - firstTokenTime : totalMs;
        const fallbackTokPerSec = firstTokenTime !== null && decodeDuration > 0
          ? (tokenCount / (decodeDuration / 1000))
          : 0;

        const tokensGenerated = result.timings?.predicted_n ?? result.tokens_predicted ?? tokenCount;
        const tokPerSec = result.timings?.predicted_per_second ?? fallbackTokPerSec;

        const genResult: GenerationResult = {
          text: result.text ?? this.streamingTokens,
          tokensGenerated,
          tokPerSec: Math.round(tokPerSec * 100) / 100,
          ttftMs: Math.round(ttftMs),
          totalMs: Math.round(totalMs),
          peakMemoryMb: 0, // Would need native instrumentation
          stopped: lease.signal.aborted || Boolean(result.interrupted),
        };

        runInAction(() => {
          this.lastResult = genResult;
          if (this.state === 'generating') {
            this.state = 'ready';
          }
          this.generationLease = null;
          this.generationPromise = null;
        });

        return genResult;
      } catch (err: any) {
        const endTime = performance.now();

        if (lease.signal.aborted) {
          // Clean abort — not an error
          const genResult: GenerationResult = {
            text: this.streamingTokens,
            tokensGenerated: tokenCount,
            tokPerSec: 0,
            ttftMs: firstTokenTime !== null ? firstTokenTime - startTime : 0,
            totalMs: Math.round(endTime - startTime),
            peakMemoryMb: 0,
            stopped: true,
          };

          runInAction(() => {
            this.lastResult = genResult;
            // Only restore to 'ready' if we're not being unloaded
            if (this.state === 'generating') {
              this.state = 'ready';
            }
            this.generationLease = null;
            this.generationPromise = null;
          });

          return genResult;
        }

        runInAction(() => {
          this.state = 'error';
          this.error = `Generation failed: ${err.message}`;
          this.generationLease = null;
          this.generationPromise = null;
        });

        throw err;
      }
    })();

    this.generationPromise = generationWork;
    return generationWork;
  }

  /**
   * Stop an in-progress generation.
   *
   * This cleanly aborts the llama.cpp decode thread without
   * leaking memory by signalling the generation lease and
   * invoking stopCompletion on the context.
   */
  stopGeneration(): void {
    if (this.generationLease) {
      console.log('[ModelStore] Stopping generation...');
      this.generationLease.abort();

      // Signal llama.cpp to stop the decode thread
      this.context?.stopCompletion().catch(() => {
        // Ignore — completion may have already finished
      });
    }
  }

  // ── Cleanup ────────────────────────────────────────────────────────

  /**
   * Full cleanup — stop generation, unload model, reset all state.
   */
  async dispose(): Promise<void> {
    this.cancelDownload();

    if (this.generationLease) {
      this.stopGeneration();
    }

    await this.unloadModel();

    runInAction(() => {
      this.state = 'idle';
      this.activeModel = null;
      this.deviceProfile = null;
      this.gateResult = null;
      this.error = null;
      this.lastResult = null;
      this.streamingTokens = '';
    });
  }

  /**
   * Delete the downloaded model file from disk.
   */
  async deleteModelFile(model?: ModelConfig): Promise<void> {
    const target = model ?? this.activeModel;
    if (!target) return;

    const path = `${RNFS.DocumentDirectoryPath}/models/${target.ggufFilename}`;
    const exists = await RNFS.exists(path);
    if (exists) {
      await RNFS.unlink(path);
      console.log(`[ModelStore] Deleted model file: ${path}`);
    }
  }
}

/**
 * Singleton instance for the app.
 */
export const modelStore = new ModelStore();
