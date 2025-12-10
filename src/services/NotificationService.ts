import { Platform } from 'react-native';

class NotificationService {
    private channelId = 'download_channel';
    private notifee: any = null;
    private androidImportance: any = null;
    private androidColor: any = null;

    constructor() {
        this.init();
    }

    private init() {
        try {
            // Lazy load the module to prevent crashes if the native module isn't linked (e.g. Expo Go)
            const notifeeModule = require('@notifee/react-native');
            this.notifee = notifeeModule.default;
            this.androidImportance = notifeeModule.AndroidImportance;
            this.androidColor = notifeeModule.AndroidColor;

            this.createChannel();
        } catch (e) {
            console.warn('[NotificationService] Notifee native module not found. Notifications will be disabled.');
        }
    }

    private async createChannel() {
        if (!this.notifee) return;

        try {
            await this.notifee.createChannel({
                id: this.channelId,
                name: 'Download Service',
                importance: this.androidImportance.LOW,
            });

            await this.notifee.createChannel({
                id: 'download_complete',
                name: 'Download Complete',
                importance: this.androidImportance.MEDIUM,
            });
        } catch (e) {
            console.warn('[NotificationService] Failed to create channel:', e);
        }
    }

    async startForegroundService(title: string, body: string) {
        if (!this.notifee) return;

        try {
            await this.notifee.displayNotification({
                id: 'download_progress',
                title: title,
                body: body,
                android: {
                    channelId: this.channelId,
                    asForegroundService: true,
                    color: this.androidColor?.BLUE,
                    ongoing: true,
                    progress: {
                        max: 100,
                        current: 0,
                        indeterminate: true,
                    },
                    actions: [
                        {
                            title: 'Cancel',
                            pressAction: {
                                id: 'cancel',
                            },
                        },
                    ],
                },
            });
        } catch (e) {
            console.warn('[NotificationService] Failed to start foreground service:', e);
        }
    }

    async updateProgress(current: number, total: number, message: string) {
        if (!this.notifee || total <= 0) return;

        try {
            await this.notifee.displayNotification({
                id: 'download_progress',
                title: 'Downloading...',
                body: message,
                android: {
                    channelId: this.channelId,
                    asForegroundService: true,
                    onlyAlertOnce: true,
                    progress: {
                        max: total,
                        current: current,
                    },
                },
            });
        } catch (e) {
            console.warn('[NotificationService] Failed to update progress:', e);
        }
    }

    async stopForegroundService() {
        if (!this.notifee) return;
        try {
            await this.notifee.stopForegroundService();
        } catch (e) {
            console.warn('[NotificationService] Failed to stop foreground service:', e);
        }
    }

    async showCompletionNotification(title: string, body: string) {
        if (!this.notifee) return;

        try {
            await this.stopForegroundService();
            await this.notifee.cancelNotification('download_progress');

            await this.notifee.displayNotification({
                title: title,
                body: body,
                android: {
                    channelId: 'download_complete',
                    smallIcon: 'ic_launcher',
                    pressAction: {
                        id: 'default',
                    },
                },
            });
        } catch (e) {
            console.warn('[NotificationService] Failed to show completion notification:', e);
        }
    }

    async requestPermissions() {
        if (!this.notifee) return;
        try {
            await this.notifee.requestPermission();
        } catch (e) {
            console.warn('[NotificationService] Failed to request permissions:', e);
        }
    }
}
export const notificationService = new NotificationService();
