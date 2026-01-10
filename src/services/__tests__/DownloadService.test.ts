import { downloadService } from '../DownloadService';
import { downloadManager } from '../download/DownloadManager';
import { storageService } from '../StorageService';
import { readChapterFile, saveChapter } from '../storage/fileSystem';
import { removeUnwantedSentences } from '../../utils/htmlUtils';
import { DownloadStatus, Story, Chapter } from '../../types';

jest.mock('../download/DownloadManager', () => ({
    downloadManager: {
        addJob: jest.fn().mockResolvedValue(undefined),
        addJobs: jest.fn().mockResolvedValue(undefined),
        getManager: jest.fn().mockReturnThis(),
    },
}));

jest.mock('../StorageService', () => ({
    storageService: {
        getSentenceRemovalList: jest.fn().mockResolvedValue([]),
        updateStory: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../storage/fileSystem', () => ({
    readChapterFile: jest.fn(),
    saveChapter: jest.fn().mockResolvedValue('file://chapter.html'),
}));

jest.mock('../../utils/htmlUtils', () => ({
    removeUnwantedSentences: jest.fn().mockImplementation((content) => content),
}));

describe('DownloadService', () => {
    const mockStory: Story = {
        id: 'story1',
        title: 'Test Story',
        author: 'Test Author',
        sourceUrl: 'https://example.com',
        status: DownloadStatus.Idle,
        totalChapters: 5,
        downloadedChapters: 0,
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'https://example.com/ch1' },
            { id: 'c2', title: 'Chapter 2', url: 'https://example.com/ch2', downloaded: true, filePath: 'file://ch2.html' },
            { id: 'c3', title: 'Chapter 3', url: 'https://example.com/ch3' },
            { id: 'c4', title: 'Chapter 4', url: 'https://example.com/ch4', downloaded: true, filePath: 'file://ch4.html' },
            { id: 'c5', title: 'Chapter 5', url: 'https://example.com/ch5' },
        ],
    };

    beforeEach(() => {
        jest.clearAllMocks();
        jest.resetAllMocks();
    });

    describe('Single Chapter Download', () => {
        it('should queue single chapter for download', async () => {
            const chapter = mockStory.chapters[0];
            const result = await downloadService.downloadChapter(mockStory, chapter, 0);

            expect(downloadManager.addJob).toHaveBeenCalledWith(
                expect.objectContaining({
                    id: 'story1_0',
                    storyId: 'story1',
                    chapterIndex: 0,
                    chapter: chapter,
                    status: 'pending',
                })
            );
            expect(result).toBe(chapter);
        });

        it('should use correct job ID format', async () => {
            const chapter = mockStory.chapters[2];
            await downloadService.downloadChapter(mockStory, chapter, 2);

            expect(downloadManager.addJob).toHaveBeenCalledWith(
                expect.objectContaining({
                    id: 'story1_2',
                })
            );
        });

        it('should include story metadata in job', async () => {
            const chapter = mockStory.chapters[0];
            await downloadService.downloadChapter(mockStory, chapter, 0);

            expect(downloadManager.addJob).toHaveBeenCalledWith(
                expect.objectContaining({
                    storyId: 'story1',
                    storyTitle: 'Test Story',
                })
            );
        });
    });

    describe('Range Download', () => {
        it('should download range of chapters', async () => {
            await downloadService.downloadRange(mockStory, 0, 4);

            expect(downloadManager.addJobs).toHaveBeenCalled();
            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: DownloadStatus.Downloading,
                })
            );
        });

        it('should download range from start to end', async () => {
            await downloadService.downloadRange(mockStory, 2, 4);

            expect(downloadManager.addJobs).toHaveBeenCalledWith(
                expect.arrayContaining([
                    expect.objectContaining({ id: 'story1_2' }),
                    expect.objectContaining({ id: 'story1_4' }),
                ])
            );
        });

        it('should skip already downloaded chapters', async () => {
            await downloadService.downloadRange(mockStory, 0, 4);

            const jobsArg = (downloadManager.addJobs as jest.Mock).mock.calls[0][0];
            expect(jobsArg).not.toEqual(
                expect.arrayContaining([
                    expect.objectContaining({ id: 'story1_1' }),
                    expect.objectContaining({ id: 'story1_3' }),
                ])
            );
        });

        it('should return story unchanged when no jobs to queue', async () => {
            const storyWithAllDownloaded: Story = {
                ...mockStory,
                chapters: mockStory.chapters.map((c: Chapter, i: number) => ({
                    ...c,
                    downloaded: true,
                })),
            };

            await downloadService.downloadRange(storyWithAllDownloaded, 0, 4);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([]);
        });

        it('should set status to Downloading', async () => {
            await downloadService.downloadRange(mockStory, 0, 4);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: DownloadStatus.Downloading,
                })
            );
        });

        it('should update lastUpdated timestamp', async () => {
            await downloadService.downloadRange(mockStory, 0, 4);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    lastUpdated: expect.any(Number),
                })
            );
        });

        it('should handle partial range download', async () => {
            await downloadService.downloadRange(mockStory, 1, 2);

            expect(downloadManager.addJobs).toHaveBeenCalled();
            const jobsArg = (downloadManager.addJobs as jest.Mock).mock.calls[0][0];
            expect(jobsArg.length).toBeGreaterThan(0);
        });
    });

    describe('All Chapters Download', () => {
        it('should download all chapters', async () => {
            await downloadService.downloadAllChapters(mockStory);

            expect(downloadManager.addJobs).toHaveBeenCalled();
        });

        it('should call downloadRange with correct indices', async () => {
            const downloadRangeSpy = jest.spyOn(downloadService as any, 'downloadRange');
            downloadRangeSpy.mockResolvedValue(mockStory);

            await downloadService.downloadAllChapters(mockStory);

            expect(downloadRangeSpy).toHaveBeenCalledWith(mockStory, 0, 4);
            downloadRangeSpy.mockRestore();
        });
    });

    describe('Sentence Removal Application', () => {
        it('should apply sentence removal to downloaded chapters', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content to clean</p>');
            (removeUnwantedSentences as jest.Mock).mockReturnValue('<p>Cleaned content</p>');

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.processed).toBeGreaterThan(0);
            expect(saveChapter).toHaveBeenCalled();
        });

        it('should skip non-downloaded chapters', async () => {
            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(readChapterFile).toHaveBeenCalledTimes(2);
        });

        it('should track processed chapters', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.processed).toBe(2);
        });

        it('should track errors', async () => {
            (readChapterFile as jest.Mock)
                .mockResolvedValueOnce('<p>Content</p>')
                .mockRejectedValueOnce(new Error('Read error'));

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.errors).toBe(1);
        });

        it('should call onProgress callback', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const progressCallback = jest.fn();
            await downloadService.applySentenceRemovalToStory(mockStory, progressCallback);

            expect(progressCallback).toHaveBeenCalled();
        });

        it('should pass current, total, and title to progress callback', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const progressCallback = jest.fn();
            await downloadService.applySentenceRemovalToStory(mockStory, progressCallback);

            const lastCall = progressCallback.mock.calls[progressCallback.mock.calls.length - 1];
            expect(lastCall).toEqual([expect.any(Number), expect.any(Number), expect.any(String)]);
        });

        it('should remove unwanted sentences', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content with bad sentences</p>');
            (storageService.getSentenceRemovalList as jest.Mock).mockResolvedValue(['bad sentence']);
            (removeUnwantedSentences as jest.Mock).mockReturnValue('<p>Content</p>');

            await downloadService.applySentenceRemovalToStory(mockStory);

            expect(removeUnwantedSentences).toHaveBeenCalled();
            expect(removeUnwantedSentences).toHaveBeenCalledWith(
                '<p>Content with bad sentences</p>',
                ['bad sentence']
            );
        });

        it('should update story after processing', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            await downloadService.applySentenceRemovalToStory(mockStory);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    epubPath: undefined,
                    lastUpdated: expect.any(Number),
                })
            );
        });
    });

    describe('Manager Access', () => {
        it('should return download manager', () => {
            const manager = downloadService.getManager();

            expect(manager).toBe(downloadManager);
        });
    });

    describe('Error Handling', () => {
        it('should handle empty chapter list', async () => {
            const emptyStory: Story = {
                ...mockStory,
                chapters: [],
                totalChapters: 0,
            };

            const result = await downloadService.downloadRange(emptyStory, 0, 0);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([]);
        });

        it('should handle range with no downloadable chapters', async () => {
            const storyWithNoneDownloadable: Story = {
                ...mockStory,
                chapters: mockStory.chapters.map((c: Chapter) => ({ ...c, downloaded: true })),
            };

            await downloadService.downloadRange(storyWithNoneDownloadable, 0, 4);

            expect(downloadManager.addJobs).toHaveBeenCalledWith([]);
        });

        it('should handle sentence removal with no downloaded chapters', async () => {
            const storyWithNoDownloads: Story = {
                ...mockStory,
                chapters: mockStory.chapters.map((c: Chapter) => ({ ...c, downloaded: false })),
            };

            const result = await downloadService.applySentenceRemovalToStory(storyWithNoDownloads);

            expect(result.processed).toBe(0);
            expect(result.errors).toBe(0);
        });

        it('should handle missing chapter content gracefully', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('');

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.processed).toBe(0);
        });

        it('should handle file read errors during sentence removal', async () => {
            (readChapterFile as jest.Mock).mockRejectedValue(new Error('File not found'));

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.errors).toBe(2);
            expect(result.processed).toBe(0);
        });

        it('should handle save errors during sentence removal', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');
            (saveChapter as jest.Mock).mockRejectedValue(new Error('Save failed'));

            const result = await downloadService.applySentenceRemovalToStory(mockStory);

            expect(result.errors).toBe(2);
        });
    });

    describe('Progress Tracking', () => {
        it('should update progress callback for each chapter', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const progressCallback = jest.fn();
            await downloadService.applySentenceRemovalToStory(mockStory, progressCallback);

            expect(progressCallback).toHaveBeenCalledTimes(2);
        });

        it('should report accurate progress numbers', async () => {
            (readChapterFile as jest.Mock).mockResolvedValue('<p>Content</p>');

            const progressCallback = jest.fn();
            await downloadService.applySentenceRemovalToStory(mockStory, progressCallback);

            const firstCall = progressCallback.mock.calls[0];
            const secondCall = progressCallback.mock.calls[1];

            expect(firstCall[0]).toBe(1);
            expect(secondCall[0]).toBe(2);
            expect(firstCall[1]).toBe(5);
        });
    });
});
