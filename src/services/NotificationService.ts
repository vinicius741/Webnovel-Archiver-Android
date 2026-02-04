import { Platform } from 'react-native';
import Constants from 'expo-constants';
import {
    clearDownloadState,
    requestPermissions,
    setDownloadState,
    showDownloadCompletionNotification,
} from './ForegroundServiceCoordinator';

class NotificationService {
    constructor() {
        if (Platform.OS !== 'android') return;
        if (Constants.executionEnvironment === 'storeClient') {
            console.log('[NotificationService] Expo Go detected. Notifications disabled.');
        }
    }

    async startForegroundService(title: string, body: string, max: number = 100, current: number = 0) {
        await setDownloadState({
            title,
            message: body,
            total: max,
            current,
        });
    }

    async updateProgress(current: number, total: number, message: string) {
        if (total <= 0) return;
        await setDownloadState({
            title: 'Downloading...',
            message,
            total,
            current,
        });
    }

    async stopForegroundService() {
        await clearDownloadState();
    }

    async showCompletionNotification(title: string, body: string) {
        await showDownloadCompletionNotification(title, body);
    }

    async requestPermissions() {
        await requestPermissions();
    }
}
export const notificationService = new NotificationService();
