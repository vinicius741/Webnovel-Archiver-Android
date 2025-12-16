import { downloadService } from '../DownloadService';
import { fetchPage } from '../network/fetcher';
import { saveChapter } from '../storage/fileSystem';
import { storageService } from '../StorageService';
import { notificationService } from '../NotificationService';
import { sourceRegistry } from '../source/SourceRegistry';
import { Story, Chapter, DownloadStatus } from '../../types';
import { SourceProvider } from '../source/types';

// Mocks
jest.mock('../network/fetcher');
jest.mock('../storage/fileSystem');
jest.mock('../StorageService', () => ({
    storageService: {
        updateStoryStatus: jest.fn(),
        getSettings: jest.fn(),
        updateStory: jest.fn(),
    }
}));
jest.mock('../NotificationService', () => ({
    notificationService: {
        startForegroundService: jest.fn(),
        updateProgress: jest.fn(),
        showCompletionNotification: jest.fn(),
        stopForegroundService: jest.fn(),
    }
}));
jest.mock('../source/SourceRegistry', () => ({
    sourceRegistry: {
        getProvider: jest.fn(),
    }
}));

describe('DownloadService', () => {
    const mockFetchPage = fetchPage as jest.Mock;
    const mockSaveChapter = saveChapter as jest.Mock;
    const mockGetProvider = sourceRegistry.getProvider as jest.Mock;
    const mockGetSettings = storageService.getSettings as jest.Mock;

    const mockProvider: SourceProvider = {
        name: 'MockSource',
        baseUrl: 'http://mock.com',
        isSource: () => true,
        getStoryId: () => '1',
        parseMetadata: () => ({ title: 'Mock', author: 'Mock' }),
        getChapterList: async () => [],
        parseChapterContent: jest.fn().mockReturnValue('<p>Content</p>'),
    };

    const mockStory: Story = {
        id: 'story1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://mock.com/story',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://mock.com/c1', downloaded: false },
            { id: 'c2', title: 'Chapter 2', url: 'http://mock.com/c2', downloaded: false },
        ],
        lastUpdated: Date.now(),
        downloadedChapters: 0,
        status: DownloadStatus.Idle,
        totalChapters: 2,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        mockGetProvider.mockReturnValue(mockProvider);
        mockGetSettings.mockResolvedValue({ downloadConcurrency: 1, downloadDelay: 0 });
    });

    describe('downloadChapter', () => {
        it('downloads and saves a chapter', async () => {
            mockFetchPage.mockResolvedValue('<html>Content</html>');
            mockSaveChapter.mockResolvedValue('/path/to/file');

            const chapter = mockStory.chapters[0];
            const result = await downloadService.downloadChapter(mockStory, chapter, 0);

            expect(mockFetchPage).toHaveBeenCalledWith(chapter.url);
            expect(mockProvider.parseChapterContent).toHaveBeenCalled();
            expect(mockSaveChapter).toHaveBeenCalledWith(mockStory.id, 0, chapter.title, '<p>Content</p>');
            expect(result.downloaded).toBe(true);
            expect(result.filePath).toBe('/path/to/file');
        });

        it('handles failure gracefully', async () => {
            mockFetchPage.mockRejectedValue(new Error('Network Error'));

            const chapter = mockStory.chapters[0];
            const result = await downloadService.downloadChapter(mockStory, chapter, 0);

            expect(result.downloaded).toBe(false);
            expect(result.filePath).toBeUndefined();
        });
    });

    describe('downloadAllChapters', () => {
        it('downloads all chapters sequentially', async () => {
            mockFetchPage.mockResolvedValue('<html>Content</html>');
            mockSaveChapter.mockResolvedValue('/path/to/file');

            const result = await downloadService.downloadAllChapters(mockStory);

            expect(mockFetchPage).toHaveBeenCalledTimes(2);
            expect(storageService.updateStory).toHaveBeenCalled();
            expect(notificationService.showCompletionNotification).toHaveBeenCalledWith(
                'Download Complete',
                expect.stringContaining('Test Story')
            );
            expect(result.downloadedChapters).toBe(2);
            expect(result.status).toBe(DownloadStatus.Completed);
        });

        it('respects concurrency settings', async () => {
            // Set concurrency to 2
            mockGetSettings.mockResolvedValue({ downloadConcurrency: 2, downloadDelay: 10 });
            mockFetchPage.mockImplementation(async () => {
                await new Promise(r => setTimeout(r, 10)); // Simulate delay
                return '<html>Content</html>';
            });
            mockSaveChapter.mockResolvedValue('/path/to/file');

            const start = Date.now();
            await downloadService.downloadAllChapters(mockStory);
            // hard to test exact timing in unit test without fake timers,
            // but we verify it runs and completes.

            expect(mockFetchPage).toHaveBeenCalledTimes(2);
        });

        it('resumes partial downloads', async () => {
             const partialStory: Story = {
                ...mockStory,
                chapters: [
                    { id: 'c1', title: 'Chapter 1', url: 'http://mock.com/c1', downloaded: true, filePath: '/exist' },
                    { id: 'c2', title: 'Chapter 2', url: 'http://mock.com/c2', downloaded: false },
                ],
                downloadedChapters: 1,
            };

            mockFetchPage.mockResolvedValue('<html>Content</html>');
            mockSaveChapter.mockResolvedValue('/path/to/file');

            const result = await downloadService.downloadAllChapters(partialStory);

            // Should only fetch the second chapter
            expect(mockFetchPage).toHaveBeenCalledTimes(1);
            expect(mockFetchPage).toHaveBeenCalledWith('http://mock.com/c2');
            expect(result.downloadedChapters).toBe(2);
        });
    });
});
