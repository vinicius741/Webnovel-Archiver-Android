import { downloadService } from '../DownloadService';
import { downloadManager } from '../download/DownloadManager';
import { storageService } from '../StorageService';
import { readChapterFile, saveChapter } from '../storage/fileSystem';
import { removeUnwantedSentences } from '../../utils/htmlUtils';
import { Story, Chapter, DownloadStatus } from '../../types';

jest.mock('../download/DownloadManager', () => ({
    downloadManager: {
        addJob: jest.fn().mockResolvedValue(undefined),
        addJobs: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../StorageService', () => ({
    storageService: {
        updateStory: jest.fn().mockResolvedValue(undefined),
        getSentenceRemovalList: jest.fn().mockResolvedValue(['bad sentence']),
    },
}));

jest.mock('../storage/fileSystem', () => ({
    readChapterFile: jest.fn(),
    saveChapter: jest.fn().mockResolvedValue('file://chapter.html'),
}));

jest.mock('../../utils/htmlUtils', () => ({
    removeUnwantedSentences: jest.fn((html, list) => html),
}));

describe('DownloadService', () => {
    const mockStory: Story = {
        id: 'test-story-1',
        title: 'Test Story',
        author: 'Test Author',
        sourceUrl: 'http://test.com/story',
        coverUrl: 'http://test.com/cover.jpg',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://test.com/c1', downloaded: false },
            { id: 'c2', title: 'Chapter 2', url: 'http://test.com/c2', downloaded: false },
            { id: 'c3', title: 'Chapter 3', url: 'http://test.com/c3', downloaded: true, filePath: 'file://c3.html' },
            { id: 'c4', title: 'Chapter 4', url: 'http://test.com/c4', downloaded: false },
        ],
        status: DownloadStatus.Idle,
        totalChapters: 4,
        downloadedChapters: 1,
    };

    beforeEach(() => {
        jest.clearAllMocks();
    });

    describe('downloadChapter', () => {
        it('should queue a single chapter for download', async () => {
            const chapter = mockStory.chapters[0];
            const result = await downloadService.downloadChapter(mockStory, chapter, 0);

            expect(downloadManager.addJob).toHaveBeenCalledWith({
                id: 'test-story-1_0',
                storyId: 'test-story-1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: chapter,
                status: 'pending',
                addedAt: expect.any(Number),
                retryCount: 0,
            });
            expect(result).toEqual(chapter);
        });

        it('should queue chapter with correct job id format', async () => {
            const chapter = mockStory.chapters[2];
            await downloadService.downloadChapter(mockStory, chapter, 2);

            expect(downloadManager.addJob).toHaveBeenCalledWith(
                expect.objectContaining({
                    id: 'test-story-1_2',
                    chapterIndex: 2,
                })
            );
        });
    });

    describe('downloadRange', () => {
        it('should queue chapters within the specified range', async () => {
            const result = await downloadService.downloadRange(mockStory, 0, 1);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([
                expect.objectContaining({
                    id: 'test-story-1_0',
                    chapterIndex: 0,
                }),
                expect.objectContaining({
                    id: 'test-story-1_1',
                    chapterIndex: 1,
                }),
            ]);
            expect(result.status).toBe(DownloadStatus.Downloading);
        });

        it('should skip already downloaded chapters', async () => {
            const result = await downloadService.downloadRange(mockStory, 0, 3);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([
                expect.objectContaining({
                    id: 'test-story-1_0',
                }),
                expect.objectContaining({
                    id: 'test-story-1_1',
                }),
                expect.objectContaining({
                    id: 'test-story-1_3',
                }),
            ]);

            const jobs = (downloadManager.addJobs as jest.Mock).mock.calls[0][0];
            expect(jobs).toHaveLength(3);
            expect(jobs.find((j: any) => j.chapterIndex === 2)).toBeUndefined();
        });

        it('should update story status to Downloading', async () => {
            await downloadService.downloadRange(mockStory, 0, 1);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: DownloadStatus.Downloading,
                    lastUpdated: expect.any(Number),
                })
            );
        });

        it('should return story unchanged if no chapters to download', async () => {
            const allDownloadedStory: Story = {
                ...mockStory,
                chapters: mockStory.chapters.map(c => ({ ...c, downloaded: true })),
            };

            const result = await downloadService.downloadRange(allDownloadedStory, 0, 3);

            expect(downloadManager.addJobs).not.toHaveBeenCalled();
            expect(result.status).toBe(DownloadStatus.Idle);
        });

        it('should handle range boundaries correctly', async () => {
            await downloadService.downloadRange(mockStory, 3, 3);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([
                expect.objectContaining({
                    id: 'test-story-1_3',
                }),
            ]);
        });
    });

    describe('downloadAllChapters', () => {
        it('should queue all chapters by calling downloadRange with full range', async () => {
            await downloadService.downloadAllChapters(mockStory);

            expect(downloadManager.addJobs).toHaveBeenCalled();
            const jobs = (downloadManager.addJobs as jest.Mock).mock.calls[0][0];
            expect(jobs).toHaveLength(3);
        });

        it('should update story status', async () => {
            await downloadService.downloadAllChapters(mockStory);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: DownloadStatus.Downloading,
                })
            );
        });
    });

    describe('applySentenceRemovalToStory', () => {
        it('should apply sentence removal to downloaded chapters', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Chapter content with bad sentence.</p>');
            (removeUnwantedSentences as jest.Mock).mockReturnValue('<p>Chapter content.</p>');

            const onProgress = jest.fn();
            const result = await downloadService.applySentenceRemovalToStory(mockStory, onProgress);

            expect(result.processed).toBe(1);
            expect(result.errors).toBe(0);
            expect(saveChapter).toHaveBeenCalledWith('test-story-1', 2, 'Chapter 3', '<p>Chapter content.</p>');
            expect(onProgress).toHaveBeenCalledWith(3, 4, 'Chapter 3');
        });

        it('should call onProgress for each chapter', async () => {
            const allDownloadedStory: Story = {
                ...mockStory,
                chapters: mockStory.chapters.map((c, i) => ({
                    ...c,
                    downloaded: true,
                    filePath: `file://c${i}.html`,
                })),
            };
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const onProgress = jest.fn();
            await downloadService.applySentenceRemovalToStory(allDownloadedStory, onProgress);

            expect(onProgress).toHaveBeenCalledWith(1, 4, 'Chapter 1');
            expect(onProgress).toHaveBeenCalledWith(2, 4, 'Chapter 2');
            expect(onProgress).toHaveBeenCalledWith(3, 4, 'Chapter 3');
            expect(onProgress).toHaveBeenCalledWith(4, 4, 'Chapter 4');
            expect(onProgress).toHaveBeenCalledTimes(4);
        });

        it('should handle errors during chapter processing', async () => {
            const errorStory: Story = {
                ...mockStory,
                chapters: [
                    { id: 'c1', title: 'Chapter 1', url: 'http://test.com/c1', downloaded: true, filePath: 'file://c1.html' },
                    { id: 'c2', title: 'Chapter 2', url: 'http://test.com/c2', downloaded: true, filePath: 'file://c2.html' },
                    { id: 'c3', title: 'Chapter 3', url: 'http://test.com/c3', downloaded: true, filePath: 'file://c3.html' },
                ],
            };
            (readChapterFile as jest.Mock)
                .mockResolvedValueOnce('<p>Content 1</p>')
                .mockRejectedValueOnce(new Error('Read error'))
                .mockResolvedValueOnce('<p>Content 3</p>');

            const result = await downloadService.applySentenceRemovalToStory(errorStory);

            expect(result.processed).toBe(2);
            expect(result.errors).toBe(1);
        });

        it('should skip chapters without filePath', async () => {
            const storyWithoutFilePath: Story = {
                ...mockStory,
                chapters: [
                    { id: 'c1', title: 'Chapter 1', url: 'http://test.com/c1', downloaded: true },
                    { id: 'c2', title: 'Chapter 2', url: 'http://test.com/c2', downloaded: true, filePath: 'file://c2.html' },
                ],
            };
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const result = await downloadService.applySentenceRemovalToStory(storyWithoutFilePath);

            expect(result.processed).toBe(1);
            expect(saveChapter).toHaveBeenCalledTimes(1);
        });

        it('should skip chapters with empty content', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('');

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.processed).toBe(0);
            expect(saveChapter).not.toHaveBeenCalled();
        });

        it('should mark epub as stale after processing', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            await downloadService.applySentenceRemovalToStory(mockStory);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    epubStale: true,
                    lastUpdated: expect.any(Number),
                })
            );
        });

        it('should use sentence removal list from storage', async () => {
            (storageService.getSentenceRemovalList as jest.Mock).mockResolvedValue(['remove this']);
            (readChapterFile as jest.Mock).mockResolvedValue('<p>remove this</p>');

            await downloadService.applySentenceRemovalToStory(mockStory);

            expect(storageService.getSentenceRemovalList).toHaveBeenCalled();
            expect(removeUnwantedSentences).toHaveBeenCalledWith('<p>remove this</p>', ['remove this']);
        });
    });

    describe('getManager', () => {
        it('should return the downloadManager instance', () => {
            const manager = downloadService.getManager();

            expect(manager).toBe(downloadManager);
        });
    });
});
