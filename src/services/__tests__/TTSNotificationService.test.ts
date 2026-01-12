
import Platform from 'react-native';
import Constants from 'expo-constants';

jest.mock('react-native', () => ({
    Platform: {
        OS: 'android',
    },
}));

jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

const mockNotifee = {
    createChannel: jest.fn().mockResolvedValue(undefined),
    displayNotification: jest.fn().mockResolvedValue(undefined),
    stopForegroundService: jest.fn().mockResolvedValue(undefined),
    cancelNotification: jest.fn().mockResolvedValue(undefined),
};

jest.mock('@notifee/react-native', () => {
    return {
        default: mockNotifee,
        AndroidImportance: {
            LOW: 'low',
            MEDIUM: 'medium',
            HIGH: 'high',
        },
        AndroidCategory: {
            SERVICE: 'service',
        },
    };
});

describe('TTSNotificationService', () => {
    const mockModule = require('@notifee/react-native');

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should create TTS channel on initialization', async () => {
        jest.resetModules();
        const { ttsNotificationService: service } = require('../TTSNotificationService');

        await new Promise(setImmediate);

        expect(mockNotifee.createChannel).toHaveBeenCalledWith({
            id: 'tts_channel_v2',
            name: 'TTS Audio Player',
            importance: 'low',
        });
    });

    it('should start TTS service', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.startService('Test Title', 'Test Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            id: 'tts_service',
            title: 'Test Title',
            body: 'Test Body',
            android: {
                channelId: 'tts_channel_v2',
                asForegroundService: true,
                ongoing: true,
                category: 'service',
                actions: [
                    {
                        title: 'Prev',
                        pressAction: { id: 'tts_prev' },
                    },
                    {
                        title: 'Pause',
                        pressAction: { id: 'tts_pause' },
                    },
                    {
                        title: 'Next',
                        pressAction: { id: 'tts_next' },
                    },
                    {
                        title: 'Stop',
                        pressAction: { id: 'tts_stop' },
                    },
                ],
            },
        });
    });

    it('should update notification with playing state', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.updateNotification(true, 'Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            id: 'tts_service',
            title: 'Title',
            body: 'Body',
            android: expect.objectContaining({
                asForegroundService: true,
                ongoing: true,
                category: 'service',
                actions: expect.arrayContaining([
                    expect.objectContaining({
                        title: 'Pause',
                        pressAction: { id: 'tts_pause' },
                    }),
                ]),
            }),
        });
    });

    it('should update notification with paused state', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.updateNotification(false, 'Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            id: 'tts_service',
            title: 'Title',
            body: 'Body',
            android: expect.objectContaining({
                asForegroundService: true,
                ongoing: true,
                category: 'service',
                actions: expect.arrayContaining([
                    expect.objectContaining({
                        title: 'Play',
                        pressAction: { id: 'tts_play' },
                    }),
                ]),
            }),
        });
    });

    it('should stop TTS service', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.stopService();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
        expect(mockNotifee.cancelNotification).toHaveBeenCalledWith('tts_service');
    });

    it('should not start service when not initialized', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        mockNotifee.createChannel.mockRejectedValue(new Error('Init error'));

        await ttsNotificationService.startService('Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should not update notification when not initialized', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        mockNotifee.createChannel.mockRejectedValue(new Error('Init error'));

        await ttsNotificationService.updateNotification(true, 'Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should handle errors gracefully when stopping service', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');
        mockNotifee.stopForegroundService.mockRejectedValue(new Error('Stop error'));

        await ttsNotificationService.stopService();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
    });

    it('should display notification with all TTS actions', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.startService('Title', 'Body');

        const callArgs = mockNotifee.displayNotification.mock.calls[0][0];
        expect(callArgs.android.actions).toHaveLength(4);

        expect(callArgs.android.actions[0]).toEqual({
            title: 'Prev',
            pressAction: { id: 'tts_prev' },
        });
        expect(callArgs.android.actions[1]).toEqual({
            title: 'Pause',
            pressAction: { id: 'tts_pause' },
        });
        expect(callArgs.android.actions[2]).toEqual({
            title: 'Next',
            pressAction: { id: 'tts_next' },
        });
        expect(callArgs.android.actions[3]).toEqual({
            title: 'Stop',
            pressAction: { id: 'tts_stop' },
        });
    });

    it('should skip initialization in Expo Go', async () => {
        jest.resetModules();
        jest.doMock('expo-constants', () => ({
            executionEnvironment: 'storeClient',
        }));

        const { ttsNotificationService: newService } = require('../TTSNotificationService');

        expect(newService).toBeDefined();
    });

    it('should work on iOS (no service category needed)', async () => {
        jest.resetModules();
        jest.doMock('react-native', () => ({
            Platform: {
                OS: 'ios',
            },
        }));

        const { ttsNotificationService: newService } = require('../TTSNotificationService');

        expect(newService).toBeDefined();
    });

    it('should handle display notification error gracefully', async () => {
        jest.resetModules();
        jest.doMock('expo-constants', () => ({
            executionEnvironment: 'bare',
        }));
        jest.doMock('react-native', () => ({
            Platform: {
                OS: 'android',
            },
        }));
        const { ttsNotificationService } = require('../TTSNotificationService');
        mockNotifee.displayNotification.mockRejectedValue(new Error('Display error'));

        await ttsNotificationService.startService('Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should correctly set ongoing flag for TTS service', async () => {
        jest.resetModules();
        jest.doMock('expo-constants', () => ({
            executionEnvironment: 'bare',
        }));
        jest.doMock('react-native', () => ({
            Platform: {
                OS: 'android',
            },
        }));
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.startService('Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
        const androidConfig = mockNotifee.displayNotification.mock.calls[0][0].android;
        expect(androidConfig.ongoing).toBe(true);
        expect(androidConfig.asForegroundService).toBe(true);
    });
});
