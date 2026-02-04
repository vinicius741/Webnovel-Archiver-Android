import Constants from 'expo-constants';
import { ttsStateManager } from './TTSStateManager';
import { clearDownloadState, registerForegroundService } from './ForegroundServiceCoordinator';

// Safely initialize background service to handle environments without Notifee (e.g. Expo Go)
const initializeBackgroundService = () => {
  if (Constants.executionEnvironment === 'storeClient') {
    console.log('[BackgroundService] Expo Go detected. Background events disabled.');
    return;
  }

  try {
    registerForegroundService();

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
          const { downloadManager } = require('./download/DownloadManager');
          await downloadManager.cancelAll();
          await clearDownloadState();
          await notifee.cancelNotification('foreground_service');
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
};

initializeBackgroundService();

// Export something to ensure the file is not tree-shaked if imported
export const backgroundServiceInitializer = true;
