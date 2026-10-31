/**
 * App.tsx — Root component for the Mobile AI POC.
 *
 * Provides a simple single-screen chat interface for
 * testing on-device inference with Clef-Flash via llama.rn.
 */

import React, {useEffect, useState, useCallback} from 'react';
import {
  SafeAreaView,
  ScrollView,
  View,
  Text,
  TextInput,
  TouchableOpacity,
  StyleSheet,
  ActivityIndicator,
  Alert,
  StatusBar,
} from 'react-native';
import {observer} from 'mobx-react-lite';
import {modelStore} from './src/store/ModelStore';
import {getDefaultModel} from './src/config/ModelRegistry';
import {runBenchmark} from './src/services/BenchmarkRunner';

const App = observer(() => {
  const [prompt, setPrompt] = useState('');
  const [initialized, setInitialized] = useState(false);
  const [isBenchmarking, setIsBenchmarking] = useState(false);
  const [modelExists, setModelExists] = useState(false);

  // ── Initialization: Profile device and check model ──
  useEffect(() => {
    (async () => {
      try {
        const model = getDefaultModel();
        const gate = await modelStore.profileAndGate(model);

        if (!gate.allowed) {
          Alert.alert(
            'Device Not Supported',
            gate.reason ?? 'This device does not meet minimum hardware requirements.',
          );
          return;
        }

        if (gate.reason) {
          // Warning (e.g., emulator)
          Alert.alert('Notice', gate.reason);
        }

        const exists = await modelStore.checkModelExists(model);
        setModelExists(exists);
        setInitialized(true);
      } catch (err: any) {
        Alert.alert(
          'Initialization Error',
          err?.message ?? 'Failed to profile device or initialize model.',
        );
        setInitialized(true);
      }
    })();
  }, []);

  // ── Sync model existence on state change ──
  useEffect(() => {
    if (modelStore.state === 'idle' || modelStore.state === 'error') {
      modelStore.checkModelExists().then(setModelExists).catch(() => setModelExists(false));
    }
  }, [modelStore.state]);

  // ── Handlers ──
  const handleDownload = useCallback(async () => {
    try {
      if (modelStore.state === 'error') {
        await modelStore.unloadModel();
      }
      await modelStore.downloadModel();
      const exists = await modelStore.checkModelExists();
      setModelExists(exists);
      if (modelStore.error || modelStore.state === 'error') {
        Alert.alert('Error', modelStore.error ?? 'Download failed.');
      } else if (exists) {
        Alert.alert('Success', 'Model downloaded successfully.');
      }
    } catch (err: any) {
      Alert.alert('Error', err?.message ?? modelStore.error ?? 'Download failed.');
    }
  }, []);

  const handleLoad = useCallback(async () => {
    try {
      if (modelStore.state === 'error') {
        await modelStore.unloadModel();
      }
      await modelStore.loadModel();
      if (modelStore.error || modelStore.state === 'error') {
        Alert.alert('Error', modelStore.error ?? 'Failed to load model.');
      }
    } catch (err: any) {
      Alert.alert('Error', err?.message ?? modelStore.error ?? 'Failed to load model.');
    }
  }, []);

  const handleGenerate = useCallback(async () => {
    if (!prompt.trim() || isBenchmarking) return;
    try {
      await modelStore.generate(prompt.trim());
    } catch {
      Alert.alert('Error', modelStore.error ?? 'Generation failed.');
    }
  }, [prompt, isBenchmarking]);

  const handleStop = useCallback(() => {
    modelStore.stopGeneration();
  }, []);

  const handleBenchmark = useCallback(async () => {
    if (isBenchmarking || modelStore.isGenerating) return;
    setIsBenchmarking(true);
    try {
      const summary = await runBenchmark(3, 256);
      Alert.alert(
        'Benchmark Complete',
        `Avg: ${summary.avgTokPerSec} tok/s\n` +
        `TTFT: ${summary.avgTtftMs}ms\n` +
        `Range: ${summary.minTokPerSec}-${summary.maxTokPerSec} tok/s`,
      );
    } catch (err: any) {
      Alert.alert('Benchmark Error', err.message);
    } finally {
      setIsBenchmarking(false);
    }
  }, [isBenchmarking]);

  const handleUnload = useCallback(async () => {
    if (isBenchmarking || modelStore.isGenerating) return;
    await modelStore.unloadModel();
  }, [isBenchmarking]);

  // ── Render ──
  return (
    <SafeAreaView style={styles.container}>
      <StatusBar barStyle="light-content" backgroundColor="#1a1a2e" />

      {/* Header */}
      <View style={styles.header}>
        <Text style={styles.title}>Mifos Mobile AI</Text>
        <Text style={styles.subtitle}>
          {modelStore.activeModel?.name ?? 'No model selected'}
        </Text>
        <View style={styles.statusRow}>
          <View
            style={[
              styles.statusDot,
              {backgroundColor: statusColor(modelStore.state)},
            ]}
          />
          <Text style={styles.statusText}>{modelStore.state.toUpperCase()}</Text>
        </View>
      </View>

      {/* Device Info */}
      {modelStore.deviceProfile && (
        <View style={styles.deviceInfo}>
          <Text style={styles.deviceText}>
            {modelStore.deviceProfile.brand} {modelStore.deviceProfile.model} ·{' '}
            {modelStore.deviceProfile.totalRamGb.toFixed(1)} GB RAM ·{' '}
            {modelStore.deviceProfile.hasGpuAcceleration ? 'GPU ✓' : 'CPU only'}
          </Text>
        </View>
      )}

      {/* Controls */}
      <View style={styles.controls}>
        {!initialized ? (
          <ActivityIndicator size="large" color="#e94560" />
        ) : (
          <>
            {(modelStore.state === 'idle' || modelStore.state === 'error') && (
              <View style={styles.buttonRow}>
                <TouchableOpacity
                  style={styles.button}
                  onPress={handleDownload}>
                  <Text style={styles.buttonText}>Download Model</Text>
                </TouchableOpacity>
                <TouchableOpacity
                  style={[
                    styles.button,
                    styles.buttonSecondary,
                    !modelExists && styles.buttonDisabled,
                  ]}
                  onPress={handleLoad}
                  disabled={!modelExists}>
                  <Text style={styles.buttonText}>Load Model</Text>
                </TouchableOpacity>
              </View>
            )}

            {modelStore.state === 'downloading' && modelStore.downloadProgress && (
              <View style={styles.progressContainer}>
                <Text style={styles.progressText}>
                  Downloading: {modelStore.downloadProgress.percent}%
                </Text>
                <View style={styles.progressBar}>
                  <View
                    style={[
                      styles.progressFill,
                      {width: `${modelStore.downloadProgress.percent}%`},
                    ]}
                  />
                </View>
                <Text style={styles.progressDetail}>
                  {(modelStore.downloadProgress.bytesWritten / 1e9).toFixed(2)} /{' '}
                  {(modelStore.downloadProgress.contentLength / 1e9).toFixed(2)} GB
                </Text>
                <TouchableOpacity
                  style={[styles.button, styles.buttonDanger]}
                  onPress={() => modelStore.cancelDownload()}>
                  <Text style={styles.buttonText}>Cancel</Text>
                </TouchableOpacity>
              </View>
            )}

            {modelStore.state === 'loading' && (
              <View style={styles.progressContainer}>
                <ActivityIndicator size="large" color="#e94560" />
                <Text style={styles.progressText}>
                  Loading model: {modelStore.loadProgress}%
                </Text>
              </View>
            )}

            {(modelStore.state === 'ready' || modelStore.state === 'generating') && (
              <>
                <View style={styles.inputRow}>
                  <TextInput
                    style={styles.input}
                    value={prompt}
                    onChangeText={setPrompt}
                    placeholder="Ask a banking question..."
                    placeholderTextColor="#666"
                    multiline
                    editable={!modelStore.isGenerating && !isBenchmarking}
                  />
                  {modelStore.isGenerating ? (
                    <TouchableOpacity
                      style={[styles.sendButton, styles.buttonDanger]}
                      onPress={handleStop}>
                      <Text style={styles.sendText}>Stop</Text>
                    </TouchableOpacity>
                  ) : (
                    <TouchableOpacity
                      style={[
                        styles.sendButton,
                        isBenchmarking && styles.buttonDisabled,
                      ]}
                      onPress={handleGenerate}
                      disabled={isBenchmarking}>
                      <Text style={styles.sendText}>Send</Text>
                    </TouchableOpacity>
                  )}
                </View>
                <View style={styles.buttonRow}>
                  <TouchableOpacity
                    style={[
                      styles.button,
                      styles.buttonSmall,
                      (modelStore.isGenerating || isBenchmarking) && styles.buttonDisabled,
                    ]}
                    onPress={handleBenchmark}
                    disabled={modelStore.isGenerating || isBenchmarking}>
                    <Text style={styles.buttonTextSmall}>
                      {isBenchmarking ? 'Benchmarking...' : 'Benchmark'}
                    </Text>
                  </TouchableOpacity>
                  <TouchableOpacity
                    style={[
                      styles.button,
                      styles.buttonSmall,
                      styles.buttonDanger,
                      (modelStore.isGenerating || isBenchmarking) && styles.buttonDisabled,
                    ]}
                    onPress={handleUnload}
                    disabled={modelStore.isGenerating || isBenchmarking}>
                    <Text style={styles.buttonTextSmall}>Unload</Text>
                  </TouchableOpacity>
                </View>
              </>
            )}
          </>
        )}
      </View>

      {/* Output */}
      <ScrollView style={styles.output} contentContainerStyle={styles.outputContent}>
        {modelStore.streamingTokens ? (
          <Text style={styles.outputText}>{modelStore.streamingTokens}</Text>
        ) : modelStore.lastResult ? (
          <Text style={styles.outputText}>{modelStore.lastResult.text}</Text>
        ) : (
          <Text style={styles.placeholder}>
            Generated output will appear here...
          </Text>
        )}
      </ScrollView>

      {/* Metrics */}
      {modelStore.lastResult && (
        <View style={styles.metrics}>
          <MetricItem label="tok/s" value={modelStore.lastResult.tokPerSec.toString()} />
          <MetricItem label="TTFT" value={`${modelStore.lastResult.ttftMs}ms`} />
          <MetricItem label="Total" value={`${modelStore.lastResult.totalMs}ms`} />
          <MetricItem label="Tokens" value={modelStore.lastResult.tokensGenerated.toString()} />
        </View>
      )}

      {/* Error */}
      {modelStore.error && (
        <View style={styles.errorContainer}>
          <Text style={styles.errorText}>{modelStore.error}</Text>
        </View>
      )}
    </SafeAreaView>
  );
});

