const {getDefaultConfig, mergeConfig} = require('@react-native/metro-config');

/**
 * Metro bundler configuration for the Mobile AI POC.
 * @see https://reactnative.dev/docs/metro
 */
const config = {};

module.exports = mergeConfig(getDefaultConfig(__dirname), config);
