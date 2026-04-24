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

describe("TTSStateManager", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  afterEach(async () => {
    await ttsStateManager.stop();
    ttsStateManager.clearPendingTimeouts();
  });

  it("should return the same instance", () => {
    expect(ttsStateManager).toBe(ttsStateManager);
  });

  it("should get settings", () => {
    expect(ttsStateManager.getSettings()).toEqual({
      pitch: 1.0,
      rate: 1.0,
      chunkSize: 500,
    });
  });

  it("should update settings and save to storage", async () => {
    const newSettings = { pitch: 1.5, rate: 1.2, chunkSize: 300 };
    await ttsStateManager.start(["chunk1", "chunk2"], "Test Story");
    await ttsStateManager.updateSettings(newSettings);

    const { storageService } = require("../StorageService");
    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(storageService.saveTTSSettings).toHaveBeenCalledWith(newSettings);
    expect(ttsMediaSessionService.startPlayback).toHaveBeenLastCalledWith(
      expect.objectContaining({
        pitch: 1.5,
        rate: 1.2,
      }),
    );
  });

  it("should start native playback with chunks and title", async () => {
    const chunks = ["chunk1", "chunk2", "chunk3"];
    await ttsStateManager.start(chunks, "Test Story");

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).toHaveBeenCalledWith(
      expect.objectContaining({
        units: chunks,
        title: "Test Story",
        startIndex: 0,
      }),
    );
  });

  it("should not start with empty chunks", async () => {
    await ttsStateManager.start([], "Test Story");
    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).not.toHaveBeenCalled();
  });

  it("should stop native playback and clear session", async () => {
    await ttsStateManager.stop();

    const { storageService } = require("../StorageService");
    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.stopPlayback).toHaveBeenCalled();
    expect(storageService.clearTTSSession).toHaveBeenCalled();
  });

  it("should pause and resume native playback", async () => {
    await ttsStateManager.start(["chunk1"], "Test Story");
    await ttsStateManager.pause();
    await ttsStateManager.resume();

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.pausePlayback).toHaveBeenCalled();
    expect(ttsMediaSessionService.resumePlayback).toHaveBeenCalled();
  });

  it("should seek next and previous units", async () => {
    await ttsStateManager.start(["chunk1", "chunk2"], "Test Story");
    await ttsStateManager.next();
    await ttsStateManager.previous();

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.next).toHaveBeenCalled();
    expect(ttsMediaSessionService.previous).toHaveBeenCalled();
  });

  it("should seek to a specific unit", async () => {
    await ttsStateManager.start(["chunk1", "chunk2"], "Test Story");
    await ttsStateManager.seekToUnit(1);

    const { ttsMediaSessionService } = require("../TtsMediaSessionService");
    expect(ttsMediaSessionService.seekToUnit).toHaveBeenCalledWith(1);
  });
});
