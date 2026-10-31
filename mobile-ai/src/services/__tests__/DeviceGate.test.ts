import {describe, it, expect} from '@jest/globals';
import {gateModel, profileDevice, type DeviceProfile} from '../DeviceGate';
import {getDefaultModel, type ModelConfig} from '../../config/ModelRegistry';
import DeviceInfo from 'react-native-device-info';

describe('DeviceGate', () => {
  const mockModel: ModelConfig = {
    ...getDefaultModel(),
    minRamGb: 6,
    fileSizeBytes: 5 * 1024 * 1024 * 1024, // 5 GB
    recommendedGpuLayers: 20,
  };

  const createProfile = (overrides: Partial<DeviceProfile> = {}): DeviceProfile => ({
    totalRamGb: 8,
    availableRamGb: 4,
    freeDiskGb: 32,
    brand: 'Google',
    model: 'Pixel 8',
    osVersion: '14',
    isEmulator: false,
    hasGpuAcceleration: true,
    ...overrides,
  });

  describe('gateModel', () => {
    it('blocks devices that do not meet the minimum RAM floor', () => {
      const underSpecDevice = createProfile({totalRamGb: 4});
      const result = gateModel(mockModel, underSpecDevice);

      expect(result.allowed).toBe(false);
      expect(result.recommendedGpuLayers).toBe(0);
      expect(result.reason).toContain('requires at least 6 GB');
      expect(result.reason).toContain('OOM crash');
    });

    it('blocks devices where runtime headroom falls below OS_HEADROOM_GB (1.5 GB)', () => {
      // 6.2 GB RAM - 5 GB model = 1.2 GB headroom (< 1.5 GB OS headroom)
      const tightRamDevice = createProfile({totalRamGb: 6.2});
      const result = gateModel(mockModel, tightRamDevice);

      expect(result.allowed).toBe(false);
      expect(result.recommendedGpuLayers).toBe(0);
      expect(result.reason).toContain('Minimum headroom is 1.5 GB');
    });

    it('blocks devices with insufficient disk space for the model file', () => {
      const lowDiskDevice = createProfile({freeDiskGb: 3}); // Model needs 5 GB
      const result = gateModel(mockModel, lowDiskDevice);

      expect(result.allowed).toBe(false);
      expect(result.recommendedGpuLayers).toBe(0);
      expect(result.reason).toContain('Insufficient disk space');
    });

    it('allows low free disk when model is already downloaded', () => {
      const lowDiskDevice = createProfile({freeDiskGb: 1}); // Model needs 5 GB, only 1 GB free
      const result = gateModel(mockModel, lowDiskDevice, true);

      expect(result.allowed).toBe(true);
      expect(result.recommendedGpuLayers).toBe(mockModel.recommendedGpuLayers);
      expect(result.reason).toBeUndefined();
    });

    it('allows emulators with warning and disables GPU layers', () => {
      const emulatorDevice = createProfile({isEmulator: true});
      const result = gateModel(mockModel, emulatorDevice);

      expect(result.allowed).toBe(true);
      expect(result.recommendedGpuLayers).toBe(0);
      expect(result.reason).toContain('Running on an emulator');
      expect(result.reason).toContain('GPU acceleration is disabled');
    });

    it('allows qualified real devices with GPU acceleration enabled', () => {
      const qualifiedDevice = createProfile({
        totalRamGb: 12,
        freeDiskGb: 64,
        hasGpuAcceleration: true,
      });
      const result = gateModel(mockModel, qualifiedDevice);

      expect(result.allowed).toBe(true);
      expect(result.recommendedGpuLayers).toBe(mockModel.recommendedGpuLayers);
      expect(result.reason).toBeUndefined();
    });

    it('falls back to 0 GPU layers for qualified devices without GPU acceleration', () => {
      const noGpuDevice = createProfile({
        totalRamGb: 12,
        freeDiskGb: 64,
        hasGpuAcceleration: false,
      });
      const result = gateModel(mockModel, noGpuDevice);

      expect(result.allowed).toBe(true);
      expect(result.recommendedGpuLayers).toBe(0);
    });
  });

  describe('profileDevice', () => {
    it('queries DeviceInfo and converts memory/disk to gigabytes correctly', async () => {
      (DeviceInfo.getTotalMemory as any).mockResolvedValueOnce(8 * 1024 * 1024 * 1024);
      (DeviceInfo.getFreeDiskStorage as any).mockResolvedValueOnce(32 * 1024 * 1024 * 1024);
      (DeviceInfo.getBrand as any).mockResolvedValueOnce('Samsung');
      (DeviceInfo.getModel as any).mockResolvedValueOnce('Galaxy S24');
      (DeviceInfo.getSystemVersion as any).mockResolvedValueOnce('14');
      (DeviceInfo.isEmulator as any).mockResolvedValueOnce(false);

      const profile = await profileDevice();

      expect(profile.totalRamGb).toBeCloseTo(8, 1);
      expect(profile.freeDiskGb).toBeCloseTo(32, 1);
      expect(profile.availableRamGb).toBeCloseTo(4, 1); // 50% heuristic
      expect(profile.brand).toBe('Samsung');
      expect(profile.model).toBe('Galaxy S24');
      expect(profile.isEmulator).toBe(false);
      expect(profile.hasGpuAcceleration).toBe(true);
    });
  });
});
