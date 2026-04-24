import {
  addMediaButtonListener,
  addPlaybackStateListener,
  getNativeVoices,
  isAvailable,
  next,
  pausePlayback,
  playPause,
  previous,
  resumePlayback,
  seekToUnit,
  startPlayback,
  stopPlayback,
  type NativeTtsPlaybackState,
  type NativeTtsVoice,
  type TtsPlaybackPayload,
} from "tts-media-session";
import { isAndroidNative } from "../utils/platform";

export type MediaButtonHandler = (action: "playPause") => void;
export type NativeTtsStateHandler = (state: NativeTtsPlaybackState) => void;

class TtsMediaSessionService {
  private initialized = false;
  private mediaButtonListening = false;
  private playbackStateListening = false;

  constructor() {
    if (!isAndroidNative()) return;
    if (!isAvailable()) {
      console.warn("[TtsMediaSessionService] Native module not available.");
      return;
    }
    this.initialized = true;
  }

  public isNativePlaybackAvailable(): boolean {
    return this.initialized;
  }

  public registerMediaButtonHandler(handler: MediaButtonHandler) {
    if (!this.initialized || this.mediaButtonListening) return;
    this.mediaButtonListening = true;
    addMediaButtonListener((event) => {
      if (event?.action === "playPause") {
        handler("playPause");
      }
    });
  }

  public registerPlaybackStateHandler(handler: NativeTtsStateHandler) {
    if (!this.initialized || this.playbackStateListening) return;
    this.playbackStateListening = true;
    addPlaybackStateListener(handler);
  }

  public async startPlayback(payload: TtsPlaybackPayload) {
    if (!this.initialized) return;
    await startPlayback(payload);
  }

  public async pausePlayback() {
    if (!this.initialized) return;
    await pausePlayback();
  }

  public async resumePlayback() {
    if (!this.initialized) return;
    await resumePlayback();
  }

  public async playPause() {
    if (!this.initialized) return;
    await playPause();
  }

  public async next() {
    if (!this.initialized) return;
    await next();
  }

  public async previous() {
    if (!this.initialized) return;
    await previous();
  }

  public async seekToUnit(index: number) {
    if (!this.initialized) return;
    await seekToUnit(index);
  }

  public async stopPlayback() {
    if (!this.initialized) return;
    await stopPlayback();
  }

  public async getVoices(): Promise<NativeTtsVoice[]> {
    if (!this.initialized) return [];
    return getNativeVoices();
  }
}

export const ttsMediaSessionService = new TtsMediaSessionService();
export type { NativeTtsPlaybackState, NativeTtsVoice };
