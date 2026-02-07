import * as Speech from 'expo-speech';
import { TTSPlaybackController } from '../../tts/TTSPlaybackController';

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

    const chunks = ['Chunk 1', 'Chunk 2', 'Chunk 3'];

    beforeEach(() => {
        jest.clearAllMocks();
        (Speech.stop as jest.Mock).mockResolvedValue(undefined);
    });

    const getSpeakOptions = (index: number = 0) => {
        return (Speech.speak as jest.Mock).mock.calls[index][1];
    };

    it('should start playback from first chunk by default', () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');

        expect(controller.getState().isSpeaking).toBe(true);
        expect(controller.getState().isPaused).toBe(false);
        expect(controller.getState().currentChunkIndex).toBe(0);
        expect(Speech.speak).toHaveBeenCalledTimes(1);
        expect(mockConfig.onChunkChange).toHaveBeenCalledWith(0);
    });

    it('should start from a specific chunk index', () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story', { startChunkIndex: 2 });

        expect(controller.getState().currentChunkIndex).toBe(2);
        expect(Speech.speak).toHaveBeenCalledWith('Chunk 3', expect.any(Object));
    });

    it('should start paused without speaking', () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story', { startPaused: true });

        expect(controller.getState().isPaused).toBe(true);
        expect(controller.getState().isSpeaking).toBe(true);
        expect(Speech.speak).not.toHaveBeenCalled();
    });

    it('should progress deterministically on chunk completion', () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');

        getSpeakOptions(0).onDone();
        expect(controller.getState().currentChunkIndex).toBe(1);
        expect(Speech.speak).toHaveBeenCalledTimes(2);
        expect((Speech.speak as jest.Mock).mock.calls[1][0]).toBe('Chunk 2');
    });

    it('should stop and call finish callback at the end', async () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');

        getSpeakOptions(0).onDone();
        getSpeakOptions(1).onDone();
        getSpeakOptions(2).onDone();

        await Promise.resolve();
        await Promise.resolve();
        expect(mockConfig.onFinish).toHaveBeenCalled();
        expect(controller.getState().isSpeaking).toBe(false);
    });

    it('should pause and resume from current chunk', async () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');
        getSpeakOptions(0).onDone();

        await controller.pause();
        expect(controller.getState().isPaused).toBe(true);
        expect(Speech.stop).toHaveBeenCalled();

        controller.resume();
        expect(controller.getState().isPaused).toBe(false);
        const calls = (Speech.speak as jest.Mock).mock.calls;
        expect(calls[calls.length - 1][0]).toBe('Chunk 2');
    });

    it('should jump next and previous deterministically', async () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');

        await controller.next();
        expect(controller.getState().currentChunkIndex).toBe(1);

        await controller.previous();
        expect(controller.getState().currentChunkIndex).toBe(0);
    });

    it('should restart current chunk for silent-stop recovery', async () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');
        getSpeakOptions(0).onDone();

        await controller.restartCurrentChunk();
        const calls = (Speech.speak as jest.Mock).mock.calls;
        expect(calls[calls.length - 1][0]).toBe('Chunk 2');
    });

    it('should ignore callbacks from invalidated play token', async () => {
        const controller = new TTSPlaybackController(chunks, 'Story', mockSettings, mockConfig);
        controller.start(chunks, 'Story');
        const firstSpeakOptions = getSpeakOptions(0);
        await controller.next();

        firstSpeakOptions.onDone();
        expect(controller.getState().currentChunkIndex).toBe(1);
        expect(Speech.speak).toHaveBeenCalledTimes(2);
    });
});
