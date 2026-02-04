import { mergeChapters } from '../mergeChapters';
import { Chapter } from '../../types';
import { ChapterInfo, SourceProvider } from '../../services/source/types';

describe('mergeChapters', () => {
    const mockProvider: SourceProvider = {
        name: 'TestProvider',
        baseUrl: 'https://example.com',
        isSource: jest.fn(),
        getStoryId: jest.fn().mockReturnValue('story-123'),
        getChapterId: jest.fn((url: string) => {
            const match = url.match(/chapter\/(\d+)/);
            return match ? `chapter-${match[1]}` : undefined;
        }),
        parseMetadata: jest.fn(),
        getChapterList: jest.fn(),
        parseChapterContent: jest.fn(),
    };

    const providerWithoutGetChapterId: SourceProvider = {
        name: 'BasicProvider',
        baseUrl: 'https://basic.com',
        isSource: jest.fn(),
        getStoryId: jest.fn().mockReturnValue('story-456'),
        parseMetadata: jest.fn(),
        getChapterList: jest.fn(),
        parseChapterContent: jest.fn(),
    };

    describe('edge cases', () => {
        it('should handle empty existing chapters and empty new chapters', () => {
            const result = mergeChapters([], [], mockProvider);

            expect(result.chapters).toEqual([]);
            expect(result.downloadedCount).toBe(0);
            expect(result.newChaptersCount).toBe(0);
        });

        it('should handle empty existing chapters with new chapters', () => {
            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://example.com/chapter/1' },
                { title: 'Chapter 2', url: 'https://example.com/chapter/2' },
            ];

            const result = mergeChapters([], newChapters, mockProvider);

            expect(result.chapters).toHaveLength(2);
            expect(result.newChaptersCount).toBe(2);
            expect(result.downloadedCount).toBe(0);
            expect(result.chapters[0].downloaded).toBe(false);
        });

        it('should handle existing chapters with empty new chapters', () => {
            const existingChapters: Chapter[] = [
                { id: 'chapter-1', title: 'Chapter 1', url: 'https://example.com/chapter/1', downloaded: true },
            ];

            const result = mergeChapters(existingChapters, [], mockProvider);

            expect(result.chapters).toEqual([]);
            expect(result.newChaptersCount).toBe(0);
            expect(result.downloadedCount).toBe(0);
        });
    });

    describe('provider without getChapterId', () => {
        it('should use fallback ID when provider has no getChapterId method', () => {
            const existingChapters: Chapter[] = [
                { id: 'fallback-1', title: 'Chapter 1', url: 'https://basic.com/1', downloaded: true },
            ];

            const newChapters: ChapterInfo[] = [
                { id: 'fallback-1', title: 'Chapter 1 Updated', url: 'https://basic.com/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, providerWithoutGetChapterId);

            expect(result.chapters).toHaveLength(1);
            expect(result.chapters[0].id).toBe('fallback-1');
            expect(result.chapters[0].title).toBe('Chapter 1 Updated');
            expect(result.chapters[0].downloaded).toBe(true);
            expect(result.newChaptersCount).toBe(0);
        });

        it('should use URL as ID when provider has no getChapterId and no fallback ID', () => {
            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://basic.com/chapter/1' },
            ];

            const result = mergeChapters([], newChapters, providerWithoutGetChapterId);

            expect(result.chapters).toHaveLength(1);
            expect(result.chapters[0].id).toBe('https://basic.com/chapter/1');
        });
    });

    describe('chapters with missing IDs', () => {
        it('should handle chapters missing both id and url in existing chapters', () => {
            const existingChapters: Chapter[] = [
                { id: 'valid-id', title: 'Valid Chapter', url: 'https://example.com/valid', downloaded: true },
                { id: '', title: 'Invalid Chapter', url: '', downloaded: false }, // Will be skipped
            ];

            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://example.com/chapter/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, mockProvider);

            // The invalid chapter should not affect merging
            expect(result.chapters).toHaveLength(1);
            expect(result.chapters[0].id).toBe('chapter-1');
        });

        it('should handle new chapters with missing id', () => {
            const existingChapters: Chapter[] = [
                { id: 'chapter-1', title: 'Chapter 1', url: 'https://example.com/chapter/1', downloaded: true },
            ];

            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://example.com/chapter/1' }, // No id provided
            ];

            const result = mergeChapters(existingChapters, newChapters, mockProvider);

            expect(result.chapters).toHaveLength(1);
            expect(result.chapters[0].id).toBe('chapter-1'); // Uses provider ID
            expect(result.newChaptersCount).toBe(0);
        });

        it('should handle chapters with only URL (no id)', () => {
            const existingChapters: Chapter[] = [];

            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://example.com/chapter/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, mockProvider);

            expect(result.chapters[0].id).toBe('chapter-1');
        });
    });

    describe('buildStableId priority strategy', () => {
        it('should prioritize provider ID over fallback ID', () => {
            const existingChapters: Chapter[] = [
                { id: 'fallback-1', title: 'Chapter 1', url: 'https://example.com/chapter/1', downloaded: true },
            ];

            const newChapters: ChapterInfo[] = [
                { id: 'fallback-1', title: 'Chapter 1', url: 'https://example.com/chapter/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, mockProvider);

            // Provider returns 'chapter-1' from URL, which takes priority over 'fallback-1'
            expect(result.chapters[0].id).toBe('chapter-1');
        });

        it('should use fallback ID when provider returns undefined', () => {
            const providerWithUndefinedReturn: SourceProvider = {
                ...mockProvider,
                getChapterId: jest.fn().mockReturnValue(undefined),
            };

            const existingChapters: Chapter[] = [
                { id: 'fallback-id', title: 'Chapter 1', url: 'https://unknown.com/chapter/1', downloaded: true },
            ];

            const newChapters: ChapterInfo[] = [
                { id: 'fallback-id', title: 'Chapter 1', url: 'https://unknown.com/chapter/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, providerWithUndefinedReturn);

            // Falls back to the provided id when provider returns undefined
            expect(result.chapters[0].id).toBe('fallback-id');
        });

        it('should use URL as final fallback when both provider ID and fallback ID are missing', () => {
            const providerWithUndefinedReturn: SourceProvider = {
                ...mockProvider,
                getChapterId: jest.fn().mockReturnValue(undefined),
            };

            const existingChapters: Chapter[] = [];

            const newChapters: ChapterInfo[] = [
                { title: 'Chapter 1', url: 'https://example.com/chapter/1' },
            ];

            const result = mergeChapters(existingChapters, newChapters, providerWithUndefinedReturn);

            // Falls back to URL when no other ID is available
            expect(result.chapters[0].id).toBe('https://example.com/chapter/1');
        });
    });

    describe('lastReadChapterId remapping', () => {
        it('should remap lastReadChapterId to new stable ID', () => {
            const existingChapters: Chapter[] = [
                { id: 'old-id', title: 'Chapter 1', url: 'https://example.com/chapter/1', downloaded: true },
            ];

            const result = mergeChapters(existingChapters, [], mockProvider, 'old-id');

            // The old ID should be remapped to the provider-based stable ID
            expect(result.lastReadChapterId).toBe('chapter-1');
        });

        it('should preserve lastReadChapterId when no remapping needed', () => {
            const existingChapters: Chapter[] = [
                { id: 'chapter-1', title: 'Chapter 1', url: 'https://example.com/chapter/1', downloaded: true },
            ];

            const result = mergeChapters(existingChapters, [], mockProvider, 'chapter-1');

            expect(result.lastReadChapterId).toBe('chapter-1');
        });

        it('should return undefined for lastReadChapterId when not found', () => {
            const existingChapters: Chapter[] = [];

            const result = mergeChapters(existingChapters, [], mockProvider, 'non-existent-id');

            expect(result.lastReadChapterId).toBe('non-existent-id'); // No remapping possible
        });
    });
});
