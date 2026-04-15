import { TTSSettings, TTSSession } from "../types";
import { storageService } from "./StorageService";
import {
  TTSPlaybackController,
  TTSPlaybackConfig,
  TTSState,
  TTSStartOptions,
} from "./tts/TTSPlaybackController";
import { ttsMediaSessionService } from "./TtsMediaSessionService";
import { TTSSessionPersistence } from "./tts/TTSSessionPersistence";
import { TTSStateEmitter } from "./tts/TTSStateEmitter";

export type { TTSState } from "./tts/TTSPlaybackController";

export const TTS_STATE_EVENTS = {
  STATE_CHANGED: "tts-state-changed",
};

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
  private initialized = false;
  private initializationPromise: Promise<void> | null = null;
  private pendingOnFinishCallback: (() => void) | null = null;
  private commandQueue: Promise<void> = Promise.resolve();

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
      // Ignore errors during cleanup (e.g., if already torn down)
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
    try {
      this.stateEmitter.cleanup();
      this.sessionPersistence.cleanup();
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
    this.stateEmitter.emitStateChange(state);
  }

  private enqueue<T>(operation: () => Promise<T>): Promise<T> {
    const result = this.commandQueue.then(operation);
    this.commandQueue = result.then(
      () => undefined,
      () => undefined,
    );
    return result;
  }

  private buildPlaybackBody(state: TTSState): string {
    if (state.isPaused) {
      return `Paused: Chunk ${state.currentChunkIndex + 1}`;
    }
    return `Reading chunk ${state.currentChunkIndex + 1} / ${state.chunks.length}`;
  }

  private buildMediaPayload(state: TTSState, isPlayingOverride?: boolean) {
    const context = this.sessionPersistence.getPlaybackContext();
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
      this.sessionPersistence.setPlaybackContext(null);
      await this.sessionPersistence.clearSession();
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
    await this.ensureInitialized();
    this._settings = newSettings;
    await storageService.saveTTSSettings(newSettings);
    this.controller?.updateSettings(newSettings);
    const state = this.getState();
    if (state?.isSpeaking || state?.isPaused) {
      this.sessionPersistence.persistSession(state, this._settings);
    }
  }

  public setOnFinishCallback(callback: (() => void) | null) {
    this.pendingOnFinishCallback = callback;
    this.controller?.setOnFinishCallback(callback);
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

    if (!request || !request.chunks || request.chunks.length === 0) return;

    await this.enqueue(async () => {
      if (!this.controller) {
        this.initializeController(request.chunks, request.title ?? "Reading");
      }

      this.sessionPersistence.setPlaybackContext({
        storyId: request.storyId,
        chapterId: request.chapterId,
        chapterTitle: request.chapterTitle,
      });

      this.controller?.start(request.chunks, request.title ?? "Reading", {
        startChunkIndex: request.startChunkIndex,
        startPaused: request.startPaused,
      });

      const state = this.getState();
      if (!state) return;

      this.sessionPersistence.persistSession(state, this._settings);
      await ttsMediaSessionService.startSession(this.buildMediaPayload(state));
    });
  }

  private async withStateSync(
    action: () => void | Promise<void>,
    wasPlayingOverride?: boolean,
  ): Promise<void> {
    await this.enqueue(async () => {
      await action();
      const state = this.getState();
      if (!state) return;
      this.sessionPersistence.persistSession(state, this._settings, wasPlayingOverride);
      await this.updateMediaSession();
    });
  }

  public async stop() {
    await this.ensureInitialized();
    await this.enqueue(async () => {
      await this.controller?.stop();
      this.sessionPersistence.setPlaybackContext(null);
      await this.sessionPersistence.clearSession();
      await ttsMediaSessionService.stopSession();
    });
  }

  public async pause() {
    await this.ensureInitialized();
    await this.withStateSync(() => this.controller?.pause(), false);
  }

  public async resume() {
    await this.ensureInitialized();
    await this.withStateSync(() => this.controller?.resume(), true);
  }

  public async playPause() {
    await this.ensureInitialized();
    await this.withStateSync(() => this.controller?.playPause());
  }

  public async next() {
    await this.ensureInitialized();
    await this.withStateSync(() => this.controller?.next(), true);
  }

  public async previous() {
    await this.ensureInitialized();
    await this.withStateSync(() => this.controller?.previous(), true);
  }

  public async recoverPlaybackFromSilentStop() {
    await this.ensureInitialized();
    await this.enqueue(async () => {
      const state = this.getState();
      if (!state || !state.isSpeaking || state.isPaused) return;
      await this.controller?.restartCurrentChunk();
      const latest = this.getState();
      if (!latest) return;
      this.sessionPersistence.persistSession(latest, this._settings, true);
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
    await this.ensureInitialized();

    const existingState = this.getState();
    if (existingState?.isSpeaking || existingState?.isPaused) return false;

    const session = await this.sessionPersistence.loadSession();
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
        this.sessionPersistence.persistSession(updatedState, this._settings);
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
