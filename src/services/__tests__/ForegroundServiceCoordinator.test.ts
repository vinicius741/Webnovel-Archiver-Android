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
    displayNotification: jest.fn().mockResolvedValue(undefined),
    stopForegroundService: jest.fn().mockResolvedValue(undefined),
    cancelNotification: jest.fn().mockResolvedValue(undefined),
    requestPermission: jest.fn().mockResolvedValue(undefined),
    registerForegroundService: jest.fn(),
};

// Keep track of the loadNotifee mock function
let mockLoadNotifee: jest.Mock;

jest.mock('@notifee/react-native', () => ({
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

describe('ForegroundServiceCoordinator', () => {
    beforeEach(() => {
        jest.resetModules();
        jest.clearAllMocks();
    });

    it('should display foreground notification when download state is set', async () => {
        const { setDownloadState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 2',
            current: 1,
            total: 3,
        });

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'foreground_service',
                title: 'Downloading...',
                body: 'Active: 1, Pending: 2',
                android: expect.objectContaining({
                    asForegroundService: true,
                    progress: { max: 3, current: 1, indeterminate: false },
                }),
            })
        );
    });

    it('should stop foreground service when download state is cleared and no other work', async () => {
        const { setDownloadState, clearDownloadState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 0',
            current: 1,
            total: 1,
        });

        await clearDownloadState();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
        expect(mockNotifee.cancelNotification).toHaveBeenCalledWith('foreground_service');
    });

    it('should use a single notification id for download and TTS', async () => {
        const { setDownloadState, setTtsState } = require('../ForegroundServiceCoordinator');

        await setDownloadState({
            title: 'Downloading...',
            message: 'Active: 1, Pending: 0',
            current: 1,
            total: 1,
        });

        await setTtsState({
            title: 'Reading',
            body: 'Reading chunk 1 / 5',
            isPlaying: true,
        });

        const calls = mockNotifee.displayNotification.mock.calls;
        expect(calls.length).toBeGreaterThan(1);
        for (const call of calls) {
            expect(call[0].id).toBe('foreground_service');
        }
    });

    it('should register foreground service handler', async () => {
        const { registerForegroundService } = require('../ForegroundServiceCoordinator');

        await registerForegroundService();

        expect(mockNotifee.registerForegroundService).toHaveBeenCalled();
    });
});
