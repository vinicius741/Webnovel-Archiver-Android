import { Platform, DeviceEventEmitter } from 'react-native';

export const TTS_EVENTS = {
    PLAY: 'tts-play',
    PAUSE: 'tts-pause',
    NEXT: 'tts-next',
    PREVIOUS: 'tts-prev',
    STOP: 'tts-stop'
};

class TTSNotificationService {
    private channelId = 'tts_channel_v1';
    private notifee: any = null;
    private androidImportance: any = null;
    private androidStyle: any = null;
    private initialized = false;

    constructor() {
        this.init();
    }

    private init() {
        try {
            const notifeeModule = require('@notifee/react-native');
            this.notifee = notifeeModule.default;
            this.androidImportance = notifeeModule.AndroidImportance;
            // Handle potentially missing properties safely
            this.androidStyle = notifeeModule.AndroidStyle || { MEDIA: 2 };

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
                name: 'Audio Player',
                importance: this.androidImportance.LOW, // Low importance for media controls usually
            });
        } catch (e) {
            console.warn('[TTSNotificationService] Failed to create channel:', e);
        }
    }

    async startService(title: string, body: string) {
        if (!this.initialized) return;
        this.displayNotification(true, title, body);
    }

    async updateNotification(isPlaying: boolean, title: string, body: string) {
        if (!this.initialized) return;
        this.displayNotification(isPlaying, title, body);
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
                    // Use standard actions for now if we can't fully construct media style object easily without more imports
                    // But actually Notifee supports pressAction.id which we will map in background service
                    actions: [
                        {
                            title: 'Prev',
                            pressAction: { id: 'tts_prev' },
                            icon: 'ic_skip_previous', // Assuming these exist or fallback
                        },
                        {
                            title: isPlaying ? 'Pause' : 'Play',
                            pressAction: { id: isPlaying ? 'tts_pause' : 'tts_play' },
                            icon: isPlaying ? 'ic_pause' : 'ic_play_arrow',
                        },
                        {
                            title: 'Next',
                            pressAction: { id: 'tts_next' },
                            icon: 'ic_skip_next',
                        },
                        {
                            title: 'Stop',
                            pressAction: { id: 'tts_stop' },

                        }
                    ],
                    // Attempt to use media style if likely valid
                    style: this.androidStyle ? {
                        type: this.androidStyle.MEDIA,
                        actions: [0, 1, 2], // Indices of actions to show in compact view
                        artwork: 'ic_launcher' // Fallback
                    } : undefined
                },
            });
        } catch (e) {
            console.warn('[TTSNotificationService] Failed to display notification:', e);
        }
    }
}

export const ttsNotificationService = new TTSNotificationService();
