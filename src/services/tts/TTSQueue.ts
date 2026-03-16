import * as Speech from "expo-speech";
import type { TTSSettings } from "../../types";

export interface TTSQueueConfig {
  onChunkStart: (index: number) => void;
  onChunkComplete: (index: number) => void;
  onError: (error: unknown) => void;
}

/**
 * TTSQueue manages the sequential playback of text chunks.
 *
 * Memory leak prevention:
 * - Uses token-based invalidation to ignore stale callbacks
 * - Explicitly stops speech before playing new chunks
 * - Clears callback references after completion
 */
export class TTSQueue {
  private chunks: string[] = [];
  private settings: TTSSettings;
  private config: TTSQueueConfig;
  private speakToken: number = 0;
  private activeChunkIndex: number | null = null;
  private isStopping: boolean = false;
  private stopPromise: Promise<void> | null = null;

  constructor(chunks: string[], settings: TTSSettings, config: TTSQueueConfig) {
    this.chunks = chunks;
    this.settings = settings;
    this.config = config;
    this.speakToken = 0;
  }

  public updateSettings(newSettings: TTSSettings): void {
    this.settings = newSettings;
  }

  public updateChunks(newChunks: string[]): void {
    this.chunks = newChunks;
    this.invalidate();
  }

  /**
   * Play a chunk with proper cleanup.
   * Stops any current speech before starting new speech to prevent
   * callback accumulation in the expo-speech native layer.
   *
   * Note: This method resolves when speech *starts*, not when it completes.
   * Completion is signaled via the onChunkComplete callback in config.
   */
  public async playChunk(index: number): Promise<void> {
    if (index < 0 || index >= this.chunks.length) return;

    if (this.activeChunkIndex !== null || this.isStopping) {
      await this.stopCurrentSpeech();
    }

    const currentToken = ++this.speakToken;
    const chunk = this.chunks[index];
    this.activeChunkIndex = index;

    // Create callbacks that check token validity
    const callbacks = {
      onStart: () => {
        if (this.speakToken !== currentToken) return;
        this.config.onChunkStart(index);
      },
      onDone: () => {
        if (this.speakToken !== currentToken) return;
        this.activeChunkIndex = null;
        this.config.onChunkComplete(index);
      },
      onError: (error: unknown) => {
        if (this.speakToken !== currentToken) return;
        this.activeChunkIndex = null;
        this.config.onError(error);
      },
    };

    Speech.speak(chunk, {
      pitch: this.settings.pitch,
      rate: this.settings.rate,
      voice: this.settings.voiceIdentifier,
      onStart: callbacks.onStart,
      onDone: callbacks.onDone,
      onError: callbacks.onError,
    });
  }

  /**
   * Stop current speech and wait for it to complete.
   * This ensures native callbacks are properly cleaned up.
   * If a stop is already in progress, returns the existing promise.
   */
  private async stopCurrentSpeech(): Promise<void> {
    // If already stopping, wait for the existing stop to complete
    if (this.isStopping && this.stopPromise) {
      return this.stopPromise;
    }

    this.isStopping = true;
    this.stopPromise = (async () => {
      try {
        // Invalidate current token first
        this.speakToken++;
        // Stop speech and wait for native cleanup
        await Speech.stop();
      } finally {
        this.isStopping = false;
        this.stopPromise = null;
      }
    })();

    return this.stopPromise;
  }

  public async stop(): Promise<void> {
    this.invalidate();
    await this.stopCurrentSpeech();
  }

  public invalidate(): void {
    this.speakToken++;
    this.activeChunkIndex = null;
  }

  public getActiveChunkIndex(): number | null {
    return this.activeChunkIndex;
  }

  public getLength(): number {
    return this.chunks.length;
  }
}
