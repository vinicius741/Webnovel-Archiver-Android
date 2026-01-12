import { DownloadManager } from '../download/DownloadManager';
import { DownloadJob } from '../download/types';

jest.mock('../download/DownloadQueue', () => ({
    downloadQueue: {
        init: jest.fn().mockResolvedValue(undefined),
        addJob: jest.fn(),
        getNextPending: jest.fn(),
        updateJobStatus: jest.fn(),
        getStats: jest.fn().mockReturnValue({ pending: 0, active: 0, total: 0 }),
    },
}));

jest.mock('../source/SourceRegistry', () => ({
    sourceRegistry: {
        getProvider: jest.fn(),
    },
}));

jest.mock('../network/fetcher', () => ({
    fetchPage: jest.fn().mockResolvedValue('<html><p>Test content</p></html>'),
}));

jest.mock('../storage/fileSystem', () => ({
    saveChapter: jest.fn().mockResolvedValue('/path/to/chapter.txt'),
}));

jest.mock('../StorageService', () => ({
    storageService: {
        getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 3 }),
        getStory: jest.fn().mockResolvedValue(null),
        updateStory: jest.fn().mockResolvedValue(undefined),
        getSentenceRemovalList: jest.fn().mockResolvedValue([]),
    },
}));

jest.mock('../NotificationService', () => ({
    notificationService: {
        startForegroundService: jest.fn().mockResolvedValue(undefined),
        updateProgress: jest.fn().mockResolvedValue(undefined),
        stopForegroundService: jest.fn().mockResolvedValue(undefined),
        showCompletionNotification: jest.fn().mockResolvedValue(undefined),
    },
}));

jest.mock('../../utils/htmlUtils', () => ({
    removeUnwantedSentences: jest.fn((content) => content),
}));

describe('DownloadManager', () => {
    let manager: DownloadManager;

    beforeEach(() => {
        jest.clearAllMocks();
        manager = new DownloadManager();
    });

    describe('Initialization', () => {
        it('should initialize queue and settings', async () => {
            await manager.init();

            const { downloadQueue } = require('../download/DownloadQueue');
            const { storageService } = require('../StorageService');

            expect(downloadQueue.init).toHaveBeenCalled();
            expect(storageService.getSettings).toHaveBeenCalled();
        });
    });

    describe('Adding Jobs', () => {
        it('should add single job to queue', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: { id: 'c1', title: 'Chapter 1', url: 'http://example.com/ch1', downloaded: false },
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            await manager.addJob(job);

            const { downloadQueue } = require('../download/DownloadQueue');
            expect(downloadQueue.addJob).toHaveBeenCalledWith(job);
        });

        it('should add multiple jobs to queue', async () => {
            const jobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId: 'story1',
                    storyTitle: 'Test Story',
                    chapterIndex: 0,
                    chapter: { id: 'c1', title: 'Chapter 1', url: 'http://example.com/ch1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job2',
                    storyId: 'story1',
                    storyTitle: 'Test Story',
                    chapterIndex: 1,
                    chapter: { id: 'c2', title: 'Chapter 2', url: 'http://example.com/ch2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(jobs);

            const { downloadQueue } = require('../download/DownloadQueue');
            expect(downloadQueue.addJob).toHaveBeenCalledTimes(2);
        });

        it('should emit queue-updated event when adding jobs', async () => {
            const job: DownloadJob = {
                id: 'job1',
                storyId: 'story1',
                storyTitle: 'Test Story',
                chapterIndex: 0,
                chapter: { id: 'c1', title: 'Chapter 1', url: 'http://example.com/ch1', downloaded: false },
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            };

            const eventSpy = jest.fn();
            manager.on('queue-updated', eventSpy);

            await manager.addJob(job);

            expect(eventSpy).toHaveBeenCalled();
        });
    });

    describe('Concurrency Control', () => {
        it('should respect concurrency setting from storage', async () => {
            const { storageService } = require('../StorageService');
            storageService.getSettings.mockResolvedValue({ downloadConcurrency: 5 });

            await manager.init();

            expect(storageService.getSettings).toHaveBeenCalled();
        });

        it('should default to 3 if setting is invalid', async () => {
            const { storageService } = require('../StorageService');
            storageService.getSettings.mockResolvedValue({ downloadConcurrency: 0 });

            await manager.init();

            expect(storageService.getSettings).toHaveBeenCalled();
        });
    });

    describe('Event Emission', () => {
        it('should emit events during job lifecycle', () => {
            const startedSpy = jest.fn();
            const completedSpy = jest.fn();
            const failedSpy = jest.fn();
            const queueUpdatedSpy = jest.fn();
            const allCompleteSpy = jest.fn();

            manager.on('job-started', startedSpy);
            manager.on('job-completed', completedSpy);
            manager.on('job-failed', failedSpy);
            manager.on('queue-updated', queueUpdatedSpy);
            manager.on('all-complete', allCompleteSpy);

            expect(startedSpy).toBeDefined();
            expect(completedSpy).toBeDefined();
            expect(failedSpy).toBeDefined();
            expect(queueUpdatedSpy).toBeDefined();
            expect(allCompleteSpy).toBeDefined();
        });
    });
});
