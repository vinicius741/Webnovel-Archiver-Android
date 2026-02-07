import Constants from 'expo-constants';
import { ttsStateManager } from './TTSStateManager';
import { clearDownloadState, registerForegroundService } from './ForegroundServiceCoordinator';
import { loadNotifee, type NotifeeModule } from './NotifeeTypes';
import type { Event } from '@notifee/react-native/dist/types/Notification';

// Safely initialize background service to handle environments without Notifee (e.g. Expo Go)
const initializeBackgroundService = () => {
  if (Constants.executionEnvironment === 'storeClient') {
    console.log('[BackgroundService] Expo Go detected. Background events disabled.');
    return;
  }

  try {
    registerForegroundService();

    const notifee = loadNotifee();
    if (!notifee) {
      console.warn('[BackgroundService] Notifee native module not found. Background events will be ignored.');
      return;
    }

    notifee.default.onBackgroundEvent(async (event: Event) => {
      console.log(`[BackgroundService] Received event type: ${event.type}`);
      const actionId = event.detail?.pressAction?.id;

      if (event.type === notifee.EventType.ACTION_PRESS) {
        try {
          // Download Actions
          if (actionId === 'cancel') {
            console.log('[BackgroundService] Cancel action pressed, stopping download.');
            const { downloadManager } = require('./download/DownloadManager');
            await downloadManager.cancelAll();
            await clearDownloadState();
            await notifee.default.cancelNotification('foreground_service');
          }

          // TTS Actions - directly call the state manager
          else if (actionId === 'tts_play') {
            console.log('[BackgroundService] TTS play action pressed');
            await ttsStateManager.resume();
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
        } catch (error) {
          console.warn('[BackgroundService] Failed to handle background action:', error);
        }
      }
    });

    console.log('[BackgroundService] Notifee background event handler registered.');
  } catch (e) {
    console.warn('[BackgroundService] Notifee native module not found. Background events will be ignored.');
  }
};

initializeBackgroundService();

// Export something to ensure the file is not tree-shaked if imported
export const backgroundServiceInitializer = true;
