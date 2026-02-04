jest.mock('react-native', () => ({
    Platform: {
        OS: 'android',
    },
}));

jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

import { AndroidImportance, AndroidColor, AndroidCategory, EventType } from '@notifee/react-native';

const mockNotifee = {
    createChannel: jest.fn().mockResolvedValue(undefined),
    displayNotification: jest.fn().mockImplementation(async () => {
        // Simulate async notification update to expose race conditions
        await Promise.resolve();
    }),
    stopForegroundService: jest.fn().mockResolvedValue(undefined),
    cancelNotification: jest.fn().mockResolvedValue(undefined),
    requestPermission: jest.fn().mockResolvedValue(undefined),
    registerForegroundService: jest.fn(),
};

jest.mock('@notifee/react-native', () => ({
    default: {
        createChannel: jest.fn().mockResolvedValue(undefined),
        displayNotification: jest.fn().mockImplementation(async () => {
            await Promise.resolve();
        }),
        stopForegroundService: jest.fn().mockResolvedValue(undefined),
        cancelNotification: jest.fn().mockResolvedValue(undefined),
        requestPermission: jest.fn().mockResolvedValue(undefined),
        registerForegroundService: jest.fn(),
    },
    AndroidImportance: {
        LOW: 'low' as const,
        DEFAULT: 'default' as const,
    },
    AndroidColor: {
        BLUE: 'blue' as const,
    },
    AndroidCategory: {
        SERVICE: 'service' as const,
    },
    EventType: {
        UNKNOWN: -1,
        DISMISSED: 0,
        PRESS: 1,
        ACTION_PRESS: 2,
        DELIVERED: 3,
        APP_BLOCKED: 4,
        CHANNEL_BLOCKED: 5,
        CHANNEL_GROUP_BLOCKED: 6,
        TRIGGER_NOTIFICATION_CREATED: 7,
        FG_ALREADY_EXIST: 8,
    },
}));

jest.mock('../NotifeeTypes', () => ({
    loadNotifee: jest.fn(() => ({
        default: mockNotifee,
        AndroidImportance: {
            LOW: 'low' as const,
            DEFAULT: 'default' as const,
        },
        AndroidColor: {
            BLUE: 'blue' as const,
        },
        AndroidCategory: {
            SERVICE: 'service' as const,
        },
        EventType: {
            UNKNOWN: -1,
            DISMISSED: 0,
            PRESS: 1,
            ACTION_PRESS: 2,
            DELIVERED: 3,
            APP_BLOCKED: 4,
            CHANNEL_BLOCKED: 5,
            CHANNEL_GROUP_BLOCKED: 6,
            TRIGGER_NOTIFICATION_CREATED: 7,
            FG_ALREADY_EXIST: 8,
        },
    })),
    clearNotifeeCache: jest.fn(),
}));

jest.mock('../download/DownloadManager', () => ({
    downloadManager: {
        init: jest.fn().mockResolvedValue(undefined),
        start: jest.fn(),
    },
}));

