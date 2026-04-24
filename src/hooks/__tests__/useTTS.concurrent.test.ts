import { act, renderHook, waitFor } from "@testing-library/react-native";
import { useTTS } from "../useTTS";
import { TTS_STATE_EVENTS } from "../../services/TTSStateManager";

const mockEventListeners = new Map<string, Function>();

jest.mock("../../services/StorageService", () => ({
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
    emit: jest.fn((event: string, data: any) => {
      mockEventListeners.get(event)?.(data);
    }),
    addListener: jest.fn((event: string, callback: Function) => {
      mockEventListeners.set(event, callback);
      return { remove: jest.fn(() => mockEventListeners.delete(event)) };
    }),
  },
  Platform: { OS: "android" },
}));

jest.mock("expo-constants", () => ({
  executionEnvironment: "bare",
}));

jest.mock("../../services/NotifeeTypes", () => ({
  loadNotifee: jest.fn(() => null),
}));

jest.mock("../../services/TtsMediaSessionService", () => ({
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

describe("useTTS - Concurrent Operations", () => {
  beforeEach(async () => {
    jest.clearAllMocks();
    mockEventListeners.clear();
    const { ttsStateManager } = require("../../services/TTSStateManager");
    await ttsStateManager.stop();
  });

  afterEach(async () => {
    const { ttsStateManager } = require("../../services/TTSStateManager");
    await ttsStateManager.stop();
  });

  it("should handle rapid play/pause toggles through native playback", async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.toggleSpeech(["chunk1", "chunk2"], "Test Story");
      await result.current.handlePlayPause();
      await result.current.handlePlayPause();
      await result.current.handlePlayPause();
    });

    const { ttsMediaSessionService } = require("../../services/TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).toHaveBeenCalled();
    expect(ttsMediaSessionService.pausePlayback).toHaveBeenCalledTimes(2);
    expect(ttsMediaSessionService.resumePlayback).toHaveBeenCalledTimes(1);
  });

  it("should handle rapid next and previous controls", async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.toggleSpeech(["chunk1", "chunk2", "chunk3"], "Test Story");
      await result.current.handleNextChunk();
      await result.current.handleNextChunk();
      await result.current.handlePreviousChunk();
    });

    const { ttsMediaSessionService } = require("../../services/TtsMediaSessionService");
    expect(ttsMediaSessionService.next).toHaveBeenCalledTimes(2);
    expect(ttsMediaSessionService.previous).toHaveBeenCalledTimes(1);
  });

  it("should keep UI state synchronized from native state events", async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.toggleSpeech(["chunk1", "chunk2"], "Test Story");
    });

    act(() => {
      mockEventListeners.get(TTS_STATE_EVENTS.STATE_CHANGED)?.({
        isSpeaking: false,
        isPaused: true,
        chunks: ["chunk1", "chunk2"],
        currentChunkIndex: 1,
        title: "Test Story",
      });
    });

    await waitFor(() => {
      expect(result.current.isPaused).toBe(true);
      expect(result.current.currentChunkIndex).toBe(1);
      expect(result.current.isControllerVisible).toBe(true);
    });
  });

  it("should start from a selected text unit", async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.startSpeechAt(
        ["chunk1", "chunk2", "chunk3"],
        "Test Story",
        2,
      );
    });

    const { ttsMediaSessionService } = require("../../services/TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).toHaveBeenCalledWith(
      expect.objectContaining({ startIndex: 2 }),
    );
  });

  it("should ignore empty starts", async () => {
    const { result } = renderHook(() => useTTS());

    await act(async () => {
      await result.current.toggleSpeech([], "Empty");
    });

    const { ttsMediaSessionService } = require("../../services/TtsMediaSessionService");
    expect(ttsMediaSessionService.startPlayback).not.toHaveBeenCalled();
  });
});
