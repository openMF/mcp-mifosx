/**
 * DeviceGate — Runtime device capability checks and safety guardrails.
 *
 * Prevents OOM crashes by profiling device RAM and blocking
 * model loading on under-spec hardware. Mirrors PocketPal's
 * device-rule gating pattern.
 */

import DeviceInfo from 'react-native-device-info';
import {Platform} from 'react-native';
import type {ModelConfig} from '../config/ModelRegistry';

export interface DeviceProfile {
  /** Total device RAM in GB */
  totalRamGb: number;
  /** Available (free) RAM in GB at check time */
  availableRamGb: number;
  /** Free disk storage in GB */
  freeDiskGb: number;
  /** Device brand (e.g., "Google", "Samsung") */
  brand: string;
  /** Device model (e.g., "Pixel 8", "SM-S911B") */
  model: string;
  /** OS version string */
  osVersion: string;
  /** Whether the device is an emulator */
  isEmulator: boolean;
  /** Whether GPU acceleration (OpenCL/Metal) is likely available */
  hasGpuAcceleration: boolean;
}

/**
 * Minimum free RAM headroom (in GB) to leave for the OS and other apps.
 */
const OS_HEADROOM_GB = 1.5;

/**
 * Profile the current device.
 */
export async function profileDevice(): Promise<DeviceProfile> {
  const [totalRam, freeDiskStorage, brand, model, osVersion, isEmulator] =
    await Promise.all([
      DeviceInfo.getTotalMemory(),
      DeviceInfo.getFreeDiskStorage(),
      DeviceInfo.getBrand(),
      DeviceInfo.getModel(),
      DeviceInfo.getSystemVersion(),
      DeviceInfo.isEmulator(),
    ]);

  const totalRamGb = totalRam / (1024 * 1024 * 1024);
  const freeDiskGb = freeDiskStorage / (1024 * 1024 * 1024);

  // Estimate available RAM — on Android we can't get free RAM directly
  // from react-native-device-info, so we use a conservative heuristic:
  // assume ~50% of total RAM is available on a fresh app launch.
  const availableRamGb = totalRamGb * 0.5;

  // GPU acceleration heuristic:
  // - iOS: Metal is available on all supported devices
  // - Android: OpenCL is available on Qualcomm Adreno 600+ series
  //   (rough proxy: devices with >= 6GB RAM from major OEMs)
  const hasGpuAcceleration =
    Platform.OS === 'ios' || (totalRamGb >= 6 && !isEmulator);

  return {
    totalRamGb,
    availableRamGb,
    freeDiskGb,
    brand,
    model,
    osVersion,
    isEmulator,
    hasGpuAcceleration,
  };
}

/**
 * Gate result indicating whether a model can be loaded on this device.
 */
export interface GateResult {
  /** Whether the model is allowed to load */
  allowed: boolean;
  /** Human-readable reason if blocked */
  reason?: string;
  /** Recommended GPU layers for this device (may be 0 for CPU-only) */
  recommendedGpuLayers: number;
}

/**
 * Check whether a model can safely run on the profiled device.
 *
 * Rules:
 * 1. Total RAM must meet the model's minimum.
 * 2. Estimated runtime footprint must leave OS_HEADROOM_GB free.
 *    Note: mmap-backed weights are paged on demand (not fully resident),
 *    so we use file size directly rather than a 1.5× multiplier.
 * 3. Free disk storage must be sufficient for the model file.
 * 4. Emulators get a warning but are allowed (for dev/testing).
 */
export function gateModel(
  model: ModelConfig,
  device: DeviceProfile,
  modelAlreadyDownloaded: boolean = false,
): GateResult {
  // Rule 1: Hard RAM floor
  if (device.totalRamGb < model.minRamGb) {
    return {
      allowed: false,
      reason:
        `This device has ${device.totalRamGb.toFixed(1)} GB RAM, ` +
        `but ${model.name} requires at least ${model.minRamGb} GB. ` +
        `Loading this model would likely cause an OOM crash.`,
      recommendedGpuLayers: 0,
    };
  }

  // Rule 2: Runtime memory estimate
  // With use_mmap enabled, model weights are memory-mapped and paged on
  // demand by the OS — they don't occupy resident RAM all at once.
  // The primary resident cost is the KV-cache and activations, which is
  // much smaller than the file size. Use the file size as a conservative
  // upper bound for the memory footprint check.
  const estimatedRuntimeGb = model.fileSizeBytes / (1024 * 1024 * 1024);
  const remainingAfterLoad = device.totalRamGb - estimatedRuntimeGb;

  if (remainingAfterLoad < OS_HEADROOM_GB) {
    return {
      allowed: false,
      reason:
        `${model.name} would use ~${estimatedRuntimeGb.toFixed(1)} GB at runtime, ` +
        `leaving only ${remainingAfterLoad.toFixed(1)} GB for the OS. ` +
        `Minimum headroom is ${OS_HEADROOM_GB} GB.`,
      recommendedGpuLayers: 0,
    };
  }

  // Rule 3: Disk space check (only required if model is not already downloaded)
  if (!modelAlreadyDownloaded) {
    const modelFileSizeGb = model.fileSizeBytes / (1024 * 1024 * 1024);
    if (device.freeDiskGb < modelFileSizeGb) {
      return {
        allowed: false,
        reason:
          `Insufficient disk space: ${device.freeDiskGb.toFixed(1)} GB free, ` +
          `but ${model.name} requires ${modelFileSizeGb.toFixed(1)} GB.`,
        recommendedGpuLayers: 0,
      };
    }
  }

  // Emulator warning (still allowed) — checked before GPU layer calc
  // since emulators don't support GPU acceleration
  if (device.isEmulator) {
    return {
      allowed: true,
      reason:
        'Running on an emulator. Performance will be significantly degraded. ' +
        'GPU acceleration is disabled.',
      recommendedGpuLayers: 0,
    };
  }

  // Determine GPU layers (only for real devices)
  const gpuLayers = device.hasGpuAcceleration
    ? model.recommendedGpuLayers
    : 0;

  return {
    allowed: true,
    recommendedGpuLayers: gpuLayers,
  };
}
