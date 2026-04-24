import { TTSSettings, TTSSession } from "../types";
import { storageService } from "./StorageService";
import {
  NativeTtsPlaybackState,
  ttsMediaSessionService,
} from "./TtsMediaSessionService";
import { TTSSessionPersistence } from "./tts/TTSSessionPersistence";
import { TTSStateEmitter } from "./tts/TTSStateEmitter";

export const TTS_STATE_EVENTS = {
  STATE_CHANGED: "tts-state-changed",
};

export interface TTSState {
  isSpeaking: boolean;
  isPaused: boolean;
  chunks: string[];
  currentChunkIndex: number;
  title: string;
}

export interface TTSStartOptions {
  startChunkIndex?: number;
  startPaused?: boolean;
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
  private _settings: TTSSettings = { pitch: 1.0, rate: 1.0, chunkSize: 500 };
  private state: TTSState | null = null;
  private initialized = false;
  private initializationPromise: Promise<void> | null = null;
  private pendingOnFinishCallback: (() => void) | null = null;
  private commandQueue: Promise<void> = Promise.resolve();
  private lastCompletedKey: string | null = null;

  private sessionPersistence = new TTSSessionPersistence();
  private stateEmitter = new TTSStateEmitter();

  private constructor() {}

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
      // Ignore cleanup errors
    }
    (TTSStateManager.instance as TTSStateManager | null) = null;
  }

  public clearPendingTimeouts(): void {
    this.cleanup();
  }

  public async initialize(): Promise<void> {
    if (this.initialized) return;
    if (this.initializationPromise) {
      await this.initializationPromise;
      return;
    }

    this.initializationPromise = (async () => {
      await this.loadSettings();
      ttsMediaSessionService.registerPlaybackStateHandler((state) => {
        this.handleNativeState(state);
      });
      ttsMediaSessionService.registerMediaButtonHandler(() => {
        void this.playPause();
      });
      this.initialized = true;
    })().finally(() => {
      this.initializationPromise = null;
    });

    await this.initializationPromise;
  }

  private async ensureInitialized() {
    if (this.initialized) return;
    await this.initialize();
  }

  private cleanup(): void {
    this.stateEmitter.cleanup();
    this.sessionPersistence.cleanup();
  }

  private async loadSettings() {
    const settings = await storageService.getTTSSettings();
    if (settings) {
      this._settings = settings;
    }
  }

  private emitStateChange(state: TTSState | null) {
    if (state) {
      this.stateEmitter.emitStateChange(state);
      return;
    }
    this.stateEmitter.emitStateChange({
      isSpeaking: false,
      isPaused: false,
      chunks: [],
      currentChunkIndex: 0,
      title: "Reading",
    });
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.commandQueue.then(operation);
    this.commandQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private handleNativeState(nativeState: NativeTtsPlaybackState) {
    const currentChunks = this.state?.chunks ?? [];
    const nextState: TTSState = {
      isSpeaking:
        nativeState.status === "playing" || nativeState.status === "buffering",
      isPaused: nativeState.isPaused || nativeState.status === "paused",
      chunks: currentChunks,
      currentChunkIndex: Math.max(0, nativeState.currentIndex ?? 0),
      title: nativeState.title || this.state?.title || "Reading",
    };

    if (nativeState.status === "stopped" && !nextState.isPaused) {
      nextState.isSpeaking = false;
    }

    this.state = nextState;
    this.emitStateChange(nextState);

    if (nativeState.status === "completed") {
      const completedKey = `${nativeState.storyId || ""}:${nativeState.chapterId || ""}:${nativeState.currentIndex}`;
      if (this.lastCompletedKey !== completedKey) {
        this.lastCompletedKey = completedKey;
        void this.onControllerFinish();
      }
      return;
    }

    if (nativeState.status === "stopped") {
      return;
    }

    if (nextState.isSpeaking || nextState.isPaused) {
      this.sessionPersistence.persistSession(nextState, this._settings);
    }
  }

  private async onControllerFinish() {
    await this.enqueue(async () => {
      this.sessionPersistence.setPlaybackContext(null);
      await this.sessionPersistence.clearSession();
      this.pendingOnFinishCallback?.();
    });
  }

  public getState(): TTSState | null {
    return this.state;
  }

  public getSettings(): TTSSettings {
    return this._settings;
  }

  public async updateSettings(newSettings: TTSSettings) {
    await this.ensureInitialized();
    this._settings = newSettings;
    await storageService.saveTTSSettings(newSettings);

    const state = this.getState();
    const context = this.sessionPersistence.getPlaybackContext();
    if ((state?.isSpeaking || state?.isPaused) && context) {
      this.sessionPersistence.persistSession(state, this._settings);
      await ttsMediaSessionService.startPlayback({
        units: state.chunks,
        title: state.title,
        storyId: context.storyId,
        chapterId: context.chapterId,
        startIndex: state.currentChunkIndex,
        pitch: newSettings.pitch,
        rate: newSettings.rate,
        voiceIdentifier: newSettings.voiceIdentifier,
      });
      if (state.isPaused) {
        await ttsMediaSessionService.pausePlayback();
      }
    }
  }

  public setOnFinishCallback(callback: (() => void) | null) {
    this.pendingOnFinishCallback = callback;
  }

  public async getPersistedSession(): Promise<TTSSession | null> {
    return this.sessionPersistence.loadSession();
  }

  public async clearPersistedSession(): Promise<void> {
    await this.sessionPersistence.clearSession();
  }

  public async start(request: TTSStartRequest): Promise<void>;
  public async start(chunks: string[], title?: string): Promise<void>;
  public async start(
    requestOrChunks: TTSStartRequest | string[],
    title: string = "Reading",
  ) {
    await this.ensureInitialized();

    const request = Array.isArray(requestOrChunks)
      ? {
          chunks: requestOrChunks,
          title,
          storyId: "",
          chapterId: "",
          chapterTitle: title,
        }
      : requestOrChunks;

    if (!request?.chunks?.length) return;
    if (!ttsMediaSessionService.isNativePlaybackAvailable()) {
      console.warn("[TTSStateManager] Native Android TTS playback is unavailable.");
      return;
    }

    await this.enqueue(async () => {
      const startIndex = Math.max(
        0,
        Math.min(request.startChunkIndex ?? 0, request.chunks.length - 1),
      );
      const startPaused = request.startPaused ?? false;

      this.sessionPersistence.setPlaybackContext({
        storyId: request.storyId,
        chapterId: request.chapterId,
        chapterTitle: request.chapterTitle,
      });

      this.state = {
        isSpeaking: true,
        isPaused: startPaused,
        chunks: request.chunks,
        currentChunkIndex: startIndex,
        title: request.title ?? "Reading",
      };
      this.lastCompletedKey = null;
      this.emitStateChange(this.state);
      this.sessionPersistence.persistSession(
        this.state,
        this._settings,
        !startPaused,
      );

      await ttsMediaSessionService.startPlayback({
        units: request.chunks,
        title: request.title ?? "Reading",
        storyId: request.storyId,
        chapterId: request.chapterId,
        startIndex,
        pitch: this._settings.pitch,
        rate: this._settings.rate,
        voiceIdentifier: this._settings.voiceIdentifier,
      });

      if (startPaused) {
        await ttsMediaSessionService.pausePlayback();
      }
    });
  }

  private async withStateSync(
    action: () => Promise<void>,
    wasPlayingOverride?: boolean,
  ): Promise<void> {
    await this.ensureInitialized();
    await this.enqueue(async () => {
      await action();
      const state = this.getState();
      if (!state) return;
      this.sessionPersistence.persistSession(
        state,
        this._settings,
        wasPlayingOverride,
      );
    });
  }

  public async stop() {
    await this.ensureInitialized();
    await this.enqueue(async () => {
      await ttsMediaSessionService.stopPlayback();
      this.state = null;
      this.sessionPersistence.setPlaybackContext(null);
      await this.sessionPersistence.clearSession();
      this.emitStateChange(null);
    });
  }

  public async pause() {
    await this.withStateSync(async () => {
      await ttsMediaSessionService.pausePlayback();
      if (this.state) {
        this.state = { ...this.state, isPaused: true, isSpeaking: false };
        this.emitStateChange(this.state);
      }
    }, false);
  }

  public async resume() {
    await this.withStateSync(async () => {
      await ttsMediaSessionService.resumePlayback();
      if (this.state) {
        this.state = { ...this.state, isPaused: false, isSpeaking: true };
        this.emitStateChange(this.state);
      }
    }, true);
  }

  public async playPause() {
    const state = this.getState();
    if (state?.isPaused || !state?.isSpeaking) {
      await this.resume();
      return;
    }
    await this.pause();
  }

  public async next() {
    await this.withStateSync(async () => {
      await ttsMediaSessionService.next();
      if (this.state) {
        this.state = {
          ...this.state,
          isPaused: false,
          isSpeaking: true,
          currentChunkIndex: Math.min(
            this.state.currentChunkIndex + 1,
            this.state.chunks.length - 1,
          ),
        };
        this.emitStateChange(this.state);
      }
    }, true);
  }

  public async previous() {
    await this.withStateSync(async () => {
      await ttsMediaSessionService.previous();
      if (this.state) {
        this.state = {
          ...this.state,
          isPaused: false,
          isSpeaking: true,
          currentChunkIndex: Math.max(0, this.state.currentChunkIndex - 1),
        };
        this.emitStateChange(this.state);
      }
    }, true);
  }

  public async seekToUnit(index: number) {
    await this.withStateSync(async () => {
      await ttsMediaSessionService.seekToUnit(index);
      if (this.state) {
        this.state = {
          ...this.state,
          isPaused: false,
          isSpeaking: true,
          currentChunkIndex: Math.max(
            0,
            Math.min(index, this.state.chunks.length - 1),
          ),
        };
        this.emitStateChange(this.state);
      }
    }, true);
  }

  public async recoverPlaybackFromSilentStop() {
    await this.ensureInitialized();
  }

  public async restoreForChapter(params: {
    chunks: string[];
    title: string;
    storyId: string;
    chapterId: string;
    chapterTitle: string;
  }): Promise<boolean> {
    await this.ensureInitialized();

    const existingState = this.getState();
    if (existingState?.isSpeaking || existingState?.isPaused) return false;

    const session = await this.sessionPersistence.loadSession();
    if (!session) return false;
    if (!session.storyId || !session.chapterId) return false;
    if (
      session.storyId !== params.storyId ||
      session.chapterId !== params.chapterId
    ) {
      return false;
    }
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
}

export const ttsStateManager = TTSStateManager.getInstance();
