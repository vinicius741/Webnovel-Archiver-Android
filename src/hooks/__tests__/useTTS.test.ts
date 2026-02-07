import { renderHook, act } from '@testing-library/react-native';
import { useTTS } from '../useTTS';
import { ttsStateManager, TTS_STATE_EVENTS } from '../../services/TTSStateManager';
import * as Speech from 'expo-speech';

jest.mock('../../services/TTSStateManager');
jest.mock('expo-speech');
jest.mock('expo-constants', () => ({
    executionEnvironment: 'standalone',
}));

describe('useTTS', () => {
    const mockChunks = ['Chunk 1', 'Chunk 2', 'Chunk 3'];
    const mockOnFinish = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        (ttsStateManager.getState as jest.Mock).mockReturnValue(null);
        (ttsStateManager.getSettings as jest.Mock).mockReturnValue({
            pitch: 1.0,
            rate: 1.0,
            chunkSize: 500,
        });
        (ttsStateManager.setOnFinishCallback as jest.Mock).mockReturnValue(undefined);
    });

    it('should initialize with default state', () => {
        const { result } = renderHook(() => useTTS());

        expect(result.current.isSpeaking).toBe(false);
        expect(result.current.isPaused).toBe(false);
        expect(result.current.chunks).toEqual([]);
        expect(result.current.currentChunkIndex).toBe(0);
    });

    it('should load TTS settings on mount', () => {
        const { result } = renderHook(() => useTTS());

        expect(ttsStateManager.getSettings).toHaveBeenCalled();
        expect(result.current.ttsSettings).toEqual({
            pitch: 1.0,
            rate: 1.0,
            chunkSize: 500,
        });
    });

    it('should set onFinish callback', () => {
        renderHook(() => useTTS({ onFinish: mockOnFinish }));

        expect(ttsStateManager.setOnFinishCallback).toHaveBeenCalledWith(expect.any(Function));
    });

    it('should start speech with new chunks', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.toggleSpeech(mockChunks, 'Test Story');
        });

        expect(ttsStateManager.start).toHaveBeenCalledWith({
            chunks: mockChunks,
            title: 'Test Story',
            storyId: '',
            chapterId: '',
            chapterTitle: 'Test Story',
        });
        expect(result.current.isControllerVisible).toBe(true);
    });

    it('should stop speech when already speaking', async () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: true,
            isPaused: false,
            chunks: mockChunks,
            currentChunkIndex: 1,
        });

        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.toggleSpeech(mockChunks, 'Test Story');
        });

        expect(ttsStateManager.stop).toHaveBeenCalled();
    });

    it('should stop speech when paused', async () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: true,
            isPaused: true,
            chunks: mockChunks,
            currentChunkIndex: 1,
        });

        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.toggleSpeech(mockChunks, 'Test Story');
        });

        expect(ttsStateManager.stop).toHaveBeenCalled();
    });

    it('should not start speech with empty chunks', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.toggleSpeech([], 'Test Story');
        });

        expect(ttsStateManager.start).not.toHaveBeenCalled();
    });

    it('should handle stopSpeech', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.stopSpeech();
        });

        expect(ttsStateManager.stop).toHaveBeenCalled();
    });

    it('should handle handlePlayPause', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.handlePlayPause();
        });

        expect(ttsStateManager.playPause).toHaveBeenCalled();
    });

    it('should handle handleNextChunk', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.handleNextChunk();
        });

        expect(ttsStateManager.next).toHaveBeenCalled();
    });

    it('should handle handlePreviousChunk', async () => {
        const { result } = renderHook(() => useTTS());

        await act(async () => {
            await result.current.handlePreviousChunk();
        });

        expect(ttsStateManager.previous).toHaveBeenCalled();
    });

    it('should update settings', async () => {
        const { result } = renderHook(() => useTTS());
        const newSettings = { pitch: 1.5, rate: 0.8, chunkSize: 600 };

        await act(async () => {
            await result.current.handleSettingsChange(newSettings);
        });

        expect(ttsStateManager.updateSettings).toHaveBeenCalledWith(newSettings);
        expect(result.current.ttsSettings).toEqual(newSettings);
    });

    it('should toggle settings visibility', () => {
        const { result } = renderHook(() => useTTS());

        expect(result.current.isSettingsVisible).toBe(false);

        act(() => {
            result.current.setIsSettingsVisible(true);
        });

        expect(result.current.isSettingsVisible).toBe(true);
    });

    it('should toggle controller visibility', () => {
        const { result } = renderHook(() => useTTS());

        expect(result.current.isControllerVisible).toBe(false);

        act(() => {
            result.current.setIsControllerVisible(true);
        });

        expect(result.current.isControllerVisible).toBe(true);
    });

    it('should sync state from TTSStateManager', () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: true,
            isPaused: false,
            chunks: mockChunks,
            currentChunkIndex: 1,
        });

        const { result } = renderHook(() => useTTS());

        expect(result.current.isSpeaking).toBe(true);
        expect(result.current.isPaused).toBe(false);
        expect(result.current.chunks).toEqual(mockChunks);
        expect(result.current.currentChunkIndex).toBe(1);
    });

    it('should show controller when speaking', () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: true,
            isPaused: false,
            chunks: mockChunks,
            currentChunkIndex: 1,
        });

        const { result } = renderHook(() => useTTS());

        expect(result.current.isControllerVisible).toBe(true);
    });

    it('should hide controller when fully stopped', () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: false,
            isPaused: false,
            chunks: [],
            currentChunkIndex: 0,
        });

        const { result } = renderHook(() => useTTS());

        expect(result.current.isControllerVisible).toBe(false);
    });

    it('should keep controller visible when paused', () => {
        (ttsStateManager.getState as jest.Mock).mockReturnValue({
            isSpeaking: true,
            isPaused: true,
            chunks: mockChunks,
            currentChunkIndex: 1,
        });

        const { result } = renderHook(() => useTTS());

        expect(result.current.isControllerVisible).toBe(true);
    });

    it('should handle state change updates', () => {
        const { result } = renderHook(() => useTTS());

        const { DeviceEventEmitter } = require('react-native');

        act(() => {
            DeviceEventEmitter.emit(TTS_STATE_EVENTS.STATE_CHANGED, {
                isSpeaking: true,
                isPaused: false,
                chunks: mockChunks,
                currentChunkIndex: 2,
            });
        });

        expect(result.current.isSpeaking).toBe(true);
        expect(result.current.currentChunkIndex).toBe(2);
    });

    it('should update onFinish callback when options change', () => {
        const { rerender } = renderHook(
            ({ onFinish }: { onFinish?: () => void }) => useTTS({ onFinish }),
            { initialProps: { onFinish: undefined as (() => void) | undefined } }
        );

        expect(ttsStateManager.setOnFinishCallback).toHaveBeenCalled();

        rerender({ onFinish: mockOnFinish });

        expect(ttsStateManager.setOnFinishCallback).toHaveBeenCalled();
    });
});
