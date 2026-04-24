import { TTSSettings, TTSSession } from "../../types";
import { storageService } from "../StorageService";
import type { TTSState } from "../TTSStateManager";

const TTS_SESSION_VERSION = 1;
const STORAGE_WRITE_THROTTLE_MS = 500;

interface PlaybackContext {
  storyId: string;
  chapterId: string;
  chapterTitle: string;
}

export class TTSSessionPersistence {
  private playbackContext: PlaybackContext | null = null;
  private pendingStorageWrite: {
    state: TTSState;
    settings: TTSSettings;
    wasPlayingOverride?: boolean;
  } | null = null;
  private storageWriteTimeoutId: ReturnType<typeof setTimeout> | null = null;

  setPlaybackContext(context: PlaybackContext | null): void {
    this.playbackContext = context;
  }

  getPlaybackContext(): PlaybackContext | null {
    return this.playbackContext;
  }

  private buildSession(
    state: TTSState,
    settings: TTSSettings,
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
      chunkSize: settings.chunkSize,
      voiceIdentifier: settings.voiceIdentifier,
      rate: settings.rate,
      pitch: settings.pitch,
      updatedAt: Date.now(),
      sessionVersion: TTS_SESSION_VERSION,
    };
  }

  persistSession(
    state: TTSState,
    settings: TTSSettings,
    wasPlayingOverride?: boolean,
  ): void {
    const session = this.buildSession(state, settings, wasPlayingOverride);
    if (!session) return;

    this.pendingStorageWrite = { state, settings, wasPlayingOverride };

    if (this.storageWriteTimeoutId) {
      return;
    }

    this.storageWriteTimeoutId = setTimeout(() => {
      this.storageWriteTimeoutId = null;
      if (this.pendingStorageWrite) {
        const { state: pendingState, settings: pendingSettings, wasPlayingOverride: pendingOverride } =
          this.pendingStorageWrite;
        this.pendingStorageWrite = null;
        const sessionToSave = this.buildSession(
          pendingState,
          pendingSettings,
          pendingOverride,
        );
        if (sessionToSave) {
          storageService.saveTTSSession(sessionToSave).catch((err) => {
            console.error("[TTSSessionPersistence] Failed to save session", err);
          });
        }
      }
    }, STORAGE_WRITE_THROTTLE_MS);
  }

  async loadSession(): Promise<TTSSession | null> {
    return storageService.getTTSSession();
  }

  async clearSession(): Promise<void> {
    await storageService.clearTTSSession();
  }

  cleanup(): void {
    try {
      if (this.storageWriteTimeoutId) {
        clearTimeout(this.storageWriteTimeoutId);
        this.storageWriteTimeoutId = null;
      }
    } catch {
      // Ignore cleanup errors
    }
  }
}