describe('ForegroundServiceCoordinator - Concurrent Download + TTS', () => {
    beforeEach(async () => {
        jest.resetModules();
        jest.clearAllMocks();

        // Trigger initialization by calling setDownloadState
        const { setDownloadState } = require('../ForegroundServiceCoordinator');
        await setDownloadState({ title: 'Init', message: 'Init', current: 0, total: 1 });
        mockNotifee.displayNotification.mockClear();
    });

    describe('Concurrent State Updates', () => {
        it('should handle simultaneous download and TTS state updates', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            // Fire both state updates simultaneously
            await Promise.all([
                setDownloadState({
                    title: 'Downloading Story 1',
                    message: 'Active: 2, Pending: 5',
                    current: 2,
                    total: 7,
                }),
                setTtsState({
                    title: 'Reading Another Story',
                    body: 'Chapter 5 / 20',
                    isPlaying: true,
                }),
            ]);

            // Should have updated notification with combined state
            expect(mockNotifee.displayNotification).toHaveBeenCalled();
            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            expect(lastCall.title).toBe('Downloading & Playing');
            expect(lastCall.body).toContain('Active: 2, Pending: 5');
            expect(lastCall.body).toContain('Chapter 5 / 20');
        });

        it('should handle rapid consecutive state updates', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            // Rapidly update states multiple times
            const promises = [];
            for (let i = 0; i < 5; i++) {
                promises.push(setDownloadState({
                    title: `Download step ${i}`,
                    message: `Active: ${i}, Pending: ${5 - i}`,
                    current: i,
                    total: 5,
                }));
                promises.push(setTtsState({
                    title: `Reading step ${i}`,
                    body: `Chunk ${i} / 10`,
                    isPlaying: i % 2 === 0,
                }));
            }

            await Promise.all(promises);

            // All updates should have been processed
            expect(mockNotifee.displayNotification).toHaveBeenCalledTimes(10);
        });

        it('should maintain correct active reasons during concurrent operations', async () => {
            const { setDownloadState, setTtsState, clearDownloadState, clearTtsState, isDownloadActive, isTtsActive } =
                require('../ForegroundServiceCoordinator');

            // Start both
            await Promise.all([
                setDownloadState({ title: 'Download', message: 'Starting...', current: 0, total: 10 }),
                setTtsState({ title: 'TTS', body: 'Playing', isPlaying: true }),
            ]);

            expect(isDownloadActive()).toBe(true);
            expect(isTtsActive()).toBe(true);

            // Clear download only
            await clearDownloadState();

            expect(isDownloadActive()).toBe(false);
            expect(isTtsActive()).toBe(true);

            // Clear TTS
            await clearTtsState();

            expect(isDownloadActive()).toBe(false);
            expect(isTtsActive()).toBe(false);
        });

        it('should show correct notification when transitioning from both active to single active', async () => {
            const { setDownloadState, setTtsState, clearDownloadState } = require('../ForegroundServiceCoordinator');

            // Start both
            await Promise.all([
                setDownloadState({ title: 'Download', message: 'Active: 1', current: 1, total: 5 }),
                setTtsState({ title: 'TTS', body: 'Chapter 1', isPlaying: true }),
            ]);

            // Clear download
            await clearDownloadState();

            // Should now show TTS-only notification
            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            expect(lastCall.title).toBe('TTS');
            expect(lastCall.body).toBe('Chapter 1');
        });
    });

    describe('Action Buttons with Both Systems Active', () => {
        it('should show combined actions when both download and TTS are active', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({
                    title: 'Downloading',
                    message: 'Active: 1, Pending: 3',
                    current: 1,
                    total: 4,
                }),
                setTtsState({
                    title: 'Reading',
                    body: 'Chapter 2 / 15',
                    isPlaying: true,
                }),
            ]);

            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            const actions = lastCall.android.actions;

            // Should have: Cancel, Pause (since playing), Stop
            expect(actions).toHaveLength(3);
            expect(actions).toContainEqual({ title: 'Cancel', pressAction: { id: 'cancel' } });
            expect(actions).toContainEqual({ title: 'Pause', pressAction: { id: 'tts_pause' } });
            expect(actions).toContainEqual({ title: 'Stop', pressAction: { id: 'tts_stop' } });
        });

        it('should show Play action when TTS is paused while downloading', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({
                    title: 'Downloading',
                    message: 'Active: 1, Pending: 3',
                    current: 1,
                    total: 4,
                }),
                setTtsState({
                    title: 'Reading',
                    body: 'Chapter 2 / 15 (Paused)',
                    isPlaying: false,
                }),
            ]);

            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            const actions = lastCall.android.actions;

            expect(actions).toContainEqual({ title: 'Play', pressAction: { id: 'tts_play' } });
            expect(actions).not.toContainEqual({ title: 'Pause', pressAction: { id: 'tts_pause' } });
        });

        it('should not include Next/Prev actions when both are active', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({ title: 'Download', message: '...', current: 1, total: 5 }),
                setTtsState({ title: 'Reading', body: 'Chapter 1', isPlaying: true }),
            ]);

            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            const actions = lastCall.android.actions;

            expect(actions).not.toContainEqual({ title: 'Next', pressAction: { id: 'tts_next' } });
            expect(actions).not.toContainEqual({ title: 'Prev', pressAction: { id: 'tts_prev' } });
        });
    });

    describe('Notification Progress with Both Systems Active', () => {
        it('should show download progress when both systems are active', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({
                    title: 'Download',
                    message: 'Active: 3',
                    current: 3,
                    total: 10,
                }),
                setTtsState({ title: 'TTS', body: 'Chapter 5', isPlaying: true }),
            ]);

            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            expect(lastCall.android.progress).toEqual({
                max: 10,
                current: 3,
                indeterminate: false,
            });
        });

        it('should not show progress when only TTS is active', async () => {
            const { setTtsState } = require('../ForegroundServiceCoordinator');

            await setTtsState({ title: 'TTS', body: 'Chapter 5', isPlaying: true });

            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            expect(lastCall.android.progress).toBeUndefined();
        });
    });

    describe('Foreground Service Lifecycle', () => {
        it('should not stop foreground service when only one system is cleared', async () => {
            const { setDownloadState, setTtsState, clearDownloadState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({ title: 'Download', message: '...', current: 1, total: 5 }),
                setTtsState({ title: 'TTS', body: 'Playing', isPlaying: true }),
            ]);

            mockNotifee.stopForegroundService.mockClear();
            mockNotifee.cancelNotification.mockClear();

            await clearDownloadState();

            expect(mockNotifee.stopForegroundService).not.toHaveBeenCalled();
            expect(mockNotifee.cancelNotification).not.toHaveBeenCalled();
        });

        it('should stop foreground service when both systems are cleared', async () => {
            const { setDownloadState, setTtsState, clearDownloadState, clearTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({ title: 'Download', message: '...', current: 1, total: 5 }),
                setTtsState({ title: 'TTS', body: 'Playing', isPlaying: true }),
            ]);

            mockNotifee.stopForegroundService.mockClear();
            mockNotifee.cancelNotification.mockClear();

            // Clear both
            await Promise.all([clearDownloadState(), clearTtsState()]);

            expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
            expect(mockNotifee.cancelNotification).toHaveBeenCalledWith('foreground_service');
        });

        it('should handle simultaneous clear operations safely', async () => {
            const { setDownloadState, setTtsState, clearDownloadState, clearTtsState } = require('../ForegroundServiceCoordinator');

            await Promise.all([
                setDownloadState({ title: 'Download', message: '...', current: 1, total: 5 }),
                setTtsState({ title: 'TTS', body: 'Playing', isPlaying: true }),
            ]);

            mockNotifee.stopForegroundService.mockClear();
            mockNotifee.cancelNotification.mockClear();

            // Clear both simultaneously
            await Promise.all([clearDownloadState(), clearTtsState()]);

            // Should only stop once, not multiple times
            expect(mockNotifee.stopForegroundService).toHaveBeenCalledTimes(1);
        });
    });

    describe('State Consistency Under Load', () => {
        it('should maintain notification content integrity during rapid updates', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            const updateSequence = [
                // Download starts
                setDownloadState({ title: 'Download', message: 'Starting...', current: 0, total: 10 }),
                // TTS starts
                setTtsState({ title: 'TTS', body: 'Chapter 1', isPlaying: true }),
                // Download progresses
                setDownloadState({ title: 'Download', message: 'Progress...', current: 5, total: 10 }),
                // TTS progresses
                setTtsState({ title: 'TTS', body: 'Chapter 3', isPlaying: true }),
                // Download completes
                setDownloadState({ title: 'Download', message: 'Finishing...', current: 10, total: 10 }),
            ];

            for (const update of updateSequence) {
                await update;
            }

            // Final state should have both systems reflected
            const lastCall = mockNotifee.displayNotification.mock.calls.at(-1)[0];
            expect(lastCall.title).toBe('Downloading & Playing');
            expect(lastCall.body).toContain('Finishing...');
            expect(lastCall.body).toContain('Chapter 3');
        });

        it('should handle interleaved updates correctly', async () => {
            const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

            // Interleave updates: D1, T1, D2, T2, D3, T3
            await setDownloadState({ title: 'D1', message: 'Step 1', current: 1, total: 3 });
            await setTtsState({ title: 'T1', body: 'Chunk 1', isPlaying: true });
            await setDownloadState({ title: 'D2', message: 'Step 2', current: 2, total: 3 });
            await setTtsState({ title: 'T2', body: 'Chunk 2', isPlaying: false });
            await setDownloadState({ title: 'D3', message: 'Step 3', current: 3, total: 3 });
            await setTtsState({ title: 'T3', body: 'Chunk 3', isPlaying: true });

            expect(mockNotifee.displayNotification).toHaveBeenCalledTimes(6);

            // Each update should be reflected in order
            const calls = mockNotifee.displayNotification.mock.calls;
            expect(calls[0][0].title).toBe('D1');
            expect(calls[1][0].title).toBe('Downloading & Playing');
            expect(calls[4][0].android.actions).toContainEqual({
                title: 'Play',
                pressAction: { id: 'tts_play' },
            });
            expect(calls[5][0].android.actions).toContainEqual({
                title: 'Pause',
                pressAction: { id: 'tts_pause' },
            });
        });
    });
});
