module.exports = {
  presets: ['module:@react-native/babel-preset'],
  plugins: [
    // MobX decorators support
    ['@babel/plugin-proposal-decorators', {legacy: true}],
  ],
};
