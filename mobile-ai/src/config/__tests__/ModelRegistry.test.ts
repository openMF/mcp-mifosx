import {describe, it, expect} from '@jest/globals';
import {
  MODEL_REGISTRY,
  getModelById,
  getDefaultModel,
} from '../ModelRegistry';

describe('ModelRegistry', () => {
  it('defines valid registered models', () => {
    expect(MODEL_REGISTRY.length).toBeGreaterThanOrEqual(2);

    for (const model of MODEL_REGISTRY) {
      expect(model.id).toBeTruthy();
      expect(model.name).toBeTruthy();
      expect(model.hfRepo).toBeTruthy();
      expect(model.ggufFilename).toMatch(/\.gguf$/);
      expect(model.downloadUrl).toBe(
        `https://huggingface.co/${model.hfRepo}/resolve/main/${model.ggufFilename}`,
      );
      expect(model.fileSizeBytes).toBeGreaterThan(0);
      expect(model.minRamGb).toBeGreaterThan(0);
      expect(model.maxContextLength).toBeGreaterThan(0);
      expect(model.recommendedGpuLayers).toBeGreaterThanOrEqual(0);
      expect(typeof model.supportsToolCalling).toBe('boolean');
    }
  });

  it('ensures all registered model IDs are unique', () => {
    const ids = MODEL_REGISTRY.map(m => m.id);
    const uniqueIds = new Set(ids);
    expect(uniqueIds.size).toBe(ids.length);
  });

  it('returns model by ID when present', () => {
    const model = getModelById('clef-flash-q4km');
    expect(model).toBeDefined();
    expect(model?.name).toBe('Clef-Flash Q4_K_M');
    expect(model?.quantization).toBe('Q4_K_M');
  });

  it('returns undefined when querying unknown model ID', () => {
    expect(getModelById('non-existent-model')).toBeUndefined();
  });

  it('returns primary default model', () => {
    const defaultModel = getDefaultModel();
    expect(defaultModel).toBeDefined();
    expect(defaultModel.id).toBe('clef-flash-q4km');
    expect(defaultModel.architecture).toBe('Qwen3.5');
  });
});
