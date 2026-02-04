
import Constants from 'expo-constants';
import { ttsStateManager } from '../TTSStateManager';
import { registerForegroundService } from '../ForegroundServiceCoordinator';
import { EventType } from '@notifee/react-native';

// Mock dependencies BEFORE importing the service because it runs code on import.
jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare', // Default to bare to allow initialization
}));

const mockOnBackgroundEvent = jest.fn();
const mockCancelNotification = jest.fn();

jest.mock('@notifee/react-native', () => ({
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
    default: {
        onBackgroundEvent: mockOnBackgroundEvent,
        cancelNotification: mockCancelNotification,
    },
}));

jest.mock('../NotifeeTypes', () => ({
    loadNotifee: jest.fn(() => ({
        default: {
            onBackgroundEvent: mockOnBackgroundEvent,
            cancelNotification: mockCancelNotification,
        },
        AndroidImportance: {},
        AndroidColor: {},
        AndroidCategory: {},
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

jest.mock('../TTSStateManager'); // Auto-mock
jest.mock('../ForegroundServiceCoordinator', () => ({
    registerForegroundService: jest.fn(),
    clearDownloadState: jest.fn(),
}));
jest.mock('../download/DownloadManager', () => ({
    downloadManager: {
        cancelAll: jest.fn().mockResolvedValue(undefined),
    },
}));

describe('BackgroundService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
        // Reset Constants mock if needed, but it's hard to change executionEnvironment dynamically 
        // because the module is already loaded. 
        // We will rely on isolating tests or assuming the import happened.
    });

    it('should register background event handler on import', () => {
        // We need to require the module to trigger the side effect.
        // Since Jest caches modules, we might need to isolate modules or use require.
        jest.isolateModules(() => {
            require('../BackgroundService');
        });

        expect(mockOnBackgroundEvent).toHaveBeenCalled();
        expect(registerForegroundService).toHaveBeenCalled();
    });

    it('should handle cancel action', async () => {
        let handler: any;
        mockOnBackgroundEvent.mockImplementation((fn: any) => {
            handler = fn;
        });

        jest.isolateModules(() => {
            require('../BackgroundService');
        });

        expect(handler).toBeDefined();

        await handler({ type: EventType.ACTION_PRESS, detail: { pressAction: { id: 'cancel' } } });

        const { downloadManager } = require('../download/DownloadManager');
        const { clearDownloadState } = require('../ForegroundServiceCoordinator');
        expect(downloadManager.cancelAll).toHaveBeenCalled();
        expect(clearDownloadState).toHaveBeenCalled();
        // Check if notifee.cancelNotification was called? 
        // We need to access the mock.
        const notifee = require('@notifee/react-native').default;
        expect(notifee.cancelNotification).toHaveBeenCalledWith('foreground_service');
    });

    it('should handle TTS actions', async () => {
        let handler: any;
        mockOnBackgroundEvent.mockImplementation((fn: any) => {
            handler = fn;
        });

        jest.isolateModules(() => {
            require('../BackgroundService');
        });

        const actions = [
            { id: 'tts_play', method: 'resume' },
            { id: 'tts_pause', method: 'pause' },
            { id: 'tts_next', method: 'next' },
            { id: 'tts_prev', method: 'previous' },
            { id: 'tts_stop', method: 'stop' },
        ];

        for (const action of actions) {
            await handler({ type: EventType.ACTION_PRESS, detail: { pressAction: { id: action.id } } });
            // @ts-ignore
            expect(ttsStateManager[action.method]).toHaveBeenCalled();
        }
    });

    it('should not register handler in Expo Go (storeClient)', () => {
        // We need to change Constants mock for this test.
        // Since we are using isolateModules, we can re-mock or modify the mock object
        // if it's mutable and referenced inside the isolated module.
        // However, standard `jest.mock` is hoisted.

        // Strategy: Use `doMock` inside `isolateModules`.
        jest.isolateModules(() => {
            jest.doMock('expo-constants', () => ({
                executionEnvironment: 'storeClient',
            }));
            // Reset mock calls for this isolation
            mockOnBackgroundEvent.mockClear();

            require('../BackgroundService');

            expect(mockOnBackgroundEvent).not.toHaveBeenCalled();
        });
    });
});
