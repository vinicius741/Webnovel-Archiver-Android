jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

jest.mock('react-native', () => ({
    Platform: { OS: 'android' },
}));

import { EventType } from '@notifee/react-native';

// Mock TTSStateManager
const mockTTSStateManager = {
    resume: jest.fn(),
    pause: jest.fn().mockResolvedValue(undefined),
    next: jest.fn().mockResolvedValue(undefined),
    previous: jest.fn().mockResolvedValue(undefined),
    stop: jest.fn().mockResolvedValue(undefined),
};

jest.mock('../TTSStateManager', () => ({
    ttsStateManager: mockTTSStateManager,
}));

// Mock DownloadManager
const mockDownloadManager = {
    cancelAll: jest.fn().mockResolvedValue(undefined),
};

jest.mock('../download/DownloadManager', () => ({
    downloadManager: mockDownloadManager,
}));

// Mock ForegroundServiceCoordinator
const mockClearDownloadState = jest.fn().mockResolvedValue(undefined);
jest.mock('../ForegroundServiceCoordinator', () => ({
    clearDownloadState: mockClearDownloadState,
    registerForegroundService: jest.fn().mockResolvedValue(undefined),
}));

// Mock Notifee
const mockOnBackgroundEvent = jest.fn();
const mockNotifee = {
    default: {
        onBackgroundEvent: mockOnBackgroundEvent,
        cancelNotification: jest.fn().mockResolvedValue(undefined),
    },
    EventType: {
        ACTION_PRESS: 'ACTION_PRESS',
    },
};

jest.mock('../NotifeeTypes', () => ({
    loadNotifee: jest.fn(() => mockNotifee),
}));

