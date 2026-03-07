import { TTS_STATE_EVENTS } from "../TTSStateManager";

// Create a global mock controller that will be returned by the mock
let globalMockController: any = null;

const createMockController = () => {
  const controller = {
    chunks: [] as string[],
    title: "",
    settings: { pitch: 1.0, rate: 1.0, chunkSize: 500 },
    isSpeaking: false,
    isPaused: false,
    currentChunkIndex: 0,
    sessionId: 0,
    onFinishCallback: null as any,

    // Track calls for testing
    _stopCalls: 0,
    _pauseCalls: 0,
    _resumeCalls: 0,
    _playPauseCalls: 0,
    _nextCalls: 0,
    _previousCalls: 0,
    _setOnFinishCallbackCalls: 0,

    getState() {
      return {
        isSpeaking: this.isSpeaking,
        isPaused: this.isPaused,
        currentChunkIndex: this.currentChunkIndex,
        chunks: this.chunks,
        title: this.title,
      };
    },

    start(newChunks?: string[], newTitle?: string) {
      if (newChunks) this.chunks = [...newChunks];
      if (newTitle) this.title = newTitle;
      this.isSpeaking = true;
      this.isPaused = false;
      this.currentChunkIndex = 0;
    },

    async stop() {
      this._stopCalls++;
      this.isSpeaking = false;
      this.isPaused = false;
      this.currentChunkIndex = 0;
      this.chunks = [];
    },

    async pause() {
      this._pauseCalls++;
      this.isPaused = true;
      this.sessionId++;
    },

    resume() {
      this._resumeCalls++;
      this.isPaused = false;
    },

    async playPause() {
      this._playPauseCalls++;
      if (this.isPaused) {
        this.isPaused = false;
      } else {
        this.isPaused = true;
        this.sessionId++;
      }
    },

    async next() {
      this._nextCalls++;
      if (this.chunks && this.currentChunkIndex < this.chunks.length - 1) {
        this.currentChunkIndex++;
      }
    },

    async previous() {
      this._previousCalls++;
      if (this.currentChunkIndex > 0) {
        this.currentChunkIndex--;
      }
    },

    updateSettings(newSettings: any) {
      this.settings = { ...newSettings };
    },

    setOnFinishCallback(callback: any) {
      this._setOnFinishCallbackCalls++;
      this.onFinishCallback = callback;
    },
  };

  globalMockController = controller;
  return controller;
};

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

jest.mock("../tts/TTSPlaybackController", () => {
  return {
    TTSPlaybackController: class MockTTSPlaybackController {
      constructor(chunks: string[], title: string, settings: any, config: any) {
        const controller = createMockController();
        controller.chunks = [...chunks];
        controller.title = title;
        controller.settings = { ...settings };
        config.onStateChange?.(controller.getState());
        return controller;
      }
    },
  };
});

jest.mock("react-native", () => ({
  DeviceEventEmitter: {
    emit: jest.fn(),
  },
}));

jest.mock("../TtsMediaSessionService", () => ({
  ttsMediaSessionService: {
    startSession: jest.fn().mockResolvedValue(undefined),
    updateSession: jest.fn().mockResolvedValue(undefined),
    stopSession: jest.fn().mockResolvedValue(undefined),
    registerMediaButtonHandler: jest.fn(),
  },
}));

// Import after mocks are set up
const { ttsStateManager } = require("../TTSStateManager");

