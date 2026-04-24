import { ttsStateManager } from "../TTSStateManager";

jest.mock("../StorageService", () => ({
  storageService: {
    getTTSSettings: jest
      .fn()
      .mockResolvedValue({ pitch: 1.0, rate: 1.0, chunkSize: 500 }),
    saveTTSSettings: jest.fn().mockResolvedValue(undefined),
    getTTSSession: jest.fn().mockResolvedValue(null),
    saveTTSSession: jest.fn().mockResolvedValue(undefined),
    clearTTSSession: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock("react-native", () => ({
  DeviceEventEmitter: {
    emit: jest.fn(),
  },
}));

jest.mock("../TtsMediaSessionService", () => ({
  ttsMediaSessionService: {
    isNativePlaybackAvailable: jest.fn().mockReturnValue(true),
    startPlayback: jest.fn().mockResolvedValue(undefined),
    pausePlayback: jest.fn().mockResolvedValue(undefined),
    resumePlayback: jest.fn().mockResolvedValue(undefined),
    playPause: jest.fn().mockResolvedValue(undefined),
    next: jest.fn().mockResolvedValue(undefined),
    previous: jest.fn().mockResolvedValue(undefined),
    seekToUnit: jest.fn().mockResolvedValue(undefined),
    stopPlayback: jest.fn().mockResolvedValue(undefined),
    registerMediaButtonHandler: jest.fn(),
    registerPlaybackStateHandler: jest.fn(),
  },
}));

describe("TTSStateManager - Concurrent Operations", () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    await ttsStateManager.stop();
  });

  afterEach(async () => {
    await ttsStateManager.stop();
    ttsStateManager.clearPendingTimeouts();
  });

  it("serializes rapid play/pause operations", async () => {
    await ttsStateManager.start(["chunk1", "chunk2"], "Test Story");
    await Promise.all([
      ttsStateManager.pause(),
      ttsStateManager.resume(),
      ttsStateManager.pause(),
    ]);

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.pausePlayback).toHaveBeenCalledTimes(2);
    expect(ttsMediaSessionService.resumePlayback).toHaveBeenCalledTimes(1);
  });

  it("serializes rapid navigation operations", async () => {
    await ttsStateManager.start(["chunk1", "chunk2", "chunk3"], "Test Story");
    await Promise.all([
      ttsStateManager.next(),
      ttsStateManager.next(),
      ttsStateManager.previous(),
      ttsStateManager.seekToUnit(2),
    ]);

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.next).toHaveBeenCalledTimes(2);
    expect(ttsMediaSessionService.previous).toHaveBeenCalledTimes(1);
    expect(ttsMediaSessionService.seekToUnit).toHaveBeenCalledWith(2);
  });

  it("handles stop immediately after start", async () => {
    await Promise.all([
      ttsStateManager.start(["chunk1"], "Test Story"),
      ttsStateManager.stop(),
    ]);

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).toHaveBeenCalled();
    expect(ttsMediaSessionService.stopPlayback).toHaveBeenCalled();
  });

  it("restarts native playback when settings change during playback", async () => {
    await ttsStateManager.start(["chunk1", "chunk2"], "Test Story");
    await ttsStateManager.updateSettings({
      pitch: 1.3,
      rate: 1.4,
      chunkSize: 400,
    });

    const { storageService } = require("../StorageService");
    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(storageService.saveTTSSettings).toHaveBeenCalledWith({
      pitch: 1.3,
      rate: 1.4,
      chunkSize: 400,
    });
    expect(ttsMediaSessionService.startPlayback).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pitch: 1.3,
        rate: 1.4,
      }),
    );
  });

  it("ignores invalid empty starts", async () => {
    await ttsStateManager.start([], "Empty");

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).not.toHaveBeenCalled();
  });
});
