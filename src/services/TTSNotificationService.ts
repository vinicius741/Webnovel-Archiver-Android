import { Platform } from 'react-native';

/**
 * Service responsible for displaying the TTS media notification.
 * 
 * This service ONLY handles the notification display - all TTS state
 * and control logic lives in TTSStateManager.
 */
class TTSNotificationService {
    private channelId = 'tts_channel_v2';
    private notifee: any = null;
    private androidImportance: any = null;
    private androidCategory: any = null;
    private initialized = false;

    constructor() {
        this.init();
    }

    private init() {
        if (Platform.OS !== 'android') return;

        try {
            const notifeeModule = require('@notifee/react-native');
            this.notifee = notifeeModule.default;
            this.androidImportance = notifeeModule.AndroidImportance;
            this.androidCategory = notifeeModule.AndroidCategory;

            this.createChannel();
            this.initialized = true;
        } catch (e) {
            console.warn('[TTSNotificationService] Notifee native module not found.');
        }
    }

    private async createChannel() {
        if (!this.notifee) return;

        try {
            await this.notifee.createChannel({
                id: this.channelId,
                name: 'TTS Audio Player',
                importance: this.androidImportance.LOW,
            });
        } catch (e) {
            console.warn('[TTSNotificationService] Failed to create channel:', e);
        }
    }

    async startService(title: string, body: string) {
        if (!this.initialized) return;
        await this.displayNotification(true, title, body);
    }

    async updateNotification(isPlaying: boolean, title: string, body: string) {
        if (!this.initialized) return;
        await this.displayNotification(isPlaying, title, body);
    }

    async stopService() {
        if (!this.initialized) return;
        try {
            await this.notifee.stopForegroundService();
            await this.notifee.cancelNotification('tts_service');
        } catch (e) {
            console.warn('[TTSNotificationService] Failed to stop service:', e);
        }
    }

    private async displayNotification(isPlaying: boolean, title: string, body: string) {
        if (!this.notifee) return;

        try {
            await this.notifee.displayNotification({
                id: 'tts_service',
                title: title,
                body: body,
                android: {
                    channelId: this.channelId,
                    asForegroundService: true,
                    ongoing: true,
                    category: this.androidCategory?.SERVICE,
                    actions: [
                        {
                            title: 'Prev',
                            pressAction: { id: 'tts_prev' },
                        },
                        {
                            title: isPlaying ? 'Pause' : 'Play',
                            pressAction: { id: isPlaying ? 'tts_pause' : 'tts_play' },
                        },
                        {
                            title: 'Next',
                            pressAction: { id: 'tts_next' },
                        },
                        {
                            title: 'Stop',
                            pressAction: { id: 'tts_stop' },
                        },
                    ],
                },
            });
        } catch (e) {
            console.warn('[TTSNotificationService] Failed to display notification:', e);
        }
    }
}

export const ttsNotificationService = new TTSNotificationService();
