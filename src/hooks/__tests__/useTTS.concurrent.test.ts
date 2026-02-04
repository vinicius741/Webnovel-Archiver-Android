import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useTTS } from '../useTTS';
import { TTS_STATE_EVENTS } from '../../services/TTSStateManager';

// Mock all the dependencies
jest.mock('expo-keep-awake', () => ({
    activateKeepAwakeAsync: jest.fn().mockResolvedValue(undefined),
    deactivateKeepAwake: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../../services/StorageService', () => ({
    storageService: {
        getTTSSettings: jest.fn().mockResolvedValue({ pitch: 1.0, rate: 1.0, chunkSize: 500 }),
        saveTTSSettings: jest.fn().mockResolvedValue(undefined),
    },
    TTSSettings: {},
}));

jest.mock('../../services/tts/TTSPlaybackController', () => {
    let mockControllerInstance: any = null;

    const createMockController = () => {
        mockControllerInstance = {
            chunks: [],
            title: '',
            isSpeaking: false,
            isPaused: false,
            currentChunkIndex: 0,

            getState() {
                return {
                    isSpeaking: this.isSpeaking,
                    isPaused: this.isPaused,
                    currentChunkIndex: this.currentChunkIndex,
                    chunks: this.chunks,
                    title: this.title,
                };
            },

            start(chunks: string[], title: string) {
                this.chunks = [...chunks];
                this.title = title;
                this.isSpeaking = true;
                this.isPaused = false;
                this.currentChunkIndex = 0;
                this.emitStateChange();
            },

            stop: jest.fn().mockImplementation(async () => {
                this.isSpeaking = false;
                this.isPaused = false;
                this.currentChunkIndex = 0;
                this.chunks = [];
                this.emitStateChange();
            }),

            pause: jest.fn().mockImplementation(async () => {
                this.isPaused = true;
                this.emitStateChange();
            }),

            resume: jest.fn().mockImplementation(() => {
                this.isPaused = false;
                this.emitStateChange();
            }),

            playPause: jest.fn().mockImplementation(async () => {
                if (this.isPaused) {
                    this.isPaused = false;
                } else {
                    this.isPaused = true;
                }
                this.emitStateChange();
            }),

            next: jest.fn().mockImplementation(async () => {
                if (this.currentChunkIndex < this.chunks.length - 1) {
                    this.currentChunkIndex++;
                    this.emitStateChange();
                }
            }),

            previous: jest.fn().mockImplementation(async () => {
                if (this.currentChunkIndex > 0) {
                    this.currentChunkIndex--;
                    this.emitStateChange();
                }
            }),

            updateSettings(settings: any) {
                // Settings updated
            },

            setOnFinishCallback(callback: any) {
                this.onFinishCallback = callback;
            },

            emitStateChange() {
                const { DeviceEventEmitter } = require('react-native');
                const { TTS_STATE_EVENTS } = require('../../services/TTSStateManager');
                DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, this.getState());
            },
        };

        return mockControllerInstance;
    };

    return {
        TTSPlaybackController: class MockTTSPlaybackController {
            constructor() {
                return createMockController();
            }
        },
        getMockController() {
            return mockControllerInstance;
        },
    };
});

jest.mock('react-native', () => ({
    DeviceEventEmitter: {
        emit: jest.fn(),
        addListener: jest.fn(() => ({ remove: jest.fn() })),
    },
    Platform: { OS: 'ios' },
}));

jest.mock('expo-constants', () => ({
    executionEnvironment: 'bare',
}));

jest.mock('../../services/NotifeeTypes', () => ({
    loadNotifee: jest.fn(() => null),
}));

