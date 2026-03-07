import { storageService, TTSSettings, TTSSession } from "./StorageService";
import {
  TTSPlaybackController,
  TTSPlaybackConfig,
  TTSState,
  TTSStartOptions,
} from "./tts/TTSPlaybackController";
import { ttsMediaSessionService } from "./TtsMediaSessionService";

export type { TTSState } from "./tts/TTSPlaybackController";

export const TTS_STATE_EVENTS = {
  STATE_CHANGED: "tts-state-changed",
};

const TTS_SESSION_VERSION = 1;

interface PlaybackContext {
  storyId: string;
  chapterId: string;
  chapterTitle: string;
}

export interface TTSStartRequest extends TTSStartOptions {
  chunks: string[];
  title?: string;
  storyId: string;
  chapterId: string;
  chapterTitle: string;
}

class TTSStateManager {
  private static instance: TTSStateManager;
  private controller: TTSPlaybackController | null = null;
  private _settings: TTSSettings = { pitch: 1.0, rate: 1.0, chunkSize: 500 };
  private playbackContext: PlaybackContext | null = null;
  private pendingOnFinishCallback: (() => void) | null = null;
  private commandQueue: Promise<void> = Promise.resolve();

  // Pending state for ensuring final state is persisted
  private pendingStorageWrite: {
    state: TTSState;
    wasPlayingOverride?: boolean;
  } | null = null;
  private storageWriteTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private static readonly STORAGE_WRITE_THROTTLE_MS = 500;

  // State emission debouncing
  private stateEmitTimeoutId: ReturnType<typeof setTimeout> | null = null;
  private pendingStateEmit: TTSState | null = null;
  private static readonly STATE_EMIT_DEBOUNCE_MS = 100;

  private constructor() {
    void this.loadSettings();
    ttsMediaSessionService.registerMediaButtonHandler(() => {
      void this.playPause();
    });
  }

  public static getInstance(): TTSStateManager {
    if (!TTSStateManager.instance) {
      TTSStateManager.instance = new TTSStateManager();
    }
    return TTSStateManager.instance;
  }

  public static resetInstance(): void {
    try {
      if (TTSStateManager.instance) {
        TTSStateManager.instance.cleanup();
      }
    } catch {
      // Ignore errors during cleanup (e.g., if already torn down)
    }
    (TTSStateManager.instance as TTSStateManager | null) = null;
  }

  public clearPendingTimeouts(): void {
    this.cleanup();
  }

  private cleanup(): void {
    try {
      if (this.stateEmitTimeoutId) {
        clearTimeout(this.stateEmitTimeoutId);
        this.stateEmitTimeoutId = null;
      }
      if (this.storageWriteTimeoutId) {
        clearTimeout(this.storageWriteTimeoutId);
        this.storageWriteTimeoutId = null;
      }
    } catch {
      // Ignore cleanup errors
    }
  }

  private async loadSettings() {
    const settings = await storageService.getTTSSettings();
    if (settings) {
      this._settings = settings;
    }
  }

