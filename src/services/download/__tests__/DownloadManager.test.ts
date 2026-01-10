import { DownloadManager } from '../DownloadManager';
import { DownloadQueue, downloadQueue } from '../DownloadQueue';
import { DownloadJob, JobStatus } from '../types';
import { sourceRegistry } from '../../source/SourceRegistry';
import { fetchPage } from '../../network/fetcher';
import { saveChapter } from '../../storage/fileSystem';
import { storageService } from '../../StorageService';
import { notificationService } from '../../NotificationService';
import { removeUnwantedSentences } from '../../../utils/htmlUtils';
import { DownloadStatus, Story, Chapter } from '../../../types';

jest.mock('../DownloadQueue', () => ({
    DownloadQueue: jest.fn().mockImplementation(() => ({
        init: jest.fn().mockResolvedValue(undefined),
        addJob: jest.fn(),
        getStats: jest.fn().mockReturnValue({ pending: 0, active: 0, total: 0 }),
        getNextPending: jest.fn(),
        updateJobStatus: jest.fn(),
    })),
    downloadQueue: {
        init: jest.fn().mockResolvedValue(undefined),
        addJob: jest.fn(),
        getStats: jest.fn().mockReturnValue({ pending: 0, active: 0, total: 0 }),
        getNextPending: jest.fn(),
        updateJobStatus: jest.fn(),
    },
}));

jest.mock('../../source/SourceRegistry', () => ({
    sourceRegistry: {
        getProvider: jest.fn(),
    },
}));

jest.mock('../../network/fetcher', () => ({
    fetchPage: jest.fn(),
}));

jest.mock('../../storage/fileSystem', () => ({
    saveChapter: jest.fn().mockResolvedValue('file://chapter1.html'),
}));

jest.mock('../../StorageService', () => ({
    storageService: {
        getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 3 }),
        getStory: jest.fn(),
        updateStory: jest.fn().mockResolvedValue(undefined),
        getSentenceRemovalList: jest.fn().mockResolvedValue([]),
    },
}));

