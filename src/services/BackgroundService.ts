import { notificationService } from './NotificationService';
import { ttsStateManager } from './TTSStateManager';

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

      // TTS Actions - directly call the state manager
      else if (actionId === 'tts_play') {
        console.log('[BackgroundService] TTS play action pressed');
        ttsStateManager.resume();
      }
      else if (actionId === 'tts_pause') {
        console.log('[BackgroundService] TTS pause action pressed');
        await ttsStateManager.pause();
      }
      else if (actionId === 'tts_next') {
        console.log('[BackgroundService] TTS next action pressed');
        await ttsStateManager.next();
      }
      else if (actionId === 'tts_prev') {
        console.log('[BackgroundService] TTS prev action pressed');
        await ttsStateManager.previous();
      }
      else if (actionId === 'tts_stop') {
        console.log('[BackgroundService] TTS stop action pressed');
        await ttsStateManager.stop();
      }
    }
  });

  console.log('[BackgroundService] Notifee background event handler registered.');
} catch (e) {
  console.warn('[BackgroundService] Notifee native module not found. Background events will be ignored.');
}

// Export something to ensure the file is not tree-shaked if imported
export const backgroundServiceInitializer = true;