  private emitStateChange(state: TTSState) {
    // Debounce state emissions to prevent UI overload while ensuring final state is emitted
    this.pendingStateEmit = state;

    if (this.stateEmitTimeoutId) {
      return; // Already scheduled, will emit the latest pending state
    }

    this.stateEmitTimeoutId = setTimeout(() => {
      this.stateEmitTimeoutId = null;
      if (this.pendingStateEmit) {
        const { DeviceEventEmitter } = require("react-native");
        DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, this.pendingStateEmit);
        this.pendingStateEmit = null;
      }
    }, TTSStateManager.STATE_EMIT_DEBOUNCE_MS);
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    // Chain operations sequentially, replacing the queue reference with a settled promise
    // to prevent unbounded chain growth while ensuring no operations are lost
    const result = this.commandQueue.then(operation);
    // Replace queue with a settled promise that won't reject
    this.commandQueue = result.then(
      () => undefined,
      () => undefined, // Swallow errors to prevent chain breakage
    );
    return result;
  }

  private buildPlaybackBody(state: TTSState): string {
    if (state.isPaused) {
      return `Paused: Chunk ${state.currentChunkIndex + 1}`;
    }
    return `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
  }

  private buildSession(
    state: TTSState,
    wasPlayingOverride?: boolean,
  ): TTSSession | null {
    if (!this.playbackContext) return null;

    return {
      storyId: this.playbackContext.storyId,
      chapterId: this.playbackContext.chapterId,
      chapterTitle: this.playbackContext.chapterTitle,
      currentChunkIndex: state.currentChunkIndex,
      isPaused: state.isPaused,
      wasPlaying: wasPlayingOverride ?? (state.isSpeaking && !state.isPaused),
      chunkSize: this._settings.chunkSize,
      voiceIdentifier: this._settings.voiceIdentifier,
      rate: this._settings.rate,
      pitch: this._settings.pitch,
      updatedAt: Date.now(),
      sessionVersion: TTS_SESSION_VERSION,
    };
  }

  private async persistSessionFromState(
    state: TTSState,
    wasPlayingOverride?: boolean,
  ) {
    const session = this.buildSession(state, wasPlayingOverride);
    if (!session) return;

    // Always store the pending write to ensure latest state is saved
    this.pendingStorageWrite = { state, wasPlayingOverride };

    // Clear any existing timeout
    if (this.storageWriteTimeoutId) {
      return; // Already scheduled, will save the latest pending state
    }

    // Schedule the write
    this.storageWriteTimeoutId = setTimeout(async () => {
      this.storageWriteTimeoutId = null;
      if (this.pendingStorageWrite) {
        const { state: pendingState, wasPlayingOverride: pendingOverride } =
          this.pendingStorageWrite;
        this.pendingStorageWrite = null;
        const sessionToSave = this.buildSession(pendingState, pendingOverride);
        if (sessionToSave) {
          await storageService.saveTTSSession(sessionToSave);
        }
      }
    }, TTSStateManager.STORAGE_WRITE_THROTTLE_MS);
  }

  private buildMediaPayload(state: TTSState, isPlayingOverride?: boolean) {
    const context = this.playbackContext;
    return {
      title: state.title,
      body: this.buildPlaybackBody(state),
      isPlaying: isPlayingOverride ?? (state.isSpeaking && !state.isPaused),
      storyId: context?.storyId,
      chapterId: context?.chapterId,
    };
  }

  private async updateMediaSession() {
    const state = this.getState();
    if (!state) return;
    await ttsMediaSessionService.updateSession(this.buildMediaPayload(state));
  }

  private async onControllerFinish() {
    await this.enqueue(async () => {
      this.playbackContext = null;
      await storageService.clearTTSSession();
      await ttsMediaSessionService.stopSession();
      this.pendingOnFinishCallback?.();
    });
  }

  public getState(): TTSState | null {
    return this.controller?.getState() || null;
  }

  public getSettings(): TTSSettings {
    return this._settings;
  }

  public async updateSettings(newSettings: TTSSettings) {
    this._settings = newSettings;
    await storageService.saveTTSSettings(newSettings);
    this.controller?.updateSettings(newSettings);
    const state = this.getState();
    if (state?.isSpeaking || state?.isPaused) {
      await this.persistSessionFromState(state);
    }
  }

  public setOnFinishCallback(callback: (() => void) | null) {
    this.pendingOnFinishCallback = callback;
    this.controller?.setOnFinishCallback(callback);
  }

  public async getPersistedSession(): Promise<TTSSession | null> {
    return storageService.getTTSSession();
  }

  public async clearPersistedSession(): Promise<void> {
    await storageService.clearTTSSession();
  }

  public async start(request: TTSStartRequest): Promise<void>;
  public async start(chunks: string[], title?: string): Promise<void>;
  public async start(
    requestOrChunks: TTSStartRequest | string[],
    title: string = "Reading",
  ) {
    const request = Array.isArray(requestOrChunks)
      ? {
          chunks: requestOrChunks,
          title,
          storyId: "",
          chapterId: "",
          chapterTitle: title,
        }
      : requestOrChunks;

    if (!request || !request.chunks || request.chunks.length === 0) return;

    await this.enqueue(async () => {
      if (!this.controller) {
        this.initializeController(request.chunks, request.title ?? "Reading");
      }

      this.playbackContext = {
        storyId: request.storyId,
        chapterId: request.chapterId,
        chapterTitle: request.chapterTitle,
      };

      this.controller?.start(request.chunks, request.title ?? "Reading", {
        startChunkIndex: request.startChunkIndex,
        startPaused: request.startPaused,
      });

      const state = this.getState();
      if (!state) return;

      await this.persistSessionFromState(state);
      await ttsMediaSessionService.startSession(this.buildMediaPayload(state));
    });
  }

  /**
   * Helper to run a controller action and sync state to storage + media session.
   * Reduces boilerplate across playback control methods.
   */
  private async withStateSync(
    action: () => void | Promise<void>,
    wasPlayingOverride?: boolean,
  ): Promise<void> {
    await this.enqueue(async () => {
      await action();
      const state = this.getState();
      if (!state) return;
      await this.persistSessionFromState(state, wasPlayingOverride);
      await this.updateMediaSession();
    });
  }

  public async stop() {
    await this.enqueue(async () => {
      await this.controller?.stop();
      this.playbackContext = null;
      await storageService.clearTTSSession();
      await ttsMediaSessionService.stopSession();
    });
  }

  public async pause() {
    await this.withStateSync(() => this.controller?.pause(), false);
  }

  public async resume() {
    await this.withStateSync(() => this.controller?.resume(), true);
  }

  public async playPause() {
    await this.withStateSync(() => this.controller?.playPause());
  }

  public async next() {
    await this.withStateSync(() => this.controller?.next(), true);
  }

  public async previous() {
    await this.withStateSync(() => this.controller?.previous(), true);
  }

  public async recoverPlaybackFromSilentStop() {
    await this.enqueue(async () => {
      const state = this.getState();
      if (!state || !state.isSpeaking || state.isPaused) return;
      await this.controller?.restartCurrentChunk();
      const latest = this.getState();
      if (!latest) return;
      await this.persistSessionFromState(latest, true);
      await this.updateMediaSession();
    });
  }

  public async restoreForChapter(params: {
    chunks: string[];
    title: string;
    storyId: string;
    chapterId: string;
    chapterTitle: string;
  }): Promise<boolean> {
    const existingState = this.getState();
    if (existingState?.isSpeaking || existingState?.isPaused) return false;

    const session = await storageService.getTTSSession();
    if (!session) return false;
    if (!session.storyId || !session.chapterId) return false;
    if (
      session.storyId !== params.storyId ||
      session.chapterId !== params.chapterId
    )
      return false;

    if (!session.wasPlaying && !session.isPaused) return false;

    await this.start({
      chunks: params.chunks,
      title: params.title,
      storyId: params.storyId,
      chapterId: params.chapterId,
      chapterTitle: params.chapterTitle,
      startChunkIndex: session.currentChunkIndex,
      startPaused: session.isPaused,
    });

    return true;
  }

  private initializeController(chunks: string[], title: string) {
    const config: TTSPlaybackConfig = {
      onStateChange: (state: TTSState) => {
        this.emitStateChange(state);
      },
      onChunkChange: (currentIndex: number) => {
        const state = this.getState();
        if (!state) return;

        const updatedState: TTSState = {
          ...state,
          currentChunkIndex: currentIndex,
        };
        void this.persistSessionFromState(updatedState);
        void this.updateMediaSession();
      },
      onFinish: () => {
        void this.onControllerFinish();
      },
    };

    this.controller = new TTSPlaybackController(
      chunks,
      title,
      this._settings,
      config,
    );
    this.controller.setOnFinishCallback(this.pendingOnFinishCallback);
  }
}

export const ttsStateManager = TTSStateManager.getInstance();
