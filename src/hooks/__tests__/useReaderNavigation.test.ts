
import { renderHook } from '@testing-library/react-native';
import { useReaderNavigation } from '../useReaderNavigation';
import { Story, Chapter } from '../../types';

jest.mock('expo-router', () => ({
    useRouter: jest.fn(),
}));

describe('useReaderNavigation', () => {
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
            { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true },
            { id: 'c2', title: 'Chapter 2', url: 'http://c2', downloaded: true },
            { id: 'c3', title: 'Chapter 3', url: 'http://c3', downloaded: true },
        ],
        dateAdded: 1000,
        lastUpdated: 2000,
    };

    const mockRouter = {
        setParams: jest.fn(),
    };

    beforeEach(() => {
        jest.clearAllMocks();
        const { useRouter } = require('expo-router');
        useRouter.mockReturnValue(mockRouter);
    });

    it('should calculate index from story and chapter', () => {
        const mockChapter = mockStory.chapters[1];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        expect(result.current.currentIndex).toBe(1);
    });

    it('should return -1 when story is null', () => {
        const { result } = renderHook(() => useReaderNavigation(null, null));

        expect(result.current.currentIndex).toBe(-1);
    });

    it('should return -1 when chapter is null', () => {
        const { result } = renderHook(() => useReaderNavigation(mockStory, null));

        expect(result.current.currentIndex).toBe(-1);
    });

    it('should use provided index instead of calculating', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter, 2));

        expect(result.current.currentIndex).toBe(2);
    });

    it('should detect hasNext correctly', () => {
        const mockChapter1 = mockStory.chapters[1];
        const { result: result1 } = renderHook(() => useReaderNavigation(mockStory, mockChapter1));
        expect(result1.current.hasNext).toBe(true);

        const mockChapter2 = mockStory.chapters[2];
        const { result: result2 } = renderHook(() => useReaderNavigation(mockStory, mockChapter2));
        expect(result2.current.hasNext).toBe(false);
    });

    it('should detect hasPrevious correctly', () => {
        const mockChapter1 = mockStory.chapters[0];
        const { result: result1 } = renderHook(() => useReaderNavigation(mockStory, mockChapter1));
        expect(result1.current.hasPrevious).toBe(false);

        const mockChapter2 = mockStory.chapters[1];
        const { result: result2 } = renderHook(() => useReaderNavigation(mockStory, mockChapter2));
        expect(result2.current.hasPrevious).toBe(true);
    });

    it('should navigate to chapter by index', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        result.current.navigateToChapter(1);

        expect(mockRouter.setParams).toHaveBeenCalledWith({
            chapterId: 'c2',
        });
    });

    it('should not navigate when story is null', () => {
        const { result } = renderHook(() => useReaderNavigation(null, null));

        result.current.navigateToChapter(0);

        expect(mockRouter.setParams).not.toHaveBeenCalled();
    });

    it('should not navigate to index less than 0', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        result.current.navigateToChapter(-1);

        expect(mockRouter.setParams).not.toHaveBeenCalled();
    });

    it('should not navigate to index greater than or equal to chapters length', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        result.current.navigateToChapter(3);

        expect(mockRouter.setParams).not.toHaveBeenCalled();
    });

    it('should navigate with additional params', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        result.current.navigateToChapter(1, { autoplay: 'true' });

        expect(mockRouter.setParams).toHaveBeenCalledWith({
            chapterId: 'c2',
            autoplay: 'true',
        });
    });

    it('should handle special characters in chapter id when navigating', () => {
        const storyWithSpecialIds: Story = {
            ...mockStory,
            chapters: [
                { id: 'c1 & test', title: 'Chapter 1', url: 'http://c1', downloaded: true },
                { id: 'c2?param=value', title: 'Chapter 2', url: 'http://c2', downloaded: true },
            ],
        };

        const { result } = renderHook(() => useReaderNavigation(storyWithSpecialIds, storyWithSpecialIds.chapters[0]));

        result.current.navigateToChapter(1);

        expect(mockRouter.setParams).toHaveBeenCalledWith({
            chapterId: 'c2%3Fparam%3Dvalue',
        });
    });

    it('should work with single chapter story', () => {
        const singleChapterStory: Story = {
            ...mockStory,
            chapters: [
                { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true },
            ],
        };

        const { result } = renderHook(() => useReaderNavigation(singleChapterStory, singleChapterStory.chapters[0]));

        expect(result.current.currentIndex).toBe(0);
        expect(result.current.hasNext).toBe(false);
        expect(result.current.hasPrevious).toBe(false);
    });

    it('should return false for hasNext and hasPrevious when story is null', () => {
        const { result } = renderHook(() => useReaderNavigation(null, null));

        expect(result.current.hasNext).toBe(false);
        expect(result.current.hasPrevious).toBe(false);
    });

    it('should handle chapter at index 0 edge case', () => {
        const mockChapter = mockStory.chapters[0];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        expect(result.current.currentIndex).toBe(0);
        expect(result.current.hasPrevious).toBe(false);
        expect(result.current.hasNext).toBe(true);
    });

    it('should handle chapter at last index edge case', () => {
        const mockChapter = mockStory.chapters[2];

        const { result } = renderHook(() => useReaderNavigation(mockStory, mockChapter));

        expect(result.current.currentIndex).toBe(2);
        expect(result.current.hasPrevious).toBe(true);
        expect(result.current.hasNext).toBe(false);
    });
});
