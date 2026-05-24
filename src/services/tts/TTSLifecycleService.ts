class TTSLifecycleService {
  public start() {
    // Native Android TTS playback is owned by the foreground media service.
  }

  public stop() {
    // No JS watchdog is needed for native media playback.
  }
}

export const ttsLifecycleService = new TTSLifecycleService();
