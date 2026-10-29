/* eslint-env jest */
jest.mock('react-native-device-info', () =>
  require('react-native-device-info/jest/react-native-device-info-mock'),
);

jest.mock('react-native-fs', () => ({
  DocumentDirectoryPath: '/mock/documents',
  exists: jest.fn().mockResolvedValue(true),
  mkdir: jest.fn().mockResolvedValue(undefined),
  downloadFile: jest.fn().mockReturnValue({
    jobId: 1,
    promise: Promise.resolve({statusCode: 200}),
  }),
  stopDownload: jest.fn(),
  moveFile: jest.fn().mockResolvedValue(undefined),
  unlink: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('@pocketpalai/llama.rn', () => ({
  initLlama: jest.fn(),
}));
