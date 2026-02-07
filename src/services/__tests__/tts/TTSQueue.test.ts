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
        onChunkStart: jest.fn(),
        onChunkComplete: jest.fn(),
        onError: jest.fn(),
    };

    const mockChunks = ['Chunk 1', 'Chunk 2', 'Chunk 3'];

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should play a single chunk by index', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(1);

        expect(Speech.speak).toHaveBeenCalledTimes(1);
        expect(Speech.speak).toHaveBeenCalledWith(
            'Chunk 2',
            expect.objectContaining({
                pitch: 1.0,
                rate: 1.0,
                voice: undefined,
            })
        );
    });

    it('should ignore invalid chunk indexes', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(-1);
        queue.playChunk(99);
        expect(Speech.speak).not.toHaveBeenCalled();
    });

    it('should expose active chunk while speaking', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(0);
        expect(queue.getActiveChunkIndex()).toBe(0);
    });

    it('should call chunk start callback', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(0);
        const speakOptions = (Speech.speak as jest.Mock).mock.calls[0][1];
        speakOptions.onStart();
        expect(mockConfig.onChunkStart).toHaveBeenCalledWith(0);
    });

    it('should call chunk complete callback and clear active index', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(0);
        const speakOptions = (Speech.speak as jest.Mock).mock.calls[0][1];
        speakOptions.onDone();
        expect(mockConfig.onChunkComplete).toHaveBeenCalledWith(0);
        expect(queue.getActiveChunkIndex()).toBeNull();
    });

    it('should call onError callback and clear active index', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        const error = new Error('Speech failed');
        queue.playChunk(0);
        const speakOptions = (Speech.speak as jest.Mock).mock.calls[0][1];
        speakOptions.onError(error);
        expect(mockConfig.onError).toHaveBeenCalledWith(error);
        expect(queue.getActiveChunkIndex()).toBeNull();
    });

    it('should cancel prior callbacks after invalidate', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(0);
        const firstOptions = (Speech.speak as jest.Mock).mock.calls[0][1];
        queue.invalidate();
        firstOptions.onDone();
        expect(mockConfig.onChunkComplete).not.toHaveBeenCalled();
    });

    it('should stop speech and invalidate callbacks', async () => {
        (Speech.stop as jest.Mock).mockResolvedValue(undefined);
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.playChunk(0);
        const firstOptions = (Speech.speak as jest.Mock).mock.calls[0][1];

        await queue.stop();
        firstOptions.onDone();

        expect(Speech.stop).toHaveBeenCalled();
        expect(mockConfig.onChunkComplete).not.toHaveBeenCalled();
    });

    it('should update settings for subsequent chunks', () => {
        const queue = new TTSQueue(mockChunks, mockSettings, mockConfig);
        queue.updateSettings({ pitch: 1.5, rate: 0.8, chunkSize: 500, voiceIdentifier: 'voice-id' });
        queue.playChunk(0);

        expect(Speech.speak).toHaveBeenCalledWith(
            'Chunk 1',
            expect.objectContaining({
                pitch: 1.5,
                rate: 0.8,
                voice: 'voice-id',
            })
        );
    });
});
