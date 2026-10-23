# Mifos Mobile AI — On-Device Inference POC

Proof-of-concept Android application that runs **Cloudflare Clef-Flash** (9B, Q4_K_M GGUF) entirely on-device for banking AI workflows, powered by `llama.rn` / `llama.cpp`.

## Architecture

This POC mirrors the [PocketPal AI](https://github.com/a-ghorbani/pocketpal-ai) architecture:

```
┌─────────────────────────────────────────────────────────┐
│                  React Native (UI)                       │
│  App.tsx ←→ MobX ModelStore ←→ BenchmarkRunner          │
├──────────────────────┬──────────────────────────────────┤
│                      │ JSI Bridge                        │
│              @pocketpalai/llama.rn                       │
├──────────────────────┼──────────────────────────────────┤
│                      │ Native C++                        │
│                  llama.cpp                               │
│           (GGUF model loading & inference)               │
├──────────────────────┼──────────────────────────────────┤
│                Hardware Layer                            │
│  CPU (ARM NEON) │ GPU (OpenCL/Adreno) │ NPU (Hexagon)  │
└─────────────────────────────────────────────────────────┘
```

### Key Components

| Component | File | Description |
|---|---|---|
| **ModelRegistry** | `src/config/ModelRegistry.ts` | Static registry of known GGUF models with download URLs, hardware requirements, and capabilities |
| **DeviceGate** | `src/services/DeviceGate.ts` | Runtime device profiling and memory gating — blocks model loading on under-spec devices |
| **ModelStore** | `src/store/ModelStore.ts` | MobX store managing the full lifecycle: download → load → generate → unload |
| **BenchmarkRunner** | `src/services/BenchmarkRunner.ts` | Runs standardized banking prompts and reports tok/s, TTFT, and memory metrics |
| **App** | `App.tsx` | Chat interface with streaming output, controls, and real-time metric display |

---

## Target Model

| Property | Value |
|---|---|
| **Model** | Cloudflare Clef-Flash |
| **Parameters** | 9B |
| **Backbone** | Qwen3.5 |
| **Quantization** | Q4_K_M (GGUF) |
| **File Size** | ~5.6 GB |
| **Min RAM** | 6 GB |
| **Context Length** | 8192 tokens |
| **Source** | [bartowski/clef-flash-GGUF](https://huggingface.co/bartowski/clef-flash-GGUF) |

---

## Device Requirements

| Requirement | Minimum | Recommended |
|---|---|---|
| **Android** | 8.0+ (API 26) | 13+ (API 33) |
| **RAM** | 6 GB | 8+ GB |
| **Storage** | 8 GB free | 12 GB free |
| **GPU** | — | Adreno 700+ (OpenCL) |
| **Tested Devices** | — | Pixel 8, Samsung S23+ |

### Device Gating

The `DeviceGate` module profiles the device at startup and blocks model loading if:
- Total RAM < model's `minRamGb` (hard floor)
- Estimated runtime memory (file size × 1.5) would leave < 1.5 GB for the OS
- Emulators get a warning but are allowed for development

---

## Safety: Generation Lease Pattern

**Critical for Android stability.** Releasing a `llama.rn` context while a completion is running causes **use-after-free crashes**. The `ModelStore` implements a lease-based pattern:

1. Before generation starts, an `AbortController` (the "lease") is acquired
2. The lease is held for the entire duration of the completion
3. To stop generation: `modelStore.stopGeneration()` signals the lease
4. Before unloading: `unloadModel()` **aborts any active generation and waits** for it to settle before releasing the native context

```typescript
// Safe: always stop before unload
modelStore.stopGeneration();  // signals abort
await modelStore.unloadModel(); // waits, then releases
```

---

## Getting Started

### Prerequisites

- Node.js 18+
- React Native CLI
- Android SDK (API 26+)
- NDK r27+ (for llama.cpp native build)
- CMake 3.22+

### Setup

```bash
cd mobile-ai

# Install dependencies
npm install

# Start Metro bundler
npm start

# Run on Android device/emulator
npm run android
```

### First Run

1. The app profiles your device and checks compatibility
2. Tap **Download Model** to fetch the GGUF from HuggingFace (~5.6 GB)
3. Tap **Load Model** to initialize the llama.rn context
4. Enter a banking prompt and tap **Send**
5. Use **Benchmark** to run standardized performance tests

---

## GPU Acceleration

GPU offloading is configured in `android/app/build.gradle`:

```gradle
externalNativeBuild {
    cmake {
        arguments "-DGGML_OPENCL=ON"
        arguments "-DGGML_OPENCL_ADRENO_LARGE_BUFFER=ON"
    }
}
```

The `DeviceGate` automatically determines GPU layer count based on device capabilities. CPU-only fallback is always available.

---

## Benchmarking

The `BenchmarkRunner` executes 3 rounds of standardized banking prompts and reports:

| Metric | Description |
|---|---|
| **tok/s** | Tokens generated per second (throughput) |
| **TTFT** | Time to first token in ms (latency) |
| **Total** | Total generation time in ms |
| **Tokens** | Number of tokens generated |

Results are displayed in-app and logged to the console.

---

## Project Structure

```
mobile-ai/
├── App.tsx                            # Root UI component
├── index.js                           # RN entry point
├── app.json                           # App name config
├── package.json                       # Dependencies
├── tsconfig.json                      # TypeScript config
├── babel.config.js                    # Babel config
├── metro.config.js                    # Metro bundler config
├── .gitignore                         # Git exclusions
├── android/                           # Android native project
│   ├── build.gradle                   # Root Gradle config
│   ├── settings.gradle                # Gradle settings
│   ├── gradle.properties              # Gradle properties (4GB heap)
│   └── app/
│       ├── build.gradle               # App Gradle (OpenCL flags, large heap)
│       ├── proguard-rules.pro         # ProGuard (keep llama.rn JNI)
│       └── src/main/AndroidManifest.xml
└── src/
    ├── config/
    │   └── ModelRegistry.ts           # Model metadata & download URLs
    ├── services/
    │   ├── DeviceGate.ts              # Device profiling & RAM gating
    │   └── BenchmarkRunner.ts         # Performance benchmarking
    └── store/
        └── ModelStore.ts              # MobX model lifecycle store
```

---

## License

[MPL-2.0](../LICENSE) — Copyright since 2025 Mifos Initiative
