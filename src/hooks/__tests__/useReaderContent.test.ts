
import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useReaderContent } from '../useReaderContent';
import { storageService } from '../../services/StorageService';
import { readChapterFile } from '../../services/storage/fileSystem';
import { Story, Chapter } from '../../types';

jest.mock('../../services/StorageService');
jest.mock('../../services/storage/fileSystem');

describe('useReaderContent', () => {
    const mockStory: Story = {
        id: 'story1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com',
        coverUrl: 'http://cover',
        description: 'Test description',
        tags: [],
        status: 'completed',
        totalChapters: 3,
        downloadedChapters: 3,
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true, filePath: '/path/c1.html' },
            { id: 'c2', title: 'Chapter 2', url: 'http://c2', downloaded: true, filePath: '/path/c2.html' },
            { id: 'c3', title: 'Chapter 3', url: 'http://c3', downloaded: true, filePath: '/path/c3.html' },
        ],
        dateAdded: 1000,
        lastUpdated: 2000,
        lastReadChapterId: 'c1',
    };

    const mockChapterContent = '<html><body><p>Chapter content</p></body></html>';

    beforeEach(() => {
        jest.clearAllMocks();
    });

    it('should load story and chapter content', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.story).toEqual(mockStory);
        expect(result.current.chapter).toEqual(mockStory.chapters[0]);
        expect(result.current.content).toBe(mockChapterContent);
        expect(result.current.error).toBeNull();
    });

    it('should decode chapter id when loading', async () => {
        const encodedId = encodeURIComponent('c2');
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', encodedId));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.chapter?.id).toBe('c2');
        expect(readChapterFile).toHaveBeenCalledWith('/path/c2.html');
    });

    it('should set error when story not found', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(null);

        const { result } = renderHook(() => useReaderContent('invalid', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.error).toBe('Story not found.');
        expect(result.current.story).toBeNull();
        expect(result.current.redirectPath).toBe('/');
    });

    it('should set error when chapter not found', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);

        const { result } = renderHook(() => useReaderContent('story1', 'invalid'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.error).toBe('Chapter not found.');
        expect(result.current.chapter).toBeNull();
        expect(result.current.redirectPath).toBe('/details/story1');
    });

    it('should handle undownloaded chapters', async () => {
        const storyWithUndownloaded = {
            ...mockStory,
            chapters: [
                { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: false },
            ],
        };
        (storageService.getStory as jest.Mock).mockResolvedValue(storyWithUndownloaded);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.content).toBe('Chapter not downloaded yet.');
        expect(readChapterFile).not.toHaveBeenCalled();
    });

    it('should handle read chapter file error', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockRejectedValue(new Error('File read error'));

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.error).toBe('File read error');
    });

    it('should mark chapter as read', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);
        (storageService.updateLastRead as jest.Mock).mockResolvedValue(undefined);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        await act(async () => {
            await result.current.markAsRead();
        });

        expect(storageService.updateLastRead).toHaveBeenCalledWith('story1', 'c1');
        expect(result.current.story?.lastReadChapterId).toBe('c1');
    });

    it('should not mark as read if story or chapter is null', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(null);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        await act(async () => {
            await result.current.markAsRead();
        });

        expect(storageService.updateLastRead).not.toHaveBeenCalled();
    });

    it('should calculate current index', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c2'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.currentIndex).toBe(1);
    });

    it('should handle edge case - first chapter index', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.currentIndex).toBe(0);
    });

    it('should handle edge case - last chapter index', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c3'));

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.currentIndex).toBe(2);
    });

    it('should return -1 for index when chapter not found', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);

        const { result } = renderHook(() => useReaderContent('story1', 'invalid'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.currentIndex).toBe(-1);
    });

    it('should check if chapter is last read', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.isLastRead).toBe(true);
    });

    it('should check if chapter is not last read', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock).mockResolvedValue(mockChapterContent);

        const { result } = renderHook(() => useReaderContent('story1', 'c2'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.isLastRead).toBe(false);
    });

    it('should return false for isLastRead when story is null', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(null);

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.isLastRead).toBe(false);
    });

    it('should support reloading content with loadData', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (readChapterFile as jest.Mock)
            .mockResolvedValueOnce('<p>First load</p>')
            .mockResolvedValueOnce('<p>Second load</p>');

        const { result } = renderHook(() => useReaderContent('story1', 'c1'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.content).toBe('<p>First load</p>');

        await act(async () => {
            await result.current.loadData();
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.content).toBe('<p>Second load</p>');
    });
});
