import { TTSQueue } from '../../tts/TTSQueue';
import * as Speech from 'expo-speech';

jest.mock('expo-speech');

describe('TTSQueue', () => {
    const mockSettings = {
        pitch: 1.0,
        rate: 1.0,
        chunkSize: 500,
    };

    const mockConfig = {
        bufferSize: 3,
        onChunkStart: jest.fn(),
        onChunkComplete: jest.fn(),
        onError: jest.fn(),
    };

    const mockChunks = ['Chunk 1', 'Chunk 2', 'Chunk 3', 'Chunk 4', 'Chunk 5'];

    beforeEach(() => {
        jest.clearAllMocks();
        (Speech.speak as jest.Mock).mockReturnValue('test-utterance-id');
    });

    it('should create queue with chunks and settings', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        expect(queue).toBeInstanceOf(TTSQueue);
    });

    it('should start buffering from index 0', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        expect(queue.getBufferedIndex()).toBe(0);
    });

    it('should return correct queue length', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        expect(queue.getLength()).toBe(5);
    });

    it('should process queue up to buffer size', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);

        expect(Speech.speak).toHaveBeenCalledTimes(3);
    });

    it('should update settings', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        const newSettings = { pitch: 1.5, rate: 0.8, chunkSize: 600 };

        queue.updateSettings(newSettings);

        queue.processQueue(0);

        expect(Speech.speak).toHaveBeenCalledWith(
            'Chunk 1',
            expect.objectContaining({
                pitch: 1.5,
                rate: 0.8,
            })
        );
    });

    it('should update chunks and reset buffer', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        const newChunks = ['New Chunk 1', 'New Chunk 2'];

        queue.processQueue(0);
        expect(queue.getBufferedIndex()).toBe(3);

        queue.updateChunks(newChunks);
        expect(queue.getLength()).toBe(2);
        expect(queue.getBufferedIndex()).toBe(0);
    });

    it('should call onChunkStart when speech starts', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);

        const speakCalls = (Speech.speak as jest.Mock).mock.calls;
        expect(speakCalls[0][1].onStart).toBeDefined();

        speakCalls[0][1].onStart();
        expect(mockConfig.onChunkStart).toHaveBeenCalledWith(0);
    });

    it('should call onChunkComplete when speech completes', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);

        const speakCalls = (Speech.speak as jest.Mock).mock.calls;
        speakCalls[0][1].onDone();
        expect(mockConfig.onChunkComplete).toHaveBeenCalledWith(0);
    });

    it('should call onError when speech errors', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        const testError = new Error('Speech error');

        queue.processQueue(0);

        const speakCalls = (Speech.speak as jest.Mock).mock.calls;
        speakCalls[0][1].onError(testError);
        expect(mockConfig.onError).toHaveBeenCalledWith(testError);
    });

    it('should continue processing after buffer size', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);
        expect(Speech.speak).toHaveBeenCalledTimes(3);

        queue.processQueue(3);
        expect(Speech.speak).toHaveBeenCalledTimes(5);
    });

    it('should not process beyond queue length', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);
        queue.processQueue(4);

        expect(Speech.speak).toHaveBeenCalledTimes(5);
    });

    it('should reset buffer to current index', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);

        queue.processQueue(0);
        expect(queue.getBufferedIndex()).toBe(3);

        queue.resetBuffer(2);
        expect(queue.getBufferedIndex()).toBe(2);
    });

    it('should use custom buffer size', () => {
        const customConfig = { ...mockConfig, bufferSize: 2 };
        const queue = new TTSQueue(mockChunks, mockSettings, customConfig);

        queue.processQueue(0);

        expect(Speech.speak).toHaveBeenCalledTimes(2);
    });

    it('should respect voiceIdentifier in settings', () => {
        const settingsWithVoice = { ...mockSettings, voiceIdentifier: 'com.apple.ttsbundle.Samantha-compact' };
        const queue = new TTSQueue(mockChunks, settingsWithVoice, mockConfig);

        queue.processQueue(0);

        expect(Speech.speak).toHaveBeenCalledWith(
            'Chunk 1',
            expect.objectContaining({
                voice: 'com.apple.ttsbundle.Samantha-compact',
            })
        );
    });

    it('should handle empty chunks array', () => {
        const emptyQueue = new TTSQueue([], mockSettings, mockConfig);

        emptyQueue.processQueue(0);

        expect(Speech.speak).not.toHaveBeenCalled();
        expect(emptyQueue.getLength()).toBe(0);
    });
});