jest.mock('../../services/TTSNotificationService', () => ({
    ttsNotificationService: {
        startService: jest.fn().mockResolvedValue(undefined),
        stopService: jest.fn().mockResolvedValue(undefined),
        updateNotification: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../../services/ForegroundServiceCoordinator', () => ({
    setTtsState: jest.fn().mockResolvedValue(undefined),
    clearTtsState: jest.fn().mockResolvedValue(undefined),
}));

describe('useTTS - Concurrent Operations', () => {
    let getMockController: any;

    beforeEach(() => {
        jest.clearAllMocks();
        const mockModule = require('../../services/tts/TTSPlaybackController');
        getMockController = mockModule.getMockController;

        // Reset the ttsStateManager singleton
        jest.resetModules();
    });

    describe('Rapid Control Changes', () => {
        it('should handle rapid play/pause toggles', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3'];

            // Start playback
            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            expect(result.current.isSpeaking).toBe(true);

            // Rapid toggles
            await act(async () => {
                await result.current.handlePlayPause();
                await result.current.handlePlayPause();
                await result.current.handlePlayPause();
            });

            // Should be paused after first toggle, playing after second, paused after third
            expect(result.current.isPaused).toBe(true);
        });

        it('should handle rapid next/previous navigation', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3', 'chunk4', 'chunk5'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            expect(result.current.currentChunkIndex).toBe(0);

            // Navigate rapidly
            await act(async () => {
                await result.current.handleNextChunk();
                await result.current.handleNextChunk();
                await result.current.handlePreviousChunk();
            });

            // Should end up at index 1 (started 0, +1 = 1, +1 = 2, -1 = 1)
            expect(result.current.currentChunkIndex).toBe(1);
        });

        it('should handle mixed control sequences', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3', 'chunk4'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            // Complex sequence: pause, next, resume, next, previous
            await act(async () => {
                await result.current.handlePlayPause();
                expect(result.current.isPaused).toBe(true);

                await result.current.handleNextChunk();
                await result.current.handlePlayPause(); // Resume
                await result.current.handleNextChunk();
                await result.current.handlePreviousChunk();
            });

            // State should be consistent
            expect(result.current.isSpeaking).toBe(true);
        });
    });

    describe('Start/Stop During Playback', () => {
        it('should handle toggleSpeech to stop during active playback', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks1 = ['chunk1', 'chunk2', 'chunk3'];

            // Start playback
            await act(async () => {
                await result.current.toggleSpeech(chunks1, 'Story 1');
            });

            expect(result.current.isSpeaking).toBe(true);
            expect(result.current.isControllerVisible).toBe(true);

            // Stop by toggling again
            await act(async () => {
                await result.current.toggleSpeech(chunks1, 'Story 1');
            });

            expect(result.current.isSpeaking).toBe(false);
            expect(result.current.isControllerVisible).toBe(false);
        });

        it('should handle starting new content during active playback', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks1 = ['chunk1', 'chunk2'];
            const chunks2 = ['chapter1', 'chapter2', 'chapter3'];

            // Start first content
            await act(async () => {
                await result.current.toggleSpeech(chunks1, 'Story 1');
            });

            expect(result.current.chunks).toEqual(chunks1);

            // Start new content (should stop first and start new)
            await act(async () => {
                await result.current.toggleSpeech(chunks2, 'Story 2');
            });

            // Should now be playing new content
            expect(result.current.chunks).toEqual(chunks2);
        });
    });

    describe('State Synchronization', () => {
        it('should sync state from DeviceEventEmitter during rapid changes', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            const { DeviceEventEmitter } = require('react-native');

            // Simulate rapid state changes from the manager
            await act(async () => {
                DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, {
                    isSpeaking: true,
                    isPaused: false,
                    currentChunkIndex: 0,
                    chunks: chunks,
                    title: 'Test Story',
                });

                DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, {
                    isSpeaking: true,
                    isPaused: true,
                    currentChunkIndex: 1,
                    chunks: chunks,
                    title: 'Test Story',
                });

                DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, {
                    isSpeaking: true,
                    isPaused: false,
                    currentChunkIndex: 1,
                    chunks: chunks,
                    title: 'Test Story',
                });
            });

            // Final state should be synced
            expect(result.current.isSpeaking).toBe(true);
            expect(result.current.isPaused).toBe(false);
            expect(result.current.currentChunkIndex).toBe(1);
        });

        it('should hide controller when fully stopped', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            expect(result.current.isControllerVisible).toBe(true);

            // Stop playback
            await act(async () => {
                await result.current.stopSpeech();
            });

            // Controller should hide when fully stopped
            expect(result.current.isControllerVisible).toBe(false);
        });

        it('should keep controller visible when paused', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
                await result.current.handlePlayPause();
            });

            // Controller should still be visible when paused
            expect(result.current.isControllerVisible).toBe(true);
            expect(result.current.isPaused).toBe(true);
        });
    });

    describe('Settings Changes During Playback', () => {
        it('should handle settings updates during active playback', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2', 'chunk3'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            // Update settings while playing
            await act(async () => {
                await result.current.handleSettingsChange({ pitch: 1.5, rate: 1.2, chunkSize: 300 });
            });

            expect(result.current.ttsSettings).toEqual({ pitch: 1.5, rate: 1.2, chunkSize: 300 });
        });

        it('should handle multiple rapid settings changes', async () => {
            const { result } = renderHook(() => useTTS());

            const chunks = ['chunk1', 'chunk2'];

            await act(async () => {
                await result.current.toggleSpeech(chunks, 'Test Story');
            });

            await act(async () => {
                await Promise.all([
                    result.current.handleSettingsChange({ pitch: 1.2, rate: 1.0, chunkSize: 500 }),
                    result.current.handleSettingsChange({ pitch: 1.5, rate: 1.3, chunkSize: 500 }),
                    result.current.handleSettingsChange({ pitch: 1.0, rate: 1.0, chunkSize: 500 }),
                ]);
            });

            // Should settle on the last value
            expect(result.current.ttsSettings.pitch).toBe(1.0);
        });
    });

    describe('OnFinish Callback', () => {
        it('should handle onFinish callback updates', async () => {
            const callback1 = jest.fn();
            const callback2 = jest.fn();

            const { rerender } = renderHook(({ onFinish }) => useTTS({ onFinish }), {
                initialProps: { onFinish: callback1 },
            });

            const chunks = ['chunk1', 'chunk2'];

            await act(async () => {
                const controller = getMockController();
                if (controller?.onFinishCallback) {
                    controller.onFinishCallback();
                }
            });

            expect(callback1).toHaveBeenCalled();

            // Update callback
            rerender({ onFinish: callback2 });

            await act(async () => {
                const controller = getMockController();
                if (controller?.onFinishCallback) {
                    controller.onFinishCallback();
                }
            });

            expect(callback2).toHaveBeenCalled();
        });
    });

    describe('Empty/Invalid Input Handling', () => {
        it('should not start with empty chunks', async () => {
            const { result } = renderHook(() => useTTS());

            await act(async () => {
                await result.current.toggleSpeech([], 'Empty Story');
            });

            expect(result.current.isSpeaking).toBe(false);
        });

        it('should not start with null chunks', async () => {
            const { result } = renderHook(() => useTTS());

            await act(async () => {
                await result.current.toggleSpeech(null as any, 'Null Story');
            });

            expect(result.current.isSpeaking).toBe(false);
        });
    });
});
