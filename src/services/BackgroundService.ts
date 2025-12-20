import { notificationService } from './NotificationService';

// Safely initialize background service to handle environments without Notifee (e.g. Expo Go)
try {
  const notifeeModule = require('@notifee/react-native');
  const notifee = notifeeModule.default;
  const EventType = notifeeModule.EventType;

  notifee.onBackgroundEvent(async ({ type, detail }: any) => {
    console.log(`[BackgroundService] Received event type: ${type}`);
    const actionId = detail.pressAction?.id;

    if (type === EventType.ACTION_PRESS) {
      // Download Actions
      if (actionId === 'cancel') {
        console.log('[BackgroundService] Cancel action pressed, stopping download.');
        await notificationService.stopForegroundService();
        await notifee.cancelNotification('download_progress');
      }

      // TTS Actions
      // We probably need to simply emit these to the JS side if the app is effectively awake, 
      // or handle them via standard mechanism. Since useTTS hook will listen to DeviceEventEmitter,
      // we can relay them. But be aware: if the app is KILLED, the hook isn't running.
      // For a foreground service, the app process usually stays alive.
      else if (actionId === 'tts_play') {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit('tts-play');
      }
      else if (actionId === 'tts_pause') {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit('tts-pause');
      }
      else if (actionId === 'tts_next') {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit('tts-next');
      }
      else if (actionId === 'tts_prev') {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit('tts-prev');
      }
      else if (actionId === 'tts_stop') {
        const { DeviceEventEmitter } = require('react-native');
        DeviceEventEmitter.emit('tts-stop');
        // Also forcefully stop service in case JS logic fails
        await notifee.stopForegroundService();
        await notifee.cancelNotification('tts_service');
      }
    }
  });

  console.log('[BackgroundService] Notifee background event handler registered.');
} catch (e) {
  console.warn('[BackgroundService] Notifee native module not found. Background events will be ignored.');
}

// Export something to ensure the file is not tree-shaked if imported
export const backgroundServiceInitializer = true;