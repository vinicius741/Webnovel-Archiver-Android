import { renderHook, act } from '@testing-library/react-native';
import { useLibrary } from '../useLibrary';
import { storageService } from '../../services/StorageService';
import { sourceRegistry } from '../../services/source/SourceRegistry';
import { Story, DownloadStatus } from '../../types';

jest.mock('../../services/StorageService', () => ({
    storageService: {
        getLibrary: jest.fn(),
    },
}));

jest.mock('../../services/source/SourceRegistry');

describe('useLibrary', () => {
    const mockStories: Story[] = [
        {
            id: '1',
            title: 'Story Alpha',
            author: 'Author A',
            sourceUrl: 'http://example.com/story1',
            coverUrl: 'http://cover1',
            chapters: [],
            status: DownloadStatus.Completed,
            totalChapters: 10,
            downloadedChapters: 10,
            dateAdded: 1000,
            lastUpdated: 2000,
            tags: ['action', 'fantasy'],
        },
        {
            id: '2',
            title: 'Story Beta',
            author: 'Author B',
            sourceUrl: 'http://other.com/story2',
            coverUrl: 'http://cover2',
            chapters: [],
            status: DownloadStatus.Partial,
            totalChapters: 5,
            downloadedChapters: 2,
            dateAdded: 1500,
            lastUpdated: 1800,
            tags: ['romance'],
        },
        {
            id: '3',
            title: 'Story Gamma',
            author: 'Author A',
            sourceUrl: 'http://example.com/story3',
            coverUrl: 'http://cover3',
            chapters: [],
            status: DownloadStatus.Completed,
            totalChapters: 8,
            downloadedChapters: 8,
            dateAdded: 1200,
            lastUpdated: 2500,
            tags: ['action', 'sci-fi'],
        },
    ];

    beforeEach(() => {
        jest.clearAllMocks();
        (storageService.getLibrary as jest.Mock).mockResolvedValue(mockStories);

        (sourceRegistry.getProvider as jest.Mock).mockImplementation((url: string) => {
            if (url.includes('example.com')) {
                return { name: 'ExampleSource' };
            }
            if (url.includes('other.com')) {
                return { name: 'OtherSource' };
            }
            return null;
        });
    });

    it('should load library on mount', async () => {
        const { result } = renderHook(() => useLibrary());

        expect(storageService.getLibrary).toHaveBeenCalled();
    });

    it('should return sorted stories by default (most recent activity first)', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        const stories = result.current.stories;

        expect(stories[0].id).toBe('3');
        expect(stories[1].id).toBe('1');
        expect(stories[2].id).toBe('2');
    });

    it('should filter stories by search query', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSearchQuery('Alpha');
        });

        expect(result.current.stories.length).toBe(1);
        expect(result.current.stories[0].title).toBe('Story Alpha');
    });

    it('should filter stories by author in search query', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSearchQuery('Author A');
        });

        expect(result.current.stories.length).toBe(2);
        expect(result.current.stories.every(s => s.author === 'Author A')).toBe(true);
    });

    it('should collect all unique tags', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(result.current.allTags).toContain('action');
        expect(result.current.allTags).toContain('fantasy');
        expect(result.current.allTags).toContain('romance');
        expect(result.current.allTags).toContain('sci-fi');
    });

    it('should collect all source names', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(result.current.allTags).toContain('ExampleSource');
        expect(result.current.allTags).toContain('OtherSource');
    });

    it('should filter by source tag', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.toggleTag('ExampleSource');
        });

        expect(result.current.selectedTags).toContain('ExampleSource');
        expect(result.current.stories.length).toBe(2);
        expect(result.current.stories.every(s => s.sourceUrl.includes('example.com'))).toBe(true);
    });

    it('should filter by content tag', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.toggleTag('action');
        });

        expect(result.current.selectedTags).toContain('action');
        expect(result.current.stories.length).toBe(2);
        expect(result.current.stories.every(s => s.tags?.includes('action'))).toBe(true);
    });

    it('should toggle tag on and off', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(result.current.selectedTags.length).toBe(0);

        await act(async () => {
            result.current.toggleTag('action');
        });

        expect(result.current.selectedTags).toContain('action');

        await act(async () => {
            result.current.toggleTag('action');
        });

        expect(result.current.selectedTags).not.toContain('action');
    });

    it('should replace source tag when selecting another source', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.toggleTag('ExampleSource');
        });

        expect(result.current.selectedTags).toContain('ExampleSource');

        await act(async () => {
            result.current.toggleTag('OtherSource');
        });

        expect(result.current.selectedTags).not.toContain('ExampleSource');
        expect(result.current.selectedTags).toContain('OtherSource');
    });

    it('should allow source and content tags together', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.toggleTag('ExampleSource');
            result.current.toggleTag('action');
        });

        expect(result.current.selectedTags).toContain('ExampleSource');
        expect(result.current.selectedTags).toContain('action');
        expect(result.current.stories.length).toBe(2);
    });

    it('should sort stories by title ascending', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSortOption('title');
            result.current.setSortDirection('asc');
        });

        expect(result.current.stories[0].title).toBe('Story Alpha');
        expect(result.current.stories[1].title).toBe('Story Beta');
        expect(result.current.stories[2].title).toBe('Story Gamma');
    });

    it('should sort stories by title descending', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSortOption('title');
            result.current.setSortDirection('desc');
        });

        expect(result.current.stories[0].title).toBe('Story Gamma');
        expect(result.current.stories[1].title).toBe('Story Beta');
        expect(result.current.stories[2].title).toBe('Story Alpha');
    });

    it('should sort stories by date added ascending', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSortOption('dateAdded');
            result.current.setSortDirection('asc');
        });

        expect(result.current.stories[0].dateAdded).toBe(1000);
        expect(result.current.stories[1].dateAdded).toBe(1200);
        expect(result.current.stories[2].dateAdded).toBe(1500);
    });

    it('should sort stories by last updated descending', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSortOption('lastUpdated');
            result.current.setSortDirection('desc');
        });

        expect(result.current.stories[0].lastUpdated).toBe(2500);
        expect(result.current.stories[1].lastUpdated).toBe(2000);
        expect(result.current.stories[2].lastUpdated).toBe(1800);
    });

    it('should sort stories by total chapters', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        await act(async () => {
            result.current.setSortOption('totalChapters');
            result.current.setSortDirection('desc');
        });

        expect(result.current.stories[0].totalChapters).toBe(10);
        expect(result.current.stories[1].totalChapters).toBe(8);
        expect(result.current.stories[2].totalChapters).toBe(5);
    });

    it('should handle refresh manually', async () => {
        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(result.current.refreshing).toBe(false);

        await act(async () => {
            await result.current.onRefresh();
        });

        expect(storageService.getLibrary).toHaveBeenCalledTimes(2);
    });

    it('should handle empty library', async () => {
        (storageService.getLibrary as jest.Mock).mockResolvedValue([]);

        const { result } = renderHook(() => useLibrary());

        await act(async () => {
            await new Promise(resolve => setTimeout(resolve, 0));
        });

        expect(result.current.stories).toEqual([]);
        expect(result.current.allTags).toEqual([]);
    });

    it('should show loading state during refresh', async () => {
        let resolvePromise: (value: unknown) => void;
        (storageService.getLibrary as jest.Mock).mockImplementation(
            () => new Promise(resolve => { resolvePromise = resolve; })
        );

        const { result } = renderHook(() => useLibrary());

        expect(result.current.loading).toBe(true);

        await act(async () => {
            resolvePromise!([]);
        });

        expect(result.current.loading).toBe(false);
    });
});
