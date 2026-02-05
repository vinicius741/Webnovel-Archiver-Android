
import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useStoryEPUB } from '../useStoryEPUB';
import { storageService } from '../../services/StorageService';
import { epubGenerator } from '../../services/EpubGenerator';
import { Story } from '../../types';

jest.mock('../../services/StorageService');
jest.mock('../../services/EpubGenerator');
jest.mock('../../services/DownloadService');

jest.mock('expo-file-system/legacy', () => ({
    getInfoAsync: jest.fn(),
    getContentUriAsync: jest.fn(),
}));
jest.mock('expo-intent-launcher', () => ({
    startActivityAsync: jest.fn(),
}));

const { getInfoAsync, getContentUriAsync } = require('expo-file-system/legacy');
const { startActivityAsync } = require('expo-intent-launcher');

const mockShowAlert = jest.fn();
jest.mock('../../context/AlertContext', () => ({
    useAppAlert: () => ({ showAlert: mockShowAlert }),
}));

describe('useStoryEPUB', () => {
    const mockStory: Story = {
        id: '1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com/story',
        coverUrl: 'http://cover',
        description: 'Test description',
        tags: [],
        status: 'completed',
        totalChapters: 10,
        downloadedChapters: 10,
        chapters: Array.from({ length: 10 }, (_, i) => ({
            id: `c${i}`,
            title: `Chapter ${i}`,
            url: `http://test.com/c${i}`,
            downloaded: true,
        })),
        dateAdded: 1000,
        lastUpdated: 2000,
    };

    const mockOnStoryUpdated = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        (getInfoAsync as jest.Mock).mockReset();
        (getContentUriAsync as jest.Mock).mockReset();
        (startActivityAsync as jest.Mock).mockReset();
        (startActivityAsync as jest.Mock).mockResolvedValue({});
        (getInfoAsync as jest.Mock).mockResolvedValue({ exists: true });
        (getContentUriAsync as jest.Mock).mockResolvedValue('content://uri');
        (storageService.getSettings as jest.Mock).mockResolvedValue({ maxChaptersPerEpub: 150 });
    });

    it('should generate epub when story has no epub path', async () => {
        const { result } = renderHook(() => useStoryEPUB({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        const mockProgress = { current: 5, total: 10, percentage: 50, stage: 'processing' as const };
        (epubGenerator.generateEpubs as jest.Mock).mockImplementation(async (_story, _chapters, _maxChapters, callback) => {
            callback(mockProgress);
            return [{
                uri: 'file://path/to/epub.epub',
                filename: 'test.epub',
                chapterRange: { start: 1, end: 10 }
            }];
        });
        (getInfoAsync as jest.Mock).mockResolvedValue({ exists: true });
        (getContentUriAsync as jest.Mock).mockResolvedValue('content://uri');

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(result.current.generating).toBe(false);
        expect(epubGenerator.generateEpubs).toHaveBeenCalledWith(
            mockStory,
            mockStory.chapters,
            150,
            expect.any(Function)
        );
        expect(storageService.addStory).toHaveBeenCalledWith(
            expect.objectContaining({ epubPath: 'file://path/to/epub.epub' })
        );
        expect(mockOnStoryUpdated).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith('Success', expect.stringContaining('EPUB exported'));
    });

    it('should read existing epub when story has epub path', async () => {
        const storyWithEpub = { ...mockStory, epubPath: 'file://existing.epub' };

        (getInfoAsync as jest.Mock).mockResolvedValue({ exists: true });
        (getContentUriAsync as jest.Mock).mockResolvedValue('content://uri');

        const { result } = renderHook(() => useStoryEPUB({ story: storyWithEpub, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(getInfoAsync).toHaveBeenCalledWith('file://existing.epub');
        expect(startActivityAsync).toHaveBeenCalledWith('android.intent.action.VIEW', {
            data: 'content://uri',
            flags: 1,
            type: 'application/epub+zip',
        });
        expect(epubGenerator.generateEpubs).not.toHaveBeenCalled();
    });

    it('should handle missing epub file gracefully', async () => {
        const storyWithEpub = { ...mockStory, epubPath: 'file://missing.epub' };

        (getInfoAsync as jest.Mock).mockResolvedValue({ exists: false });

        const { result } = renderHook(() => useStoryEPUB({ story: storyWithEpub, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Error', expect.stringContaining('not found'));
        expect(storageService.addStory).toHaveBeenCalledWith(
            expect.objectContaining({ epubPath: undefined })
        );
        expect(mockOnStoryUpdated).toHaveBeenCalled();
    });

    it('should show alert when story is null', async () => {
        const { result } = renderHook(() => useStoryEPUB({ story: null, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).not.toHaveBeenCalled();
        expect(mockShowAlert).not.toHaveBeenCalled();
    });

    it('should handle generate epub error', async () => {
        const { result } = renderHook(() => useStoryEPUB({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        (epubGenerator.generateEpubs as jest.Mock).mockRejectedValue(new Error('Generation failed'));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(result.current.generating).toBe(false);
        expect(mockShowAlert).toHaveBeenCalledWith('Error', 'Generation failed');
    });

    it('should update progress during epub generation', async () => {
        (epubGenerator.generateEpubs as jest.Mock).mockImplementation(async (_story, _chapters, _maxChapters, callback) => {
            await new Promise(resolve => setTimeout(resolve, 10));
            callback({ current: 2, total: 10, percentage: 20, stage: 'filtering' as const });
            await new Promise(resolve => setTimeout(resolve, 10));
            callback({ current: 5, total: 10, percentage: 50, stage: 'processing' as const });
            await new Promise(resolve => setTimeout(resolve, 10));
            callback({ current: 10, total: 10, percentage: 100, stage: 'finalizing' as const });
            await new Promise(resolve => setTimeout(resolve, 10));
            return [{
                uri: 'file://path/to/epub.epub',
                filename: 'test.epub',
                chapterRange: { start: 1, end: 10 }
            }];
        });

        const { result } = renderHook(() => useStoryEPUB({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        let finalStage: string | undefined = undefined;
        act(() => {
            result.current.generateOrRead().then(() => {
                finalStage = result.current.progress?.stage;
            });
        });

        await waitFor(() => {
            const progress = result.current.progress;
            if (progress && progress.stage === 'finalizing') {
                expect(progress.stage).toBe('finalizing');
            }
        }, { timeout: 3000 });
    });

    it('should not generate epub if story is invalid', async () => {
        const invalidStory: Story | null = null;

        const { result } = renderHook(() => useStoryEPUB({ story: invalidStory, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).not.toHaveBeenCalled();
        expect(mockShowAlert).not.toHaveBeenCalled();
    });

    it('should handle read error for existing epub', async () => {
        const storyWithEpub = { ...mockStory, epubPath: 'file://error.epub' };

        (getInfoAsync as jest.Mock).mockRejectedValue(new Error('File system error'));

        const { result } = renderHook(() => useStoryEPUB({ story: storyWithEpub, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Read Error', expect.stringContaining('Could not open EPUB'));
    });
});
