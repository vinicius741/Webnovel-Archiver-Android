import { TTSPlaybackController, TTSPlaybackState } from '../../tts/TTSPlaybackController';
import * as Speech from 'expo-speech';

jest.mock('expo-speech');

describe('TTSPlaybackController', () => {
    const mockSettings = {
        pitch: 1.0,
        rate: 1.0,
        chunkSize: 500,
    };

    const mockConfig = {
        onStateChange: jest.fn(),
        onChunkChange: jest.fn(),
        onFinish: jest.fn(),
    };

    const mockChunks = ['Chunk 1', 'Chunk 2', 'Chunk 3'];

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should create controller with initial state', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);

        const state = controller.getState();
        expect(state.isSpeaking).toBe(false);
        expect(state.isPaused).toBe(false);
        expect(state.chunks).toEqual(mockChunks);
        expect(state.currentChunkIndex).toBe(0);
        expect(state.title).toBe('Test Story');
    });

    it('should start playback', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);

        controller.start(mockChunks, 'Test Story');

        const state = controller.getState();
        expect(state.isSpeaking).toBe(true);
        expect(state.isPaused).toBe(false);
        expect(state.currentChunkIndex).toBe(0);
        expect(mockConfig.onStateChange).toHaveBeenCalled();
    });

    it('should not start with empty chunks', () => {
        const controller = new TTSPlaybackController([], 'Test Story', mockSettings, mockConfig);

        controller.start([], 'Test Story');

        const state = controller.getState();
        expect(state.isSpeaking).toBe(false);
    });

    it('should stop playback', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.stop();

        const state = controller.getState();
        expect(state.isSpeaking).toBe(false);
        expect(state.isPaused).toBe(false);
        expect(state.chunks).toEqual([]);
        expect(state.currentChunkIndex).toBe(0);
        expect(Speech.stop).toHaveBeenCalled();
        expect(mockConfig.onStateChange).toHaveBeenCalled();
    });

    it('should pause playback', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.pause();

        const state = controller.getState();
        expect(state.isPaused).toBe(true);
        expect(state.isSpeaking).toBe(true);
        expect(Speech.stop).toHaveBeenCalled();
        expect(mockConfig.onStateChange).toHaveBeenCalled();
    });

    it('should not pause if not speaking or already paused', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);

        await controller.pause();

        expect(Speech.stop).not.toHaveBeenCalled();
    });

    it('should resume playback', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');
        controller.pause();

        controller.resume();

        const state = controller.getState();
        expect(state.isPaused).toBe(false);
        expect(mockConfig.onStateChange).toHaveBeenCalled();
    });

    it('should not resume if not paused', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        controller.resume();

        expect(mockConfig.onStateChange).toHaveBeenCalledTimes(1);
    });

    it('should toggle play/pause', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.playPause();

        expect(controller.getState().isPaused).toBe(true);

        controller.playPause();

        expect(controller.getState().isPaused).toBe(false);
    });

    it('should go to next chunk', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.next();

        const state = controller.getState();
        expect(state.currentChunkIndex).toBe(1);
        expect(state.isPaused).toBe(false);
        expect(Speech.stop).toHaveBeenCalled();
        expect(mockConfig.onStateChange).toHaveBeenCalled();
        expect(mockConfig.onChunkChange).toHaveBeenCalledWith(1);
    });

    it('should not go beyond last chunk', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.next();
        await controller.next();
        await controller.next();

        const state = controller.getState();
        expect(state.currentChunkIndex).toBe(2);
    });

    it('should not go to next if not speaking', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);

        await controller.next();

        expect(Speech.stop).not.toHaveBeenCalled();
        expect(mockConfig.onChunkChange).not.toHaveBeenCalled();
    });

    it('should go to previous chunk', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.next();
        await controller.previous();

        const state = controller.getState();
        expect(state.currentChunkIndex).toBe(0);
        expect(Speech.stop).toHaveBeenCalled();
        expect(mockConfig.onChunkChange).toHaveBeenCalledWith(0);
    });

    it('should not go below first chunk', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');

        await controller.previous();

        const state = controller.getState();
        expect(state.currentChunkIndex).toBe(0);
    });

    it('should not go to previous if not speaking', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);

        await controller.previous();

        expect(Speech.stop).not.toHaveBeenCalled();
        expect(mockConfig.onChunkChange).not.toHaveBeenCalled();
    });

    it('should update settings', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        const newSettings = { pitch: 1.5, rate: 0.8, chunkSize: 600 };

        controller.updateSettings(newSettings);

        expect(controller.getSettings()).toEqual(newSettings);
    });

    it('should set finish callback', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        const newCallback = jest.fn();

        controller.setOnFinishCallback(newCallback);

        expect(mockConfig.onFinish).toBe(newCallback);
    });

    it('should increment session ID on start', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        const initialSessionId = controller.getSessionId();

        controller.start(mockChunks, 'Test Story');

        expect(controller.getSessionId()).toBeGreaterThan(initialSessionId);
    });

    it('should increment session ID on stop', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');
        const beforeStopId = controller.getSessionId();

        await controller.stop();

        expect(controller.getSessionId()).toBeGreaterThan(beforeStopId);
    });

    it('should increment session ID on pause', async () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        controller.start(mockChunks, 'Test Story');
        const beforePauseId = controller.getSessionId();

        await controller.pause();

        expect(controller.getSessionId()).toBeGreaterThan(beforePauseId);
    });

    it('should return copy of state', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        const state1 = controller.getState();
        const state2 = controller.getState();

        expect(state1).toEqual(state2);
        expect(state1).not.toBe(state2);
    });

    it('should return copy of settings', () => {
        const controller = new TTSPlaybackController(mockChunks, 'Test Story', mockSettings, mockConfig);
        const settings1 = controller.getSettings();
        const settings2 = controller.getSettings();

        expect(settings1).toEqual(settings2);
        expect(settings1).not.toBe(settings2);
    });
});