jest.mock('../../NotificationService', () => ({
    notificationService: {
        startForegroundService: jest.fn().mockResolvedValue(undefined),
        updateProgress: jest.fn().mockResolvedValue(undefined),
        stopForegroundService: jest.fn().mockResolvedValue(undefined),
        showCompletionNotification: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../../../utils/htmlUtils', () => ({
    removeUnwantedSentences: jest.fn().mockImplementation((content) => content),
}));

describe('DownloadManager', () => {
    let manager: DownloadManager;

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
            { id: 'c2', title: 'Chapter 2', url: 'https://example.com/ch2' },
        ],
    };

    const mockProvider = {
        parseChapterContent: jest.fn().mockReturnValue('<p>This is a much longer chapter content that exceeds the minimum length requirement of fifty characters</p>'),
    };

    beforeEach(() => {
        jest.clearAllMocks();
        manager = new DownloadManager();
        mockProvider.parseChapterContent.mockReturnValue('<p>This is a much longer chapter content that exceeds the minimum length requirement of fifty characters</p>');
    });

    describe('Initialization', () => {
        it('should initialize with settings from storage', async () => {
            await (manager as any).init();

            expect(downloadQueue.init).toHaveBeenCalled();
            expect(storageService.getSettings).toHaveBeenCalled();
        });

        it('should auto-start if pending jobs exist', async () => {
            (downloadQueue.getStats as jest.Mock).mockReturnValue({ pending: 5, active: 0, total: 5 });

            const startSpy = jest.spyOn(manager, 'start').mockImplementation(jest.fn());

            await (manager as any).init();

            expect(startSpy).toHaveBeenCalled();
            startSpy.mockRestore();
        });
    });

    describe('Queue Management', () => {
        it('should add job to queue', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            await manager.addJob(job);

            expect(downloadQueue.addJob).toHaveBeenCalledWith(job);
        });

        it('should add multiple jobs to queue', async () => {
            const jobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId: 'story1',
                    storyTitle: 'Test Story',
                    chapterIndex: 0,
                    chapter: mockStory.chapters[0],
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job2',
                    storyId: 'story1',
                    storyTitle: 'Test Story',
                    chapterIndex: 1,
                    chapter: mockStory.chapters[1],
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(jobs);

            expect(downloadQueue.addJob).toHaveBeenCalledTimes(2);
        });

        it('should emit queue-updated event when adding jobs', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            const emitSpy = jest.spyOn(manager, 'emit');

            await manager.addJob(job);

            expect(emitSpy).toHaveBeenCalledWith('queue-updated');
            emitSpy.mockRestore();
        });
    });

    describe('Worker Pool Management', () => {
        it('should respect concurrency limit', async () => {
            await (manager as any).init();

            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');
            (downloadQueue.getNextPending as jest.Mock)
                .mockReturnValueOnce(job)
                .mockReturnValue(undefined);

            manager.start();

            await new Promise(resolve => setTimeout(resolve, 100));

            expect((manager as any).activeWorkers).toBeLessThanOrEqual(3);
        });

        it('should handle worker lifecycle', async () => {
            await (manager as any).init();

            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');
            (downloadQueue.getNextPending as jest.Mock)
                .mockReturnValueOnce(job)
                .mockReturnValue(undefined);

            manager.start();

            await new Promise(resolve => setTimeout(resolve, 200));

            expect((manager as any).activeWorkers).toBe(0);
        });
    });

    describe('Story Locking', () => {
        it('should acquire lock for story update', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            expect((manager as any).storyLocks.has('story1')).toBe(true);
        });

        it('should handle lock contention', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            const processPromises = [
                (manager as any).processJob(job),
                (manager as any).processJob({ ...job, id: 'job2' }),
            ];

            await Promise.all(processPromises);

            expect((manager as any).storyLocks.has('story1')).toBe(true);
        });

        it('should release lock after completion', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            await new Promise(resolve => setTimeout(resolve, 50));

            expect(storageService.updateStory).toHaveBeenCalled();
        });
    });

    describe('Job Lifecycle', () => {
        it('should transition job from pending to downloading', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'downloading');
        });

        it('should transition job to completed on success', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'completed');
        });

        it('should transition job to failed on error', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(null);

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith(
                'job1',
                'failed',
                expect.any(String)
            );
        });

        it('should emit job-started event', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            const emitSpy = jest.spyOn(manager, 'emit');

            await (manager as any).processJob(job);

            expect(emitSpy).toHaveBeenCalledWith('job-started', job);
            emitSpy.mockRestore();
        });

        it('should emit job-completed event', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            const emitSpy = jest.spyOn(manager, 'emit');

            await (manager as any).processJob(job);

            expect(emitSpy).toHaveBeenCalledWith('job-completed', job);
            emitSpy.mockRestore();
        });
    });

    describe('Error Handling', () => {
        it('should handle network failures', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockRejectedValue(new Error('Network error'));

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'failed', expect.any(String));
        });

        it('should handle story not found', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(null);

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'failed', 'Story not found');
        });

        it('should handle provider not found', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(null);

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'failed', expect.any(String));
        });

        it('should handle empty or short content', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');
            mockProvider.parseChapterContent.mockReturnValue('');

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'failed', 'Content empty or too short');
        });

        it('should handle missing chapter URL', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: { ...mockStory.chapters[0], url: '' },
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);

            await (manager as any).processJob(job);

            expect(downloadQueue.updateJobStatus).toHaveBeenCalledWith('job1', 'failed', 'Chapter has no URL');
        });

        it('should emit job-failed event on error', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(null);

            const emitSpy = jest.spyOn(manager, 'emit');

            await (manager as any).processJob(job);

            expect(emitSpy).toHaveBeenCalledWith('job-failed', job, expect.any(Error));
            emitSpy.mockRestore();
        });
    });

    describe('Progress Tracking', () => {
        it('should update story metadata on job completion', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: mockStory.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    id: 'story1',
                    downloadedChapters: 1,
                    lastUpdated: expect.any(Number),
                })
            );
        });

        it('should set status to partial when some chapters downloaded', async () => {
            const storyWithMoreChapters: Story = {
                ...mockStory,
                chapters: [
                    { id: 'c1', title: 'Chapter 1', url: 'https://example.com/ch1' },
                    { id: 'c2', title: 'Chapter 2', url: 'https://example.com/ch2', downloaded: true },
                ],
                downloadedChapters: 1,
                totalChapters: 2,
            };

            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: storyWithMoreChapters.chapters[0],
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            (storageService.getStory as jest.Mock).mockResolvedValue(storyWithMoreChapters);
            (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
            (fetchPage as jest.Mock).mockResolvedValue('<html>content</html>');

            await (manager as any).processJob(job);

            expect(storageService.updateStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    status: DownloadStatus.Completed,
                    downloadedChapters: 2,
                })
            );
        });
    });
});
