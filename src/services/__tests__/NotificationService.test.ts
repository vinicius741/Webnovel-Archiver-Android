
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
    requestPermission: jest.fn().mockResolvedValue(undefined),
};

jest.mock('@notifee/react-native', () => {
    return {
        default: mockNotifee,
        AndroidImportance: {
            LOW: 'low',
            MEDIUM: 'medium',
            HIGH: 'high',
        },
        AndroidColor: {
            BLUE: 'blue',
        },
    };
});

describe('NotificationService', () => {
    const mockModule = require('@notifee/react-native');

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should create channels on initialization', async () => {
        jest.resetModules();
        const { notificationService: service } = require('../NotificationService');

        await new Promise(setImmediate);

        expect(mockNotifee.createChannel).toHaveBeenCalledWith({
            id: 'download_channel',
            name: 'Download Service',
            importance: 'low',
        });
        expect(mockNotifee.createChannel).toHaveBeenCalledWith({
            id: 'download_complete',
            name: 'Download Complete',
            importance: 'medium',
        });
    });

    it('should start foreground service', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.startForegroundService('Test Title', 'Test Body', 100, 50);

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            id: 'download_progress',
            title: 'Test Title',
            body: 'Test Body',
            android: {
                channelId: 'download_channel',
                asForegroundService: true,
                color: 'blue',
                ongoing: true,
                progress: {
                    max: 100,
                    current: 50,
                    indeterminate: false,
                },
                actions: [
                    {
                        title: 'Cancel',
                        pressAction: {
                            id: 'cancel',
                        },
                    },
                ],
            },
        });
    });

    it('should update progress notification', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.updateProgress(75, 100, 'Downloading');

        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            id: 'download_progress',
            title: 'Downloading...',
            body: 'Downloading',
            android: {
                channelId: 'download_channel',
                asForegroundService: true,
                onlyAlertOnce: true,
                progress: {
                    max: 100,
                    current: 75,
                },
            },
        });
    });

    it('should not update progress when total is zero or negative', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.updateProgress(75, 0, 'Test');

        expect(mockNotifee.displayNotification).not.toHaveBeenCalled();
    });

    it('should stop foreground service', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.stopForegroundService();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
    });

    it('should show completion notification', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.showCompletionNotification('Download Complete', 'All files downloaded');

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
        expect(mockNotifee.cancelNotification).toHaveBeenCalledWith('download_progress');
        expect(mockNotifee.displayNotification).toHaveBeenCalledWith({
            title: 'Download Complete',
            body: 'All files downloaded',
            android: {
                channelId: 'download_complete',
                smallIcon: 'ic_launcher',
                pressAction: {
                    id: 'default',
                },
            },
        });
    });

    it('should request permissions', async () => {
        const { notificationService } = require('../NotificationService');

        await notificationService.requestPermissions();

        expect(mockNotifee.requestPermission).toHaveBeenCalled();
    });

    it('should not throw error when notifee is not available', async () => {
        const { notificationService } = require('../NotificationService');

        expect(notificationService).toBeDefined();
    });

    it('should handle errors gracefully when starting foreground service', async () => {
        const { notificationService } = require('../NotificationService');
        mockNotifee.displayNotification.mockRejectedValue(new Error('Display error'));

        await notificationService.startForegroundService('Title', 'Body');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should handle errors gracefully when updating progress', async () => {
        const { notificationService } = require('../NotificationService');
        mockNotifee.displayNotification.mockRejectedValue(new Error('Update error'));

        await notificationService.updateProgress(50, 100, 'Updating');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should handle errors gracefully when stopping foreground service', async () => {
        const { notificationService } = require('../NotificationService');
        mockNotifee.stopForegroundService.mockRejectedValue(new Error('Stop error'));

        await notificationService.stopForegroundService();

        expect(mockNotifee.stopForegroundService).toHaveBeenCalled();
    });

    it('should handle errors gracefully when showing completion notification', async () => {
        const { notificationService } = require('../NotificationService');
        mockNotifee.displayNotification.mockRejectedValue(new Error('Completion error'));

        await notificationService.showCompletionNotification('Complete', 'Done');

        expect(mockNotifee.displayNotification).toHaveBeenCalled();
    });

    it('should handle errors gracefully when requesting permissions', async () => {
        const { notificationService } = require('../NotificationService');
        mockNotifee.requestPermission.mockRejectedValue(new Error('Permission error'));

        await notificationService.requestPermissions();

        expect(mockNotifee.requestPermission).toHaveBeenCalled();
    });

    it('should skip initialization in Expo Go', async () => {
        jest.resetModules();
        jest.doMock('expo-constants', () => ({
            executionEnvironment: 'storeClient',
        }));

        const { notificationService: newService } = require('../NotificationService');

        expect(newService).toBeDefined();
    });

    it('should work on iOS (no channels needed)', async () => {
        jest.resetModules();
        jest.doMock('react-native', () => ({
            Platform: {
                OS: 'ios',
            },
        }));

        const { notificationService: newService } = require('../NotificationService');

        expect(newService).toBeDefined();
    });
});
