/**
 * ModelRegistry — Static registry of known GGUF models with device requirements.
 *
 * Provides model metadata, download URLs, and hardware gating rules
 * for on-device inference using llama.rn.
 */

export interface ModelConfig {
  /** Unique identifier */
  id: string;
  /** Display name */
  name: string;
  /** HuggingFace repo slug */
  hfRepo: string;
  /** GGUF filename on HuggingFace */
  ggufFilename: string;
  /** Direct download URL (constructed from HF repo) */
  downloadUrl: string;
  /** File size in bytes (approximate, for progress tracking) */
  fileSizeBytes: number;
  /** Quantization method */
  quantization: string;
  /** Parameter count string */
  parameterCount: string;
  /** Architecture backbone */
  architecture: string;
  /** Maximum context window supported */
  maxContextLength: number;
  /** Minimum device RAM in GB to load this model */
  minRamGb: number;
  /** Recommended number of GPU layers (0 = CPU only) */
  recommendedGpuLayers: number;
  /** Whether this model supports tool/function calling */
  supportsToolCalling: boolean;
  /** Brief description */
  description: string;
}

/**
 * Known model configurations.
 *
 * The primary target is the Cloudflare Clef-Flash 4-bit GGUF.
 * We also include a smaller fallback model for low-RAM devices.
 */
export const MODEL_REGISTRY: ModelConfig[] = [
  {
    id: 'clef-flash-q4km',
    name: 'Clef-Flash Q4_K_M',
    hfRepo: 'bartowski/clef-flash-GGUF',
    ggufFilename: 'clef-flash-Q4_K_M.gguf',
    downloadUrl:
      'https://huggingface.co/bartowski/clef-flash-GGUF/resolve/main/clef-flash-Q4_K_M.gguf',
    fileSizeBytes: 5_600_000_000, // ~5.6 GB
    quantization: 'Q4_K_M',
    parameterCount: '9B',
    architecture: 'Qwen3.5',
    maxContextLength: 8192,
    minRamGb: 6,
    recommendedGpuLayers: 20,
    supportsToolCalling: true,
    description:
      'Cloudflare Clef-Flash — 9B decision model (Qwen3.5 backbone). ' +
      '4-bit quantized for mobile inference. Designed for structured decision schemas.',
  },
  {
    id: 'clef-flash-q4km-small',
    name: 'Clef-Flash Q4_K_S',
    hfRepo: 'bartowski/clef-flash-GGUF',
    ggufFilename: 'clef-flash-Q4_K_S.gguf',
    downloadUrl:
      'https://huggingface.co/bartowski/clef-flash-GGUF/resolve/main/clef-flash-Q4_K_S.gguf',
    fileSizeBytes: 5_200_000_000, // ~5.2 GB
    quantization: 'Q4_K_S',
    parameterCount: '9B',
    architecture: 'Qwen3.5',
    maxContextLength: 4096,
    minRamGb: 5,
    recommendedGpuLayers: 16,
    supportsToolCalling: true,
    description:
      'Smaller Q4_K_S variant of Clef-Flash for devices with tighter RAM budgets.',
  },
];

/**
 * Look up a model by its ID.
 */
export function getModelById(id: string): ModelConfig | undefined {
  return MODEL_REGISTRY.find(m => m.id === id);
}

/**
 * Get the default model configuration.
 */
export function getDefaultModel(): ModelConfig {
  return MODEL_REGISTRY[0];
}