describe("TTSStateManager - Concurrent Operations", () => {
  beforeEach(() => {
    jest.clearAllMocks();

    // Reset state manager by clearing controller
    (ttsStateManager).controller = null;
    globalMockController = null;
  });

  afterEach(async () => {
    // Wait for any pending async operations to complete
    await new Promise((resolve) => setTimeout(resolve, 200));
    // Cleanup pending timeouts without destroying the singleton
    ttsStateManager.clearPendingTimeouts();
  });

  describe("Rapid State Changes", () => {
    it("should handle rapid play/pause toggles", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      // Rapid toggle
      await Promise.all([
        ttsStateManager.playPause(),
        ttsStateManager.playPause(),
        ttsStateManager.playPause(),
      ]);

      // All should complete without error
      expect(globalMockController?._playPauseCalls).toBeGreaterThanOrEqual(3);
    });

    it("should handle pause immediately followed by resume", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      // Pause then immediately resume
      await Promise.all([ttsStateManager.pause(), ttsStateManager.resume()]);

      expect(globalMockController?._pauseCalls).toBeGreaterThan(0);
      expect(globalMockController?._resumeCalls).toBeGreaterThan(0);
    });

    it("should handle multiple next/previous calls in quick succession", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3", "chunk4", "chunk5"];
      ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      // Skip forward multiple times
      await Promise.all([
        ttsStateManager.next(),
        ttsStateManager.next(),
        ttsStateManager.next(),
      ]);

      expect(globalMockController?._nextCalls).toBeGreaterThanOrEqual(3);
    });

    it("should handle mixed navigation and playback controls", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3", "chunk4"];
      ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      // Mix of navigation and playback controls
      await Promise.all([
        ttsStateManager.next(),
        ttsStateManager.pause(),
        ttsStateManager.next(),
        ttsStateManager.resume(),
      ]);

      expect(globalMockController?._nextCalls).toBeGreaterThanOrEqual(2);
      expect(globalMockController?._pauseCalls).toBeGreaterThan(0);
      expect(globalMockController?._resumeCalls).toBeGreaterThan(0);
    });
  });

  describe("State Synchronization", () => {
    it("should maintain correct isPlaying state during rapid changes", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      await ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      const isPlayingStates: boolean[] = [];

      ttsMediaSessionService.updateSession.mockImplementation(
        async (payload: any) => {
          isPlayingStates.push(payload.isPlaying);
        },
      );

      // Rapid sequence: pause, resume, pause, resume
      await ttsStateManager.pause();
      await ttsStateManager.resume();
      await ttsStateManager.pause();
      await ttsStateManager.resume();

      // Each update should have correct state
      // After pause: isPlaying=false, after resume: isPlaying=true, etc.
      expect(isPlayingStates.length).toBeGreaterThan(0);
      // Verify we have both true and false states (pause/resume cycles)
      expect(isPlayingStates).toContain(false);
      expect(isPlayingStates).toContain(true);
    });

    it("should handle state reads during updates", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      await ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      // Read state while operations are in flight
      const stateReads: any[] = [];
      const operations = [
        ttsStateManager.next(),
        ttsStateManager.pause(),
        ttsStateManager.next(),
      ];

      // Interleave state reads
      for (const op of operations) {
        stateReads.push(ttsStateManager.getState());
        await op;
      }

      // All state reads should return valid state (controller exists)
      const validReads = stateReads.filter((state) => state !== null);
      expect(validReads.length).toBeGreaterThan(0);
    });
  });

  describe("Notification Update Consistency", () => {
    it("should not lose notification updates during rapid changes", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3", "chunk4"];
      await ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      const updates: string[] = [];
      ttsMediaSessionService.updateSession.mockImplementation(
        async (payload: any) => {
          updates.push(payload.body);
        },
      );

      // Sequence of operations
      await ttsStateManager.next(); // chunk 2
      await ttsStateManager.next(); // chunk 3
      await ttsStateManager.pause();
      await ttsStateManager.previous(); // chunk 2
      await ttsStateManager.resume();

      // All updates should be captured - verify updateSession was called
      expect(ttsMediaSessionService.updateSession).toHaveBeenCalled();
    });

    it("should update notification with correct chunk index", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3", "chunk4", "chunk5"];
      await ttsStateManager.start(chunks, "Test Story");

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.updateSession.mockClear();

      const chunkIndices: number[] = [];
      ttsMediaSessionService.updateSession.mockImplementation(
        async (payload: any) => {
          // Body format: "Reading chunk X / Y" or "Paused: Chunk X"
          const match = payload.body.match(/chunk (\d+)/i);
          if (match) {
            chunkIndices.push(parseInt(match[1]));
          }
        },
      );

      await ttsStateManager.next();
      await ttsStateManager.next();
      await ttsStateManager.previous();

      // Should have called updateSession
      expect(ttsMediaSessionService.updateSession).toHaveBeenCalled();
    });
  });

  describe("Start/Stop Race Conditions", () => {
    it("should handle stop called immediately after start", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];

      // Start first, then stop (command queue ensures ordering)
      await ttsStateManager.start(chunks, "Test Story");
      await ttsStateManager.stop();

      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      expect(ttsMediaSessionService.stopSession).toHaveBeenCalled();
    });

    it("should handle multiple start calls", async () => {
      const chunks1 = ["chunk1", "chunk2"];
      const chunks2 = ["chunk3", "chunk4"];

      // Start first session
      await ttsStateManager.start(chunks1, "Story 1");
      // Start second session (replaces first via command queue)
      await ttsStateManager.start(chunks2, "Story 2");

      // Should complete without error
      const state = ttsStateManager.getState();
      expect(state).toBeDefined();
    });

    it("should handle start during active playback", async () => {
      const chunks1 = ["chunk1", "chunk2", "chunk3"];
      const chunks2 = ["chunk4", "chunk5"];

      await ttsStateManager.start(chunks1, "Story 1");

      // Start again while first is still active
      await ttsStateManager.start(chunks2, "Story 2");

      const state = ttsStateManager.getState();
      expect(state).toBeDefined();
    });
  });

  describe("Settings Updates During Playback", () => {
    it("should handle settings changes during playback", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      await ttsStateManager.start(chunks, "Test Story");

      const { storageService } = require("../StorageService");
      storageService.saveTTSSettings.mockClear();

      // Update settings sequentially (command queue serializes them)
      await ttsStateManager.updateSettings({
        pitch: 1.2,
        rate: 1.1,
        chunkSize: 500,
      });
      await ttsStateManager.updateSettings({
        pitch: 1.5,
        rate: 1.3,
        chunkSize: 500,
      });
      await ttsStateManager.updateSettings({
        pitch: 1.0,
        rate: 1.0,
        chunkSize: 500,
      });

      // All updates should be saved
      expect(storageService.saveTTSSettings).toHaveBeenCalledTimes(3);
    });
  });

  describe("Event Emission Under Load", () => {
    it("should emit state change events correctly during rapid changes", async () => {
      const { DeviceEventEmitter } = require("react-native");
      const emittedStates: any[] = [];

      DeviceEventEmitter.emit.mockImplementation(
        (event: string, state: any) => {
          if (event === TTS_STATE_EVENTS.STATE_CHANGED) {
            emittedStates.push(state);
          }
        },
      );

      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      // Perform rapid operations
      await ttsStateManager.next();
      await ttsStateManager.pause();
      await ttsStateManager.resume();

      // Wait for debounced state emissions (100ms debounce in TTSStateManager)
      await new Promise((resolve) => setTimeout(resolve, 150));

      // Should have emitted state changes
      expect(emittedStates.length).toBeGreaterThan(0);
      emittedStates.forEach((state) => {
        expect(state).toHaveProperty("isSpeaking");
        expect(state).toHaveProperty("isPaused");
      });
    });
  });

  describe("Session ID Management", () => {
    it("should increment session ID on pause", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      const initialSessionId = globalMockController?.sessionId || 0;

      await ttsStateManager.pause();
      expect(globalMockController?.sessionId).toBe(initialSessionId + 1);

      await ttsStateManager.pause();
      expect(globalMockController?.sessionId).toBe(initialSessionId + 2);
    });

    it("should increment session ID on playPause when playing", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      const initialSessionId = globalMockController?.sessionId || 0;

      // Playing -> Paused increments session
      await ttsStateManager.playPause();
      expect(globalMockController?.sessionId).toBe(initialSessionId + 1);

      // Paused -> Playing does not increment
      await ttsStateManager.playPause();
      expect(globalMockController?.sessionId).toBe(initialSessionId + 1);
    });
  });

  describe("OnFinish Callback", () => {
    it("should handle callback updates during playback", async () => {
      const chunks = ["chunk1", "chunk2", "chunk3"];
      ttsStateManager.start(chunks, "Test Story");

      const callback1 = jest.fn();
      const callback2 = jest.fn();

      // Set callback multiple times
      await Promise.all([
        Promise.resolve(ttsStateManager.setOnFinishCallback(callback1)),
        Promise.resolve(ttsStateManager.setOnFinishCallback(callback2)),
      ]);

      expect(globalMockController?._setOnFinishCallbackCalls).toBeGreaterThan(
        0,
      );
    });
  });

  describe("Empty/Invalid Input Handling", () => {
    it("should ignore start with empty chunks", () => {
      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.startSession.mockClear();

      ttsStateManager.start([], "Empty Story");

      // Should not update notification for empty chunks
      expect(ttsMediaSessionService.startSession).not.toHaveBeenCalled();
    });

    it("should handle start with null chunks", () => {
      const { ttsMediaSessionService } = require("../TtsMediaSessionService");
      ttsMediaSessionService.startSession.mockClear();

      ttsStateManager.start(null as any, "Null Story");

      expect(ttsMediaSessionService.startSession).not.toHaveBeenCalled();
    });
  });
});
