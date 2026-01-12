import { validateStory, validateChapter, validateDownloadRange, hasAllChaptersDownloaded, hasUpdatesAvailable } from '../storyValidation';
import { Story, Chapter, DownloadStatus } from '../../types';

describe('storyValidation', () => {
    const mockStory: Story = {
        id: 'story-123',
        title: 'Test Story',
        author: 'Test Author',
        sourceUrl: 'https://example.com',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'https://example.com/c1', downloaded: false },
            { id: 'c2', title: 'Chapter 2', url: 'https://example.com/c2', downloaded: true },
            { id: 'c3', title: 'Chapter 3', url: 'https://example.com/c3', downloaded: false },
        ],
        totalChapters: 3,
        downloadedChapters: 1,
        status: DownloadStatus.Partial,
    };

    const mockChapter: Chapter = {
        id: 'c2',
        title: 'Chapter 2',
        url: 'https://example.com/c2',
        downloaded: true,
    };

    const invalidChapter: Chapter = {
        id: 'c99',
        title: 'Chapter 99',
        url: 'https://example.com/c99',
        downloaded: false,
    };

    describe('validateStory', () => {
        it('should return true for valid story', () => {
            expect(validateStory(mockStory)).toBe(true);
        });

        it('should return false for null story', () => {
            expect(validateStory(null)).toBe(false);
        });

        it('should return false for story with empty ID', () => {
            const invalidStory = { ...mockStory, id: '' };
            expect(validateStory(invalidStory)).toBe(false);
        });

        it('should return false for story with undefined ID', () => {
            const invalidStory = { ...mockStory, id: undefined as any };
            expect(validateStory(invalidStory)).toBe(false);
        });

        it('should validate story type correctly', () => {
            const validStory: Story = mockStory;
            if (validateStory(validStory)) {
                expect(validStory.id).toBe('story-123');
            }
        });
    });

    describe('validateChapter', () => {
        it('should return true for chapter belonging to story', () => {
            expect(validateChapter(mockStory, mockChapter)).toBe(true);
        });

        it('should return false for chapter not in story', () => {
            expect(validateChapter(mockStory, invalidChapter)).toBe(false);
        });

        it('should find chapter by ID', () => {
            const chapterToValidate = mockStory.chapters[1];
            expect(validateChapter(mockStory, chapterToValidate)).toBe(true);
        });

        it('should return false for chapter with different ID', () => {
            const wrongChapter = { ...mockChapter, id: 'different-id' };
            expect(validateChapter(mockStory, wrongChapter)).toBe(false);
        });
    });

    describe('validateDownloadRange', () => {
        it('should return valid for correct range', () => {
            const result = validateDownloadRange(1, 5, 10);
            expect(result.valid).toBe(true);
            expect(result.error).toBeUndefined();
        });

        it('should return valid for single chapter range', () => {
            const result = validateDownloadRange(5, 5, 10);
            expect(result.valid).toBe(true);
        });

        it('should return invalid when start is less than 1', () => {
            const result = validateDownloadRange(0, 5, 10);
            expect(result.valid).toBe(false);
            expect(result.error).toBe('Range exceeds available chapters');
        });

        it('should return invalid when end exceeds total', () => {
            const result = validateDownloadRange(1, 15, 10);
            expect(result.valid).toBe(false);
            expect(result.error).toBe('Range exceeds available chapters');
        });

        it('should return invalid when start is greater than end', () => {
            const result = validateDownloadRange(10, 5, 20);
            expect(result.valid).toBe(false);
            expect(result.error).toBe('Start chapter must be before end chapter');
        });

        it('should return invalid when both start and end exceed total', () => {
            const result = validateDownloadRange(15, 20, 10);
            expect(result.valid).toBe(false);
            expect(result.error).toBe('Range exceeds available chapters');
        });

        it('should handle edge case: range equal to total chapters', () => {
            const result = validateDownloadRange(1, 10, 10);
            expect(result.valid).toBe(true);
        });
    });

    describe('hasAllChaptersDownloaded', () => {
        it('should return true when all chapters are downloaded', () => {
            const completedStory = {
                ...mockStory,
                totalChapters: 5,
                downloadedChapters: 5,
            };
            expect(hasAllChaptersDownloaded(completedStory)).toBe(true);
        });

        it('should return false when not all chapters are downloaded', () => {
            expect(hasAllChaptersDownloaded(mockStory)).toBe(false);
        });

        it('should return true when totalChapters is zero', () => {
            const emptyStory = {
                ...mockStory,
                totalChapters: 0,
                downloadedChapters: 0,
            };
            expect(hasAllChaptersDownloaded(emptyStory)).toBe(true);
        });

        it('should return false for partially downloaded story', () => {
            const partialStory = {
                ...mockStory,
                totalChapters: 10,
                downloadedChapters: 5,
            };
            expect(hasAllChaptersDownloaded(partialStory)).toBe(false);
        });

        it('should return false when downloadedChapters is zero', () => {
            const notDownloadedStory = {
                ...mockStory,
                totalChapters: 5,
                downloadedChapters: 0,
            };
            expect(hasAllChaptersDownloaded(notDownloadedStory)).toBe(false);
        });
    });

    describe('hasUpdatesAvailable', () => {
        it('should return true when new chapters are available', () => {
            expect(hasUpdatesAvailable(mockStory, 5, false)).toBe(true);
        });

        it('should return true when tags have changed', () => {
            expect(hasUpdatesAvailable(mockStory, 0, true)).toBe(true);
        });

        it('should return true when both new chapters and tag changes', () => {
            expect(hasUpdatesAvailable(mockStory, 5, true)).toBe(true);
        });

        it('should return false when no updates available', () => {
            expect(hasUpdatesAvailable(mockStory, 0, false)).toBe(false);
        });

        it('should handle negative newChaptersCount', () => {
            expect(hasUpdatesAvailable(mockStory, -1, false)).toBe(false);
        });

        it('should return true for any positive new chapter count', () => {
            expect(hasUpdatesAvailable(mockStory, 1, false)).toBe(true);
        });

        it('should return true for large new chapter count', () => {
            expect(hasUpdatesAvailable(mockStory, 100, false)).toBe(true);
        });
    });
});
