import { Platform } from 'react-native';
import Constants from 'expo-constants';
import { clearTtsState, setTtsState } from './ForegroundServiceCoordinator';

/**
 * Service responsible for displaying the TTS media notification.
 * 
 * This service ONLY handles the notification display - all TTS state
 * and control logic lives in TTSStateManager.
 */
class TTSNotificationService {
    private initialized = false;

    constructor() {
        this.init();
    }

    private init() {
        if (Platform.OS !== 'android') return;

        // Skip initialization in Expo Go
        if (Constants.executionEnvironment === 'storeClient') {
            console.log('[TTSNotificationService] Expo Go detected. Notifications disabled.');
            return;
        }
        this.initialized = true;
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
