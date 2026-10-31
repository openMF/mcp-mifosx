import {describe, it, expect, beforeEach, jest} from '@jest/globals';
import {ModelStore} from '../ModelStore';
import {getDefaultModel} from '../../config/ModelRegistry';
import RNFS from 'react-native-fs';
import {initLlama} from '@pocketpalai/llama.rn';
import DeviceInfo from 'react-native-device-info';

describe('ModelStore', () => {
  let store: ModelStore;

  const mockContext = {
    completion: jest.fn(),
    stopCompletion: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
    release: jest.fn<() => Promise<void>>().mockResolvedValue(undefined),
  };

  beforeEach(() => {
    jest.clearAllMocks();
    store = new ModelStore();
    (initLlama as any).mockResolvedValue(mockContext);
    (RNFS.exists as any).mockResolvedValue(true);
    (RNFS.mkdir as any).mockResolvedValue(undefined);
    (RNFS.moveFile as any).mockResolvedValue(undefined);
    (RNFS.unlink as any).mockResolvedValue(undefined);

    (DeviceInfo.getTotalMemory as any).mockResolvedValue(12 * 1024 * 1024 * 1024);
    (DeviceInfo.getFreeDiskStorage as any).mockResolvedValue(64 * 1024 * 1024 * 1024);
    (DeviceInfo.getBrand as any).mockResolvedValue('Google');
    (DeviceInfo.getModel as any).mockResolvedValue('Pixel 8 Pro');
    (DeviceInfo.getSystemVersion as any).mockResolvedValue('14');
    (DeviceInfo.isEmulator as any).mockResolvedValue(false);
  });

  describe('initial state', () => {
    it('initializes in idle state without active model or error', () => {
      expect(store.state).toBe('idle');
      expect(store.isReady).toBe(false);
      expect(store.isGenerating).toBe(false);
      expect(store.activeModel).toBeNull();
      expect(store.error).toBeNull();
      expect(store.downloadProgress).toBeNull();
      expect(store.lastResult).toBeNull();
    });
  });

  describe('profileAndGate', () => {
    it('profiles device capabilities and sets gateResult and activeModel', async () => {
      const result = await store.profileAndGate();

      expect(result.allowed).toBe(true);
      expect(store.deviceProfile).toBeDefined();
      expect(store.deviceProfile?.totalRamGb).toBeCloseTo(12, 1);
      expect(store.activeModel?.id).toBe(getDefaultModel().id);
      expect(store.gateResult).toEqual(result);
    });
  });

  describe('downloadModel', () => {
    it('skips download if model file already exists on disk', async () => {
      await store.profileAndGate();
      (RNFS.exists as any).mockResolvedValue(true);

      await store.downloadModel();

      expect(RNFS.downloadFile).not.toHaveBeenCalled();
      expect(store.state).toBe('idle');
    });

    it('downloads model to .part file and moves to destination on success', async () => {
      await store.profileAndGate();
      (RNFS.exists as any).mockResolvedValue(false);

      let capturedStateDuringDownload = '';
      let progressCapturedPercent = 0;

      (RNFS.downloadFile as any).mockImplementation(({progress}: any) => {
        capturedStateDuringDownload = store.state;
        if (progress) {
          progress({bytesWritten: 2_800_000_000, contentLength: 5_600_000_000});
          progressCapturedPercent = store.downloadProgress?.percent ?? 0;
        }
        return {
          jobId: 42,
          promise: Promise.resolve({statusCode: 200}),
        };
      });

      await store.downloadModel();

      expect(capturedStateDuringDownload).toBe('downloading');
      expect(progressCapturedPercent).toBe(50);
      expect(RNFS.mkdir).toHaveBeenCalled();
      expect(RNFS.moveFile).toHaveBeenCalledWith(
        expect.stringMatching(/\.part$/),
        expect.stringMatching(/clef-flash-Q4_K_M\.gguf$/),
      );
      expect(store.state).toBe('idle');
      expect(store.downloadProgress).toBeNull();
    });

    it('cleans up .part file and sets error state when download fails', async () => {
      await store.profileAndGate();
      (RNFS.exists as any).mockResolvedValue(false);

      (RNFS.downloadFile as any).mockReturnValue({
        jobId: 10,
        promise: Promise.resolve({statusCode: 500}),
      });

      await store.downloadModel();

      expect(store.state).toBe('error');
      expect(store.error).toContain('Download failed with status 500');
      expect(RNFS.moveFile).not.toHaveBeenCalled();
    });
  });

  describe('loadModel and unloadModel', () => {
    it('rejects loading when state is not idle', async () => {
      await store.profileAndGate();
      store.state = 'loading';

      await expect(store.loadModel()).rejects.toThrow(
        "Cannot load model in state 'loading'",
      );
    });

    it('rejects loading when model file is not present on disk', async () => {
      await store.profileAndGate();
      (RNFS.exists as any).mockResolvedValue(false);

      await expect(store.loadModel()).rejects.toThrow('Model file not found');
      expect(store.state).toBe('error');
      expect(store.error).toContain('Model file not found');
    });

    it('initializes llama.rn context with hardware-gated parameters', async () => {
      await store.profileAndGate();
      (RNFS.exists as any).mockResolvedValue(true);

      await store.loadModel();

      expect(initLlama).toHaveBeenCalledWith({
        model: expect.stringMatching(/clef-flash-Q4_K_M\.gguf$/),
        n_ctx: getDefaultModel().maxContextLength,
        n_gpu_layers: getDefaultModel().recommendedGpuLayers,
        use_mlock: true,
        use_mmap: true,
        use_progress_callback: true,
      });

      expect(store.state).toBe('ready');
      expect(store.isReady).toBe(true);
      expect(store.loadProgress).toBe(100);
    });

    it('releases native context cleanly on unloadModel', async () => {
      await store.profileAndGate();
      await store.loadModel();

      await store.unloadModel();

      expect(mockContext.release).toHaveBeenCalled();
      expect(store.state).toBe('idle');
      expect(store.isReady).toBe(false);
    });
  });

  describe('generate and stopGeneration', () => {
    it('rejects generation when no model is loaded', async () => {
      await expect(store.generate('Hello')).rejects.toThrow(
        'No model loaded. Call loadModel() first.',
      );
    });

    it('runs completion with streaming token callback and returns generation metrics', async () => {
      await store.profileAndGate();
      await store.loadModel();

      (mockContext.completion as any).mockImplementation(
        async (_opts: any, onToken: (data: {token: string}) => void) => {
          onToken({token: 'Hello'});
          onToken({token: ' world'});
          return {
            text: 'Hello world',
            tokens_predicted: 2,
            timings: {
              predicted_n: 2,
              predicted_per_second: 20.0,
            },
          };
        },
      );

      const result = await store.generate('Test prompt', 128, 0.7);

      expect(mockContext.completion).toHaveBeenCalledWith(
        expect.objectContaining({
          n_predict: 128,
          temperature: 0.7,
        }),
        expect.any(Function),
      );

      expect(result.text).toBe('Hello world');
      expect(result.tokensGenerated).toBe(2);
      expect(result.tokPerSec).toBe(20.0);
      expect(result.stopped).toBe(false);
      expect(store.state).toBe('ready');
      expect(store.lastResult).toEqual(result);
    });

    it('handles stopGeneration by aborting lease and invoking context.stopCompletion', async () => {
      await store.profileAndGate();
      await store.loadModel();

      let resolveCompletion: any;
      (mockContext.completion as any).mockImplementation(() => {
        return new Promise(resolve => {
          resolveCompletion = resolve;
        });
      });

      const genPromise = store.generate('Long prompt');
      expect(store.state).toBe('generating');

      store.stopGeneration();

      expect(mockContext.stopCompletion).toHaveBeenCalled();

      // Resolve the stalled completion with interrupted flag
      resolveCompletion({
        text: 'Partial text',
        tokens_predicted: 1,
        interrupted: true,
      });

      const result = await genPromise;
      expect(result.stopped).toBe(true);
      expect(store.state).toBe('ready');
    });

    it('prevents use-after-free crash by safely stopping in-flight generation on unloadModel', async () => {
      await store.profileAndGate();
      await store.loadModel();

      let resolveCompletion: any;
      (mockContext.completion as any).mockImplementation(() => {
        return new Promise(resolve => {
          resolveCompletion = resolve;
        });
      });

      const genPromise = store.generate('In-flight prompt');
      expect(store.state).toBe('generating');

      // Delay context release to observe intermediate state
      let resolveRelease: any;
      (mockContext.release as any).mockImplementationOnce(() => {
        return new Promise(resolve => {
          resolveRelease = resolve;
        });
      });

      // Trigger unload while generation is still pending
      const unloadPromise = store.unloadModel();
      expect(store.state).toBe('unloading');

      expect(mockContext.stopCompletion).toHaveBeenCalled();

      // Simulate completion finishing after abort signal
      resolveCompletion({
        text: 'Aborted text',
        tokens_predicted: 1,
        interrupted: true,
      });

      await genPromise;

      // Store must remain 'unloading' rather than reverting to 'ready'
      expect(store.state).toBe('unloading');

      resolveRelease();
      await unloadPromise;

      expect(mockContext.release).toHaveBeenCalled();
      expect(store.state).toBe('idle');
    });
  });

  describe('dispose', () => {
    it('cancels active operations, unloads model, and resets all observable state', async () => {
      await store.profileAndGate();
      await store.loadModel();

      await store.dispose();

      expect(mockContext.release).toHaveBeenCalled();
      expect(store.state).toBe('idle');
      expect(store.activeModel).toBeNull();
      expect(store.deviceProfile).toBeNull();
      expect(store.gateResult).toBeNull();
      expect(store.lastResult).toBeNull();
      expect(store.streamingTokens).toBe('');
    });
  });
});
