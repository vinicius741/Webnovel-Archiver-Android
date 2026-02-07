jest.useFakeTimers();

const mockAddEventListener = jest.fn();
const mockRemoveListener = jest.fn();

jest.mock('react-native', () => ({
    AppState: {
        currentState: 'active',
        addEventListener: mockAddEventListener,
    },
}));

jest.mock('expo-speech', () => ({
    isSpeakingAsync: jest.fn(),
}));

const mockTtsStateManager = {
    getPersistedSession: jest.fn().mockResolvedValue(null),
    getState: jest.fn().mockReturnValue(null),
    recoverPlaybackFromSilentStop: jest.fn().mockResolvedValue(undefined),
};

jest.mock('../TTSStateManager', () => ({
    ttsStateManager: mockTtsStateManager,
}));

describe('TTSLifecycleService', () => {
    let lifecycleService: any;
    let appStateChangeHandler: ((state: string) => void) | undefined;

    beforeEach(() => {
        jest.clearAllMocks();
        jest.resetModules();
        mockTtsStateManager.getPersistedSession.mockResolvedValue(null);
        mockTtsStateManager.getState.mockReturnValue(null);
        mockTtsStateManager.recoverPlaybackFromSilentStop.mockResolvedValue(undefined);

        mockAddEventListener.mockImplementation((_event: string, handler: (state: string) => void) => {
            appStateChangeHandler = handler;
            return { remove: mockRemoveListener };
        });

        const module = require('../TTSLifecycleService');
        lifecycleService = module.ttsLifecycleService;
    });

    afterEach(() => {
        lifecycleService.stop();
    });

    it('should register AppState listener on start', () => {
        lifecycleService.start();
        expect(mockAddEventListener).toHaveBeenCalledWith('change', expect.any(Function));
    });

    it('should reconcile on foreground and recover silent playback', async () => {
        const Speech = require('expo-speech');
        mockTtsStateManager.getPersistedSession.mockResolvedValue({
            storyId: 'story-1',
            chapterId: 'chapter-1',
            wasPlaying: true,
        });
        mockTtsStateManager.getState.mockReturnValue({
            isSpeaking: true,
            isPaused: false,
        });
        Speech.isSpeakingAsync.mockResolvedValue(false);

        lifecycleService.start();
        await lifecycleService['handleForeground']();
        await Promise.resolve();

        expect(mockTtsStateManager.recoverPlaybackFromSilentStop).toHaveBeenCalled();
    });

    it('should use watchdog to recover after repeated silent checks', async () => {
        const Speech = require('expo-speech');
        mockTtsStateManager.getState.mockReturnValue({
            isSpeaking: true,
            isPaused: false,
        });
        Speech.isSpeakingAsync.mockResolvedValue(false);

        lifecycleService.start();

        jest.advanceTimersByTime(3000);
        await Promise.resolve();
        expect(mockTtsStateManager.recoverPlaybackFromSilentStop).not.toHaveBeenCalled();

        jest.advanceTimersByTime(3000);
        await Promise.resolve();
        expect(mockTtsStateManager.recoverPlaybackFromSilentStop).toHaveBeenCalledTimes(1);
    });

    it('should stop watchdog and remove subscription', () => {
        lifecycleService.start();
        lifecycleService.stop();
        expect(mockRemoveListener).toHaveBeenCalled();
    });
});
