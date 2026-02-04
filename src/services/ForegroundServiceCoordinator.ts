import { Platform } from 'react-native';
import Constants from 'expo-constants';
import EventEmitter from 'events';
import { loadNotifee, type NotifeeModule } from './NotifeeTypes';
import type { NotificationAndroid } from '@notifee/react-native/dist/types/NotificationAndroid';

export interface DownloadState {
    current: number;
    total: number;
    message: string;
    title?: string;
}

export interface TtsState {
    title: string;
    body: string;
    isPlaying: boolean;
}

type ActiveReason = 'download' | 'tts';

const FOREGROUND_CHANNEL_ID = 'foreground_service';
const DOWNLOAD_COMPLETE_CHANNEL_ID = 'download_complete';
const FOREGROUND_NOTIFICATION_ID = 'foreground_service';

const emitter = new EventEmitter();

let notifee: NotifeeModule | null = null;
let initialized = false;
let initializing: Promise<void> | null = null;
let foregroundRegistered = false;

let downloadState: DownloadState | null = null;
let ttsState: TtsState | null = null;
const activeReasons = new Set<ActiveReason>();

const isAndroid = Platform.OS === 'android';
const isExpoGo = Constants.executionEnvironment === 'storeClient';

const ensureInitialized = async (): Promise<boolean> => {
    if (!isAndroid || isExpoGo) return false;
    if (initialized) return true;
    if (!initializing) {
        initializing = (async () => {
            try {
                notifee = loadNotifee();
                if (!notifee) {
                    return;
                }

                await notifee.default.createChannel({
                    id: FOREGROUND_CHANNEL_ID,
                    name: 'Foreground Service',
                    importance: notifee.AndroidImportance.LOW,
                });

                await notifee.default.createChannel({
                    id: DOWNLOAD_COMPLETE_CHANNEL_ID,
                    name: 'Download Complete',
                    importance: notifee.AndroidImportance.DEFAULT,
                });

                initialized = true;
            } catch (e) {
                console.warn('[ForegroundServiceCoordinator] Notifee init failed:', e);
            }
        })();
    }
    await initializing;
    return initialized;
};

const emitActiveChange = () => {
    emitter.emit('active-change');
};

const hasActive = () => activeReasons.size > 0;

const buildActions = () => {
    const downloadActive = activeReasons.has('download');
    const ttsActive = activeReasons.has('tts');

    if (downloadActive && !ttsActive) {
        return [
            { title: 'Cancel', pressAction: { id: 'cancel' } },
        ];
    }

    if (!downloadActive && ttsActive) {
        return [
            { title: 'Prev', pressAction: { id: 'tts_prev' } },
            {
                title: ttsState?.isPlaying ? 'Pause' : 'Play',
                pressAction: { id: ttsState?.isPlaying ? 'tts_pause' : 'tts_play' },
            },
            { title: 'Next', pressAction: { id: 'tts_next' } },
            { title: 'Stop', pressAction: { id: 'tts_stop' } },
        ];
    }

    if (downloadActive && ttsActive) {
        return [
            { title: 'Cancel', pressAction: { id: 'cancel' } },
            {
                title: ttsState?.isPlaying ? 'Pause' : 'Play',
                pressAction: { id: ttsState?.isPlaying ? 'tts_pause' : 'tts_play' },
            },
            { title: 'Stop', pressAction: { id: 'tts_stop' } },
        ];
    }

    return [];
};

const updateForegroundNotification = async () => {
    if (!await ensureInitialized()) return;
    if (!hasActive()) return;

    const downloadActive = activeReasons.has('download');
    const ttsActive = activeReasons.has('tts');

    let title = 'Working...';
    let body = '';

    if (downloadActive && !ttsActive && downloadState) {
        title = downloadState.title || 'Downloading...';
        body = downloadState.message;
    } else if (!downloadActive && ttsActive && ttsState) {
        title = ttsState.title;
        body = ttsState.body;
    } else if (downloadActive && ttsActive && downloadState && ttsState) {
        title = 'Downloading & Playing';
        body = `${downloadState.message} • ${ttsState.body}`;
    }

    const android: NotificationAndroid = {
        channelId: FOREGROUND_CHANNEL_ID,
        asForegroundService: true,
        ongoing: true,
        onlyAlertOnce: true,
        color: notifee?.AndroidColor.BLUE,
        category: notifee?.AndroidCategory.SERVICE,
        actions: buildActions(),
    };

    if (downloadActive && downloadState && downloadState.total > 0) {
        android.progress = {
            max: downloadState.total,
            current: Math.min(downloadState.current, downloadState.total),
            indeterminate: false,
        };
    }

    try {
        await notifee!.default.displayNotification({
            id: FOREGROUND_NOTIFICATION_ID,
            title,
            body,
            android,
        });
    } catch (e) {
        console.warn('[ForegroundServiceCoordinator] Failed to update notification:', e);
    }
};

const stopForegroundIfIdle = async () => {
    if (!await ensureInitialized()) return;
    if (hasActive()) return;
    try {
        await notifee!.default.stopForegroundService();
        await notifee!.default.cancelNotification(FOREGROUND_NOTIFICATION_ID);
    } catch (e) {
        console.warn('[ForegroundServiceCoordinator] Failed to stop foreground service:', e);
    }
};

export const registerForegroundService = async () => {
    if (!await ensureInitialized()) return;
    if (foregroundRegistered) return;
    foregroundRegistered = true;

    notifee!.default.registerForegroundService(async () => {
        try {
            const { downloadManager } = require('./download/DownloadManager');
            const { downloadQueue } = require('./download/DownloadQueue');
            await downloadManager.init();
            const stats = downloadQueue.getStats();
            if (stats.pending > 0 || stats.active > 0) {
                downloadManager.start();
            }
        } catch (e) {
            console.warn('[ForegroundServiceCoordinator] Failed to start download manager in FGS:', e);
        }

        await new Promise<void>((resolve) => {
            const check = () => {
                if (!hasActive()) {
                    emitter.off('active-change', check);
                    resolve();
                }
            };
            emitter.on('active-change', check);
            check();
        });
    });
};

export const setDownloadState = async (state: DownloadState) => {
    downloadState = state;
    activeReasons.add('download');
    emitActiveChange();
    await updateForegroundNotification();
};

export const clearDownloadState = async () => {
    downloadState = null;
    activeReasons.delete('download');
    emitActiveChange();
    await stopForegroundIfIdle();
};

export const setTtsState = async (state: TtsState) => {
    ttsState = state;
    activeReasons.add('tts');
    emitActiveChange();
    await updateForegroundNotification();
};

export const clearTtsState = async () => {
    ttsState = null;
    activeReasons.delete('tts');
    emitActiveChange();
    await stopForegroundIfIdle();
};

export const showDownloadCompletionNotification = async (title: string, body: string) => {
    if (!await ensureInitialized()) return;
    try {
        await notifee!.default.displayNotification({
            title,
            body,
            android: {
                channelId: DOWNLOAD_COMPLETE_CHANNEL_ID,
                smallIcon: 'ic_launcher',
                pressAction: { id: 'default' },
            },
        });
    } catch (e) {
        console.warn('[ForegroundServiceCoordinator] Failed to show completion notification:', e);
    }
};

export const requestPermissions = async () => {
    if (!await ensureInitialized()) return;
    try {
        await notifee!.default.requestPermission();
    } catch (e) {
        console.warn('[ForegroundServiceCoordinator] Failed to request permissions:', e);
    }
};

export const isDownloadActive = () => activeReasons.has('download');
export const isTtsActive = () => activeReasons.has('tts');
