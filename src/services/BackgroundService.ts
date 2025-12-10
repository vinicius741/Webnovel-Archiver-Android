import { notificationService } from './NotificationService';

// Safely initialize background service to handle environments without Notifee (e.g. Expo Go)
try {
  const notifeeModule = require('@notifee/react-native');
  const notifee = notifeeModule.default;
  const EventType = notifeeModule.EventType;

  notifee.onBackgroundEvent(async ({ type, detail }: any) => {
    console.log(`[BackgroundService] Received event type: ${type}`);
    if (type === EventType.ACTION_PRESS && detail.pressAction?.id === 'cancel') {
      console.log('[BackgroundService] Cancel action pressed, stopping download.');
      // Implement actual cancellation logic in your DownloadService if possible
      // This will stop the Notifee foreground service and cancel the progress notification
      await notificationService.stopForegroundService();
      await notifee.cancelNotification('download_progress');
      // If you need to stop the download process itself, you'd need a way to signal it.
      // For example, by having a `cancelDownload` method in DownloadService.
    }
    // Add other background event handlers as needed
  });

  console.log('[BackgroundService] Notifee background event handler registered.');
} catch (e) {
  console.warn('[BackgroundService] Notifee native module not found. Background events will be ignored.');
}

// Export something to ensure the file is not tree-shaked if imported
export const backgroundServiceInitializer = true;