describe('BackgroundService - Concurrent Download + TTS Actions', () => {
    let backgroundEventHandler: ((event: any) => Promise<void>) | undefined;

    beforeEach(async () => {
        jest.clearAllMocks();
        jest.resetModules();

        // Import BackgroundService to trigger initialization
        require('../BackgroundService');

        // Capture the registered event handler
        expect(mockOnBackgroundEvent).toHaveBeenCalledWith(expect.any(Function));
        backgroundEventHandler = mockOnBackgroundEvent.mock.calls[0][0];
    });

    describe('Cancel Action During TTS Playback', () => {
        it('should cancel downloads when cancel is pressed while TTS is playing', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: {
                    pressAction: { id: 'cancel' },
                },
            });

            expect(mockDownloadManager.cancelAll).toHaveBeenCalled();
            expect(mockClearDownloadState).toHaveBeenCalled();
            expect(mockNotifee.default.cancelNotification).toHaveBeenCalledWith('foreground_service');

            // TTS should not be affected by cancel action
            expect(mockTTSStateManager.pause).not.toHaveBeenCalled();
            expect(mockTTSStateManager.stop).not.toHaveBeenCalled();
        });

        it('should handle rapid cancel followed by TTS actions', async () => {
            // User cancels download, then quickly presses TTS pause
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'cancel' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_pause' } },
            });

            expect(mockDownloadManager.cancelAll).toHaveBeenCalled();
            expect(mockTTSStateManager.pause).toHaveBeenCalled();
        });
    });

    describe('TTS Actions During Download', () => {
        it('should handle TTS pause while download is active', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_pause' } },
            });

            expect(mockTTSStateManager.pause).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });

        it('should handle TTS play while download is active', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_play' } },
            });

            expect(mockTTSStateManager.resume).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });

        it('should handle TTS next while download is active', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_next' } },
            });

            expect(mockTTSStateManager.next).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });

        it('should handle TTS previous while download is active', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_prev' } },
            });

            expect(mockTTSStateManager.previous).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });

        it('should handle TTS stop while download is active', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_stop' } },
            });

            expect(mockTTSStateManager.stop).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });
    });

    describe('Rapid Action Sequences', () => {
        it('should handle pause/play toggle while downloading', async () => {
            // Simulate user rapidly toggling pause/play
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_pause' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_play' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_pause' } },
            });

            expect(mockTTSStateManager.pause).toHaveBeenCalledTimes(2);
            expect(mockTTSStateManager.resume).toHaveBeenCalledTimes(1);
        });

        it('should handle next/prev navigation while downloading', async () => {
            // User skips forward multiple times
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_next' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_next' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_prev' } },
            });

            expect(mockTTSStateManager.next).toHaveBeenCalledTimes(2);
            expect(mockTTSStateManager.previous).toHaveBeenCalledTimes(1);
        });

        it('should handle cancel followed by stop', async () => {
            // User cancels download, then stops TTS
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'cancel' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_stop' } },
            });

            expect(mockDownloadManager.cancelAll).toHaveBeenCalled();
            expect(mockTTSStateManager.stop).toHaveBeenCalled();
            expect(mockClearDownloadState).toHaveBeenCalled();
        });
    });

    describe('Unknown Actions', () => {
        it('should ignore unknown action IDs', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'unknown_action' } },
            });

            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
            expect(mockTTSStateManager.pause).not.toHaveBeenCalled();
            expect(mockTTSStateManager.resume).not.toHaveBeenCalled();
            expect(mockTTSStateManager.stop).not.toHaveBeenCalled();
            expect(mockTTSStateManager.next).not.toHaveBeenCalled();
            expect(mockTTSStateManager.previous).not.toHaveBeenCalled();
        });

        it('should handle actions without pressAction', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: {},
            });

            // Should not throw, should just ignore
            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });

        it('should handle events without detail', async () => {
            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
            });

            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });
    });

    describe('Non-ACTION_PRESS Events', () => {
        it('should ignore non-action events', async () => {
            await backgroundEventHandler!({
                type: 'DISMISSED',
                detail: {},
            });

            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
            expect(mockTTSStateManager.pause).not.toHaveBeenCalled();
        });

        it('should handle events with different types', async () => {
            const eventTypes = ['DELIVERED', 'APP_BLOCKED', 'CHANNEL_UPDATED'];

            for (const type of eventTypes) {
                await backgroundEventHandler!({
                    type,
                    detail: {},
                });
            }

            expect(mockDownloadManager.cancelAll).not.toHaveBeenCalled();
        });
    });

    describe('Error Handling', () => {
        it('should handle TTS action errors gracefully', async () => {
            mockTTSStateManager.pause.mockRejectedValueOnce(new Error('TTS error'));

            // Should not throw
            await expect(
                backgroundEventHandler!({
                    type: 'ACTION_PRESS',
                    detail: { pressAction: { id: 'tts_pause' } },
                })
            ).resolves.not.toThrow();
        });

        it('should handle download cancel errors gracefully', async () => {
            mockDownloadManager.cancelAll.mockRejectedValueOnce(new Error('Download error'));

            await expect(
                backgroundEventHandler!({
                    type: 'ACTION_PRESS',
                    detail: { pressAction: { id: 'cancel' } },
                })
            ).resolves.not.toThrow();
        });

        it('should continue processing actions after one fails', async () => {
            mockTTSStateManager.pause.mockRejectedValueOnce(new Error('First error'));

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_pause' } },
            });

            await backgroundEventHandler!({
                type: 'ACTION_PRESS',
                detail: { pressAction: { id: 'tts_next' } },
            });

            // Second action should still be attempted
            expect(mockTTSStateManager.next).toHaveBeenCalled();
        });
    });

    describe('Concurrent Action Processing', () => {
        it('should handle multiple simultaneous actions', async () => {
            // Simulate rapid button presses
            const actions = [
                { id: 'tts_pause' },
                { id: 'tts_next' },
                { id: 'tts_play' },
                { id: 'cancel' },
                { id: 'tts_stop' },
            ];

            await Promise.all(
                actions.map(action =>
                    backgroundEventHandler!({
                        type: 'ACTION_PRESS',
                        detail: { pressAction: { id: action.id } },
                    })
                )
            );

            expect(mockTTSStateManager.pause).toHaveBeenCalled();
            expect(mockTTSStateManager.next).toHaveBeenCalled();
            expect(mockTTSStateManager.resume).toHaveBeenCalled();
            expect(mockDownloadManager.cancelAll).toHaveBeenCalled();
            expect(mockTTSStateManager.stop).toHaveBeenCalled();
        });
    });
});