const MetricItem = ({label, value}: {label: string; value: string}) => (
  <View style={styles.metricItem}>
    <Text style={styles.metricValue}>{value}</Text>
    <Text style={styles.metricLabel}>{label}</Text>
  </View>
);

function statusColor(state: string): string {
  switch (state) {
    case 'ready': return '#4ecca3';
    case 'generating': return '#e94560';
    case 'downloading':
    case 'loading': return '#f5a623';
    case 'error': return '#ff4444';
    default: return '#666';
  }
}

const styles = StyleSheet.create({
  container: {flex: 1, backgroundColor: '#1a1a2e'},
  header: {paddingHorizontal: 20, paddingTop: 16, paddingBottom: 12},
  title: {fontSize: 24, fontWeight: '700', color: '#eee'},
  subtitle: {fontSize: 14, color: '#888', marginTop: 2},
  statusRow: {flexDirection: 'row', alignItems: 'center', marginTop: 8},
  statusDot: {width: 8, height: 8, borderRadius: 4, marginRight: 8},
  statusText: {fontSize: 12, color: '#aaa', fontWeight: '600'},
  deviceInfo: {
    paddingHorizontal: 20,
    paddingVertical: 8,
    backgroundColor: '#16213e',
    borderRadius: 8,
    marginHorizontal: 16,
  },
  deviceText: {fontSize: 12, color: '#7f8c8d'},
  controls: {padding: 16},
  buttonRow: {flexDirection: 'row', gap: 12},
  button: {
    flex: 1,
    backgroundColor: '#e94560',
    paddingVertical: 14,
    borderRadius: 12,
    alignItems: 'center',
  },
  buttonSecondary: {backgroundColor: '#0f3460'},
  buttonDanger: {backgroundColor: '#c0392b'},
  buttonDisabled: {opacity: 0.5},
  buttonSmall: {paddingVertical: 8},
  buttonText: {color: '#fff', fontWeight: '600', fontSize: 15},
  buttonTextSmall: {color: '#fff', fontWeight: '600', fontSize: 13},
  progressContainer: {alignItems: 'center', gap: 8},
  progressText: {color: '#eee', fontSize: 14, fontWeight: '600'},
  progressDetail: {color: '#888', fontSize: 12},
  progressBar: {
    width: '100%',
    height: 6,
    backgroundColor: '#16213e',
    borderRadius: 3,
    overflow: 'hidden',
  },
  progressFill: {height: '100%', backgroundColor: '#e94560', borderRadius: 3},
  inputRow: {flexDirection: 'row', gap: 8, marginBottom: 12},
  input: {
    flex: 1,
    backgroundColor: '#16213e',
    color: '#eee',
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderRadius: 12,
    fontSize: 14,
    maxHeight: 100,
  },
  sendButton: {
    backgroundColor: '#e94560',
    paddingHorizontal: 20,
    borderRadius: 12,
    justifyContent: 'center',
  },
  sendText: {color: '#fff', fontWeight: '700', fontSize: 14},
  output: {
    flex: 1,
    backgroundColor: '#16213e',
    marginHorizontal: 16,
    borderRadius: 12,
    padding: 16,
  },
  outputContent: {flexGrow: 1},
  outputText: {color: '#eee', fontSize: 14, lineHeight: 22},
  placeholder: {color: '#555', fontSize: 14, fontStyle: 'italic'},
  metrics: {
    flexDirection: 'row',
    justifyContent: 'space-around',
    paddingVertical: 12,
    paddingHorizontal: 16,
    backgroundColor: '#0f3460',
    marginHorizontal: 16,
    marginTop: 8,
    borderRadius: 12,
    marginBottom: 8,
  },
  metricItem: {alignItems: 'center'},
  metricValue: {color: '#4ecca3', fontSize: 16, fontWeight: '700'},
  metricLabel: {color: '#888', fontSize: 11, marginTop: 2},
  errorContainer: {
    margin: 16,
    padding: 12,
    backgroundColor: '#2c0b0b',
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#ff4444',
  },
  errorText: {color: '#ff6666', fontSize: 13},
});

export default App;
