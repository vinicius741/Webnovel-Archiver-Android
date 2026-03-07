import { isAndroidNative } from "../utils/platform";
import { clearTtsState, setTtsState } from "./ForegroundServiceCoordinator";

/**
 * Service responsible for displaying the TTS media notification.
 *
 * This service ONLY handles the notification display - all TTS state
 * and control logic lives in TTSStateManager.
 */
class TTSNotificationService {
  private initialized = false;

  constructor() {
    this.initialized = isAndroidNative();
  }

  async startService(title: string, body: string) {
    if (!this.initialized) return;
    await setTtsState({ title, body, isPlaying: true });
  }

  async updateNotification(isPlaying: boolean, title: string, body: string) {
    if (!this.initialized) return;
    await setTtsState({ title, body, isPlaying });
  }

  async stopService() {
    if (!this.initialized) return;
    await clearTtsState();
  }
}

export const ttsNotificationService = new TTSNotificationService();
