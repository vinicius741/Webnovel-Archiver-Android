import { renderHook, act } from '@testing-library/react-native';

import { useStoryEPUB } from '../useStoryEPUB';
import { storageService } from '../../services/StorageService';
import { epubGenerator } from '../../services/EpubGenerator';
import { Story } from '../../types';

jest.mock('../../services/StorageService');
jest.mock('../../services/EpubGenerator');
jest.mock('../../services/DownloadService', () => ({
    downloadService: {
        downloadChaptersByIds: jest.fn(),
    },
}));

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
    const mockOnStoryUpdated = jest.fn();

    const baseStory: Story = {
        id: '1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com/story',
        coverUrl: 'http://cover',
        description: 'Test description',
        tags: [],
        status: 'completed',
        totalChapters: 6,
        downloadedChapters: 4,
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://test.com/c1', downloaded: true },
            { id: 'c2', title: 'Chapter 2', url: 'http://test.com/c2', downloaded: false },
            { id: 'c3', title: 'Chapter 3', url: 'http://test.com/c3', downloaded: true },
            { id: 'c4', title: 'Chapter 4', url: 'http://test.com/c4', downloaded: true },
            { id: 'c5', title: 'Chapter 5', url: 'http://test.com/c5', downloaded: false },
            { id: 'c6', title: 'Chapter 6', url: 'http://test.com/c6', downloaded: true },
        ],
        dateAdded: 1000,
        lastUpdated: 2000,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        (storageService.getSettings as jest.Mock).mockResolvedValue({ maxChaptersPerEpub: 150 });
        (getInfoAsync as jest.Mock).mockResolvedValue({ exists: true });
        (getContentUriAsync as jest.Mock).mockResolvedValue('content://uri');
        (startActivityAsync as jest.Mock).mockResolvedValue({});
        (epubGenerator.generateEpubs as jest.Mock).mockResolvedValue([{
            uri: 'file://path/to/epub.epub',
            filename: 'test.epub',
            chapterRange: { start: 1, end: 1 },
        }]);
    });

    it('generates EPUBs using only downloaded chapters', async () => {
        const { result } = renderHook(() => useStoryEPUB({
            story: baseStory,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).toHaveBeenCalledWith(
            baseStory,
            [baseStory.chapters[0], baseStory.chapters[2], baseStory.chapters[3], baseStory.chapters[5]],
            150,
            expect.any(Function),
            [1, 3, 4, 6]
        );
    });

    it('applies configured chapter range before generation', async () => {
        const storyWithRange: Story = {
            ...baseStory,
            epubConfig: {
                maxChaptersPerEpub: 120,
                rangeStart: 2,
                rangeEnd: 4,
                startAfterBookmark: false,
            },
        };

        const { result } = renderHook(() => useStoryEPUB({
            story: storyWithRange,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).toHaveBeenCalledWith(
            storyWithRange,
            [baseStory.chapters[2], baseStory.chapters[3]],
            120,
            expect.any(Function),
            [3, 4]
        );
    });

    it('applies start-after-bookmark when enabled', async () => {
        const allDownloadedChapters = baseStory.chapters.map(ch => ({ ...ch, downloaded: true }));
        const storyWithBookmark: Story = {
            ...baseStory,
            chapters: allDownloadedChapters,
            downloadedChapters: allDownloadedChapters.length,
            lastReadChapterId: 'c3',
            epubConfig: {
                maxChaptersPerEpub: 100,
                rangeStart: 1,
                rangeEnd: 6,
                startAfterBookmark: true,
            },
        };

        const { result } = renderHook(() => useStoryEPUB({
            story: storyWithBookmark,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).toHaveBeenCalledWith(
            storyWithBookmark,
            [allDownloadedChapters[3], allDownloadedChapters[4], allDownloadedChapters[5]],
            100,
            expect.any(Function),
            [4, 5, 6]
        );
    });

    it('warns and falls back to configured range when bookmark chapter is missing', async () => {
        const storyWithMissingBookmark: Story = {
            ...baseStory,
            lastReadChapterId: 'missing-chapter-id',
            epubConfig: {
                maxChaptersPerEpub: 100,
                rangeStart: 3,
                rangeEnd: 4,
                startAfterBookmark: true,
            },
        };

        const { result } = renderHook(() => useStoryEPUB({
            story: storyWithMissingBookmark,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(mockShowAlert).toHaveBeenCalledWith(
            'Bookmark Not Found',
            'Your saved bookmark is no longer in this chapter list. Using the configured chapter range instead.'
        );
        expect(epubGenerator.generateEpubs).toHaveBeenCalledWith(
            storyWithMissingBookmark,
            [baseStory.chapters[2], baseStory.chapters[3]],
            100,
            expect.any(Function),
            [3, 4]
        );
    });

    it('aborts when selected scope has no downloaded chapters', async () => {
        const storyNoDownloadedInRange: Story = {
            ...baseStory,
            epubConfig: {
                maxChaptersPerEpub: 150,
                rangeStart: 2,
                rangeEnd: 2,
                startAfterBookmark: false,
            },
        };

        const { result } = renderHook(() => useStoryEPUB({
            story: storyNoDownloadedInRange,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(epubGenerator.generateEpubs).not.toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith(
            'No Downloaded Chapters',
            'No downloaded chapters found in the selected EPUB range.'
        );
    });

    it('reads existing epub when epub path exists and is not stale', async () => {
        const storyWithEpub: Story = {
            ...baseStory,
            epubPath: 'file://existing.epub',
            epubStale: false,
        };
        const { result } = renderHook(() => useStoryEPUB({
            story: storyWithEpub,
            onStoryUpdated: mockOnStoryUpdated,
        }));

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

    it('handles generator errors', async () => {
        (epubGenerator.generateEpubs as jest.Mock).mockRejectedValue(new Error('Generation failed'));

        const { result } = renderHook(() => useStoryEPUB({
            story: baseStory,
            onStoryUpdated: mockOnStoryUpdated,
        }));

        await act(async () => {
            await result.current.generateOrRead();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Error', 'Generation failed');
    });
});
