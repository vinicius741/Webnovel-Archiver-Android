
jest.mock('react-native', () => ({
    Platform: {
        OS: 'android',
    },
}));

jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

jest.mock('../ForegroundServiceCoordinator', () => ({
    setTtsState: jest.fn().mockResolvedValue(undefined),
    clearTtsState: jest.fn().mockResolvedValue(undefined),
}));

describe('TTSNotificationService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should start TTS service', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');
        const { setTtsState } = require('../ForegroundServiceCoordinator');

        await ttsNotificationService.startService('Test Title', 'Test Body');

        expect(setTtsState).toHaveBeenCalledWith({
            title: 'Test Title',
            body: 'Test Body',
            isPlaying: true,
        });
    });

    it('should update notification with playing state', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');
        const { setTtsState } = require('../ForegroundServiceCoordinator');

        await ttsNotificationService.updateNotification(true, 'Title', 'Body');

        expect(setTtsState).toHaveBeenCalledWith({
            title: 'Title',
            body: 'Body',
            isPlaying: true,
        });
    });

    it('should update notification with paused state', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');
        const { setTtsState } = require('../ForegroundServiceCoordinator');

        await ttsNotificationService.updateNotification(false, 'Title', 'Body');

        expect(setTtsState).toHaveBeenCalledWith({
            title: 'Title',
            body: 'Body',
            isPlaying: false,
        });
    });

    it('should stop TTS service', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');
        const { clearTtsState } = require('../ForegroundServiceCoordinator');

        await ttsNotificationService.stopService();

        expect(clearTtsState).toHaveBeenCalled();
    });

    it('should start service when initialized', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.startService('Title', 'Body');

        const { setTtsState } = require('../ForegroundServiceCoordinator');
        expect(setTtsState).toHaveBeenCalled();
    });

    it('should update notification when initialized', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        await ttsNotificationService.updateNotification(true, 'Title', 'Body');

        const { setTtsState } = require('../ForegroundServiceCoordinator');
        expect(setTtsState).toHaveBeenCalled();
    });

    it('should not throw error when coordinator is available', async () => {
        const { ttsNotificationService } = require('../TTSNotificationService');

        expect(ttsNotificationService).toBeDefined();
    });
});
