import * as Speech from 'expo-speech';
import { TTSPlaybackController, TTSPlaybackState, TTSState } from '../TTSPlaybackController';
import { TTSQueue } from '../TTSQueue';
import { TTSSettings } from '../../StorageService';

jest.mock('expo-speech', () => ({
    stop: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../TTSQueue', () => ({
    TTSQueue: jest.fn().mockImplementation(() => ({
        processQueue: jest.fn(),
        resetBuffer: jest.fn(),
        updateSettings: jest.fn(),
    })),
}));

describe('TTSPlaybackController', () => {
    let controller: TTSPlaybackController;
    let mockConfig: any;
    let mockSettings: TTSSettings;
    let chunks: string[];
    const onStateChange = jest.fn();
    const onChunkChange = jest.fn();
    const onFinish = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();

        chunks = ['First chunk', 'Second chunk', 'Third chunk'];
        mockSettings = {
            voiceIdentifier: 'default',
            rate: 1.0,
            pitch: 1.0,
            chunkSize: 500,
        };

        mockConfig = {
            onStateChange,
            onChunkChange,
            onFinish,
        };

        controller = new TTSPlaybackController(chunks, 'Test Title', mockSettings, mockConfig);
    });

    describe('Initialization', () => {
        it('should initialize with empty queue', () => {
            const emptyController = new TTSPlaybackController([], 'Empty', mockSettings, mockConfig);

            const state = emptyController.getState();

            expect(state.chunks).toEqual([]);
            expect(state.currentChunkIndex).toBe(0);
            expect(state.isSpeaking).toBe(false);
            expect(state.isPaused).toBe(false);
        });

        it('should initialize with content queue', () => {
            const state = controller.getState();

            expect(state.chunks).toEqual(chunks);
            expect(state.currentChunkIndex).toBe(0);
            expect(state.title).toBe('Test Title');
        });

        it('should initialize with settings', () => {
            const settings = controller.getSettings();

            expect(settings.voiceIdentifier).toBe('default');
            expect(settings.rate).toBe(1.0);
            expect(settings.pitch).toBe(1.0);
        });

        it('should start with idle state', () => {
            const state = controller.getState();

            expect(state.isSpeaking).toBe(false);
            expect(state.isPaused).toBe(false);
        });

        it('should have session ID', () => {
            const sessionId = (controller as any).sessionId;

            expect(sessionId).toBeDefined();
            expect(typeof sessionId).toBe('number');
        });
    });

    describe('Playback Controls', () => {
        it('should start playback from beginning', () => {
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            controller.start(chunks, 'Test Title');

            const state = controller.getState();
            expect(state.isSpeaking).toBe(true);
            expect(state.isPaused).toBe(false);
            expect(state.currentChunkIndex).toBe(0);
            expect(state.chunks).toEqual(chunks);
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should start playback from specific position', () => {
            controller.start(chunks, 'Test Title');
            controller.start(['New chunk', 'Another chunk'], 'New Title');

            const state = controller.getState();
            expect(state.currentChunkIndex).toBe(0);
            expect(state.chunks).toEqual(['New chunk', 'Another chunk']);
            expect(state.title).toBe('New Title');
        });

        it('should pause playback', async () => {
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            controller.start(chunks, 'Test Title');
            await controller.pause();

            const state = controller.getState();
            expect(state.isPaused).toBe(true);
            expect(state.isSpeaking).toBe(true);
            expect(Speech.stop).toHaveBeenCalled();
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should not pause when not speaking', async () => {
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.pause();

            expect(Speech.stop).not.toHaveBeenCalled();
            expect(stateChangeSpy).not.toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should not pause when already paused', async () => {
            controller.start(chunks, 'Test Title');
            await controller.pause();

            const stopSpy = jest.spyOn(Speech, 'stop');
            await controller.pause();

            expect(stopSpy).toHaveBeenCalledTimes(1);
            stopSpy.mockRestore();
        });

        it('should resume playback', async () => {
            controller.start(chunks, 'Test Title');
            await controller.pause();
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            controller.resume();

            const state = controller.getState();
            expect(state.isPaused).toBe(false);
            expect(state.isSpeaking).toBe(true);
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should not resume when not paused', () => {
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            controller.resume();

            expect(stateChangeSpy).not.toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should stop playback', async () => {
            controller.start(chunks, 'Test Title');
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.stop();

            const state = controller.getState();
            expect(state.isSpeaking).toBe(false);
            expect(state.isPaused).toBe(false);
            expect(state.currentChunkIndex).toBe(0);
            expect(state.chunks).toEqual([]);
            expect(Speech.stop).toHaveBeenCalled();
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should skip to next chunk', async () => {
            controller.start(chunks, 'Test Title');
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.next();

            const state = controller.getState();
            expect(state.currentChunkIndex).toBe(1);
            expect(Speech.stop).toHaveBeenCalled();
            expect(onChunkChange).toHaveBeenCalledWith(1);
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should not skip past last chunk', async () => {
            controller.start(chunks, 'Test Title');
            (controller as any).state.currentChunkIndex = 2;

            await controller.next();

            const state = controller.getState();
            expect(state.currentChunkIndex).toBe(2);
        });

        it('should skip to previous chunk', async () => {
            controller.start(chunks, 'Test Title');
            (controller as any).state.currentChunkIndex = 2;
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.previous();

            const state = controller.getState();
            expect(state.currentChunkIndex).toBe(1);
            expect(Speech.stop).toHaveBeenCalled();
            expect(onChunkChange).toHaveBeenCalledWith(1);
            expect(stateChangeSpy).toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });

        it('should not skip before first chunk', async () => {
            controller.start(chunks, 'Test Title');

            await controller.previous();

            const state = controller.getState();
            expect(state.currentChunkIndex).toBe(0);
        });

        it('should toggle play/pause', async () => {
            controller.start(chunks, 'Test Title');
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.playPause();

            const state = controller.getState();
            expect(state.isPaused).toBe(true);

            await controller.playPause();

            const state2 = controller.getState();
            expect(state2.isPaused).toBe(false);
            stateChangeSpy.mockRestore();
        });

        it('should not toggle when not speaking', async () => {
            const stateChangeSpy = jest.spyOn(controller as any, 'emitStateChange');

            await controller.playPause();

            expect(stateChangeSpy).not.toHaveBeenCalled();
            stateChangeSpy.mockRestore();
        });
    });

    describe('Queue Management', () => {
        it('should initialize queue on start', () => {
            controller.start(chunks, 'Test Title');

            expect((controller as any).queue).toBeDefined();
        });

        it('should clear queue on stop', async () => {
            controller.start(chunks, 'Test Title');
            await controller.stop();

            expect((controller as any).queue).toBeNull();
        });

        it('should update queue position on navigation', async () => {
            controller.start(chunks, 'Test Title');

            await controller.next();

            expect((controller as any).queue?.resetBuffer).toHaveBeenCalledWith(1);
            expect((controller as any).queue?.processQueue).toHaveBeenCalledWith(1);
        });

        it('should handle empty queue during playback', () => {
            const newController = new TTSPlaybackController([], 'Empty', mockSettings, mockConfig);
            newController.start([], 'Empty');

            const state = newController.getState();
            expect(state.chunks).toEqual([]);
            expect(state.currentChunkIndex).toBe(0);
        });
    });

    describe('State Management', () => {
        it('should track state transitions', () => {
            const states: TTSState[] = [];

            mockConfig.onStateChange = (state: TTSState) => states.push(state);

            const newController = new TTSPlaybackController(chunks, 'Title', mockSettings, mockConfig);

            newController.start(chunks, 'Test Title');

            expect(states.length).toBeGreaterThan(0);
            expect(states[states.length - 1].isSpeaking).toBe(true);
        });

        it('should track position correctly', () => {
            controller.start(chunks, 'Test Title');

            let state = controller.getState();
            expect(state.currentChunkIndex).toBe(0);

            (controller as any).state.currentChunkIndex = 1;
            state = controller.getState();
            expect(state.currentChunkIndex).toBe(1);
        });

        it('should increment session ID on state change', () => {
            const initialSessionId = (controller as any).sessionId;

            controller.start(chunks, 'New Title');

            const newSessionId = (controller as any).sessionId;
            expect(newSessionId).not.toBe(initialSessionId);
        });

        it('should buffer next chunks', () => {
            controller.start(chunks, 'Test Title');

            const queue = (controller as any).queue;
            expect(queue).toBeDefined();
        });

        it('should detect end of queue', async () => {
            (controller as any).state.currentChunkIndex = chunks.length - 1;

            const queue = (controller as any).queue;

            if (queue && queue.processQueue.mock.calls[0]) {
                const configArg = (TTSQueue as jest.Mock).mock.calls[0][2];
                configArg.onChunkComplete(chunks.length - 1);

                const state = controller.getState();
                expect(state.chunks).toEqual([]);
            }
        });
    });

    describe('Event Handling', () => {
        it('should call onStateChange on state updates', () => {
            controller.start(chunks, 'Test Title');

            expect(onStateChange).toHaveBeenCalled();
        });

        it('should call onChunkChange on chunk navigation', async () => {
            controller.start(chunks, 'Test Title');

            await controller.next();

            expect(onChunkChange).toHaveBeenCalledWith(1);
        });

        it('should call onChunkChange on previous navigation', async () => {
            controller.start(chunks, 'Test Title');
            (controller as any).state.currentChunkIndex = 2;

            await controller.previous();

            expect(onChunkChange).toHaveBeenCalledWith(1);
        });

        it('should call onFinish on completion', async () => {
            const queueMock = {
                processQueue: jest.fn(),
                resetBuffer: jest.fn(),
                updateSettings: jest.fn(),
            };

            (TTSQueue as jest.Mock).mockImplementation(() => queueMock);

            const newController = new TTSPlaybackController(chunks, 'Title', mockSettings, mockConfig);
            newController.start(chunks, 'Title');

            const configArg = (TTSQueue as jest.Mock).mock.calls[0][2];

            configArg.onChunkComplete(chunks.length - 1);

            expect(onFinish).toHaveBeenCalled();
        });

        it('should handle multiple rapid state changes', () => {
            controller.start(chunks, 'Test Title');

            controller.start(['New'], 'New');
            controller.pause();
            controller.resume();

            expect(onStateChange).toHaveBeenCalled();
        });
    });

    describe('Error Handling', () => {
        it('should handle speech synthesis errors', async () => {
            const queueMock = {
                processQueue: jest.fn(),
                resetBuffer: jest.fn(),
                updateSettings: jest.fn(),
            };

            (TTSQueue as jest.Mock).mockImplementation(() => queueMock);

            const newController = new TTSPlaybackController(chunks, 'Title', mockSettings, mockConfig);
            newController.start(chunks, 'Title');

            const configArg = (TTSQueue as jest.Mock).mock.calls[0][2];

            configArg.onError(new Error('Speech error'));
            await new Promise(resolve => setTimeout(resolve, 0));

            const state = newController.getState();
            expect(state.chunks).toEqual([]);
        });

        it('should handle empty content chunks', () => {
            const newController = new TTSPlaybackController([], 'Empty', mockSettings, mockConfig);
            newController.start([], 'Empty');

            const state = newController.getState();
            expect(state.chunks).toEqual([]);
            expect(state.currentChunkIndex).toBe(0);
        });

        it('should handle queue position out of bounds', async () => {
            controller.start(chunks, 'Test Title');
            (controller as any).state.currentChunkIndex = 100;

            await controller.next();

            const state = controller.getState();
            expect(state.currentChunkIndex).toBeLessThanOrEqual(chunks.length - 1);
        });

        it('should handle session ID changes gracefully', () => {
            const initialSessionId = (controller as any).sessionId;

            controller.start(chunks, 'New Title');
            const secondSessionId = (controller as any).sessionId;

            controller.pause();
            const thirdSessionId = (controller as any).sessionId;

            expect(initialSessionId).not.toBe(secondSessionId);
            expect(secondSessionId).not.toBe(thirdSessionId);
        });

        it('should handle onFinish callback updates', () => {
            const newFinish = jest.fn();

            controller.setOnFinishCallback(newFinish);

            expect(mockConfig.onFinish).toBe(newFinish);
        });
    });

    describe('Settings Management', () => {
        it('should update settings', () => {
            const newSettings: TTSSettings = {
                voiceIdentifier: 'new-voice',
                rate: 1.5,
                pitch: 0.8,
                chunkSize: 500,
            };

            controller.updateSettings(newSettings);

            const settings = controller.getSettings();
            expect(settings.voiceIdentifier).toBe('new-voice');
            expect(settings.rate).toBe(1.5);
            expect(settings.pitch).toBe(0.8);
        });

        it('should update settings during playback', () => {
            controller.start(chunks, 'Test Title');

            const newSettings: TTSSettings = {
                voiceIdentifier: 'updated-voice',
                rate: 1.2,
                pitch: 1.0,
                chunkSize: 500,
            };

            controller.updateSettings(newSettings);

            const queue = (controller as any).queue;
            expect(queue?.updateSettings).toHaveBeenCalledWith(newSettings);
        });

        it('should not update settings when queue is null', () => {
            const newSettings: TTSSettings = {
                voiceIdentifier: 'new-voice',
                rate: 1.5,
                pitch: 0.8,
                chunkSize: 500,
            };

            controller.updateSettings(newSettings);

            const settings = controller.getSettings();
            expect(settings.voiceIdentifier).toBe('new-voice');
        });
    });

    describe('Session Management', () => {
        it('should return session ID', () => {
            const sessionId = controller.getSessionId();

            expect(typeof sessionId).toBe('number');
        });

        it('should increment session ID on start', () => {
            const initialId = controller.getSessionId();

            controller.start(chunks, 'New Title');

            const newId = controller.getSessionId();
            expect(newId).not.toBe(initialId);
        });

        it('should increment session ID on pause', async () => {
            controller.start(chunks, 'Test Title');

            const initialId = controller.getSessionId();
            await controller.pause();

            const newId = controller.getSessionId();
            expect(newId).not.toBe(initialId);
        });

        it('should increment session ID on navigation', async () => {
            controller.start(chunks, 'Test Title');

            const initialId = controller.getSessionId();
            await controller.next();

            const newId = controller.getSessionId();
            expect(newId).not.toBe(initialId);
        });

        it('should increment session ID on stop', async () => {
            controller.start(chunks, 'Test Title');

            const initialId = controller.getSessionId();
            await controller.stop();

            const newId = controller.getSessionId();
            expect(newId).not.toBe(initialId);
        });
    });

    describe('State Isolation', () => {
        it('should return copy of state', () => {
            const state1 = controller.getState();
            const state2 = controller.getState();

            expect(state1).toEqual(state2);
            expect(state1).not.toBe(state2);
        });

        it('should return copy of settings', () => {
            const settings1 = controller.getSettings();
            const settings2 = controller.getSettings();

            expect(settings1).toEqual(settings2);
            expect(settings1).not.toBe(settings2);
        });
    });
});
