import { TTS_STATE_EVENTS, ttsStateManager } from '../TTSStateManager';

jest.mock('../StorageService', () => ({
    storageService: {
        getTTSSettings: jest.fn().mockResolvedValue({ pitch: 1.0, rate: 1.0, chunkSize: 500 }),
        saveTTSSettings: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../tts/TTSPlaybackController', () => {
    const mockController = {
        getState: jest.fn().mockReturnValue({
            isPlaying: true,
            currentChunkIndex: 0,
            chunks: ['chunk1', 'chunk2'],
            title: 'Test Story',
        }),
        start: jest.fn(),
        stop: jest.fn().mockResolvedValue(undefined),
        pause: jest.fn().mockResolvedValue(undefined),
        resume: jest.fn(),
        playPause: jest.fn().mockResolvedValue(undefined),
        next: jest.fn().mockResolvedValue(undefined),
        previous: jest.fn().mockResolvedValue(undefined),
        updateSettings: jest.fn(),
        setOnFinishCallback: jest.fn(),
    };

    class MockTTSPlaybackController {
        constructor(chunks: string[], title: string, settings: any, config: any) {
            config.onStateChange?.(mockController.getState());
        }

        getState = mockController.getState;
        start = mockController.start;
        stop = mockController.stop;
        pause = mockController.pause;
        resume = mockController.resume;
        playPause = mockController.playPause;
        next = mockController.next;
        previous = mockController.previous;
        updateSettings = mockController.updateSettings;
        setOnFinishCallback = mockController.setOnFinishCallback;
    }

    return {
        TTSPlaybackController: MockTTSPlaybackController as any,
        mockController,
    };
});

jest.mock('react-native', () => ({
    DeviceEventEmitter: {
        emit: jest.fn(),
    },
}));

jest.mock('../TTSNotificationService', () => ({
    ttsNotificationService: {
        startService: jest.fn().mockResolvedValue(undefined),
        stopService: jest.fn().mockResolvedValue(undefined),
        updateNotification: jest.fn().mockResolvedValue(undefined),
    },
}));

describe('TTSStateManager', () => {
    let mockController: any;

    beforeEach(() => {
        jest.clearAllMocks();
        const { mockController: controller } = require('../tts/TTSPlaybackController');
        mockController = controller;
    });

    describe('Singleton Pattern', () => {
        it('should return the same instance', () => {
            const instance1 = ttsStateManager;
            const instance2 = ttsStateManager;
            expect(instance1).toBe(instance2);
        });
    });

    describe('State Management', () => {
        it('should get settings', () => {
            const settings = ttsStateManager.getSettings();
            expect(settings).toEqual({ pitch: 1.0, rate: 1.0, chunkSize: 500 });
        });

        it('should update settings and save to storage', async () => {
            const newSettings = { pitch: 1.5, rate: 1.2, chunkSize: 300 };
            await ttsStateManager.start(['chunk1', 'chunk2'], 'Test Story');
            await ttsStateManager.updateSettings(newSettings);

            const { storageService } = require('../StorageService');
            expect(storageService.saveTTSSettings).toHaveBeenCalledWith(newSettings);
            expect(mockController.updateSettings).toHaveBeenCalledWith(newSettings);
        });
    });

    describe('Playback Controls', () => {
        it('should start with chunks and title', async () => {
            const chunks = ['chunk1', 'chunk2', 'chunk3'];
            ttsStateManager.start(chunks, 'Test Story');

            expect(mockController.start).toHaveBeenCalledWith(chunks, 'Test Story');
            const { ttsNotificationService } = require('../TTSNotificationService');
            expect(ttsNotificationService.startService).toHaveBeenCalled();
        });

        it('should not start with empty chunks', () => {
            ttsStateManager.start([], 'Test Story');
            expect(mockController.start).not.toHaveBeenCalled();
        });

        it('should stop playback and notification service', async () => {
            await ttsStateManager.stop();

            expect(mockController.stop).toHaveBeenCalled();
            const { ttsNotificationService } = require('../TTSNotificationService');
            expect(ttsNotificationService.stopService).toHaveBeenCalled();
        });

        it('should pause playback and update notification', async () => {
            await ttsStateManager.pause();

            expect(mockController.pause).toHaveBeenCalled();
            const { ttsNotificationService } = require('../TTSNotificationService');
            expect(ttsNotificationService.updateNotification).toHaveBeenCalledWith(
                false,
                'Test Story',
                'Paused: Chunk 1'
            );
        });

        it('should resume playback', () => {
            ttsStateManager.resume();
            expect(mockController.resume).toHaveBeenCalled();
        });

        it('should toggle play/pause', async () => {
            await ttsStateManager.playPause();
            expect(mockController.playPause).toHaveBeenCalled();
        });

        it('should play next chunk and update notification', async () => {
            await ttsStateManager.next();

            expect(mockController.next).toHaveBeenCalled();
            const { ttsNotificationService } = require('../TTSNotificationService');
            expect(ttsNotificationService.updateNotification).toHaveBeenCalled();
        });

        it('should play previous chunk and update notification', async () => {
            await ttsStateManager.previous();

            expect(mockController.previous).toHaveBeenCalled();
            const { ttsNotificationService } = require('../TTSNotificationService');
            expect(ttsNotificationService.updateNotification).toHaveBeenCalled();
        });

        it('should set on finish callback', () => {
            const callback = jest.fn();
            ttsStateManager.setOnFinishCallback(callback);
            expect(mockController.setOnFinishCallback).toHaveBeenCalledWith(callback);
        });
    });

});

