import Constants from 'expo-constants';
import { Platform } from 'react-native';
import {
  addMediaButtonListener,
  isAvailable,
  startSession,
  stopSession,
  updateSession,
  type TtsMediaSessionPayload,
} from 'tts-media-session';

export type MediaButtonHandler = (action: 'playPause') => void;

class TtsMediaSessionService {
  private initialized = false;
  private listening = false;
  private started = false;

  constructor() {
    this.init();
  }

  private init() {
    if (Platform.OS !== 'android') return;
    if (Constants.executionEnvironment === 'storeClient') {
      console.log('[TtsMediaSessionService] Expo Go detected. Media session disabled.');
      return;
    }
    if (!isAvailable()) {
      console.warn('[TtsMediaSessionService] Native module not available.');
      return;
    }
    this.initialized = true;
  }

  public registerMediaButtonHandler(handler: MediaButtonHandler) {
    if (!this.initialized || this.listening) return;
    this.listening = true;
    addMediaButtonListener((event) => {
      if (event?.action === 'playPause') {
        handler('playPause');
      }
    });
  }

  public async startSession(payload: TtsMediaSessionPayload) {
    if (!this.initialized) return;
    this.started = true;
    await startSession(payload);
  }

  public async updateSession(payload: TtsMediaSessionPayload) {
    if (!this.initialized) return;
    if (!this.started) {
      await this.startSession(payload);
      return;
    }
    await updateSession(payload);
  }

  public async stopSession() {
    if (!this.initialized) return;
    this.started = false;
    await stopSession();
  }
}

export const ttsMediaSessionService = new TtsMediaSessionService();
