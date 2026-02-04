import { DownloadManager } from '../download/DownloadManager';
import { DownloadJob } from '../download/types';

jest.mock('../download/DownloadQueue', () => {
    const mockJobs = new Map<string, any>();
    return {
        downloadQueue: {
            init: jest.fn().mockResolvedValue(undefined),
            addJob: jest.fn(async (job: DownloadJob) => {
                mockJobs.set(job.id, { ...job, status: 'pending' as const });
            }),
            getNextPending: jest.fn(async () => {
                for (const [id, job] of mockJobs.entries()) {
                    if (job.status === 'pending') {
                        return job;
                    }
                }
                return null;
            }),
            updateJobStatus: jest.fn(async (id: string, status: string) => {
                const job = mockJobs.get(id);
                if (job) {
                    job.status = status as any;
                }
            }),
            getStats: jest.fn(() => {
                let pending = 0;
                let active = 0;
                for (const job of mockJobs.values()) {
                    if (job.status === 'pending') pending++;
                    if (job.status === 'active') active++;
                }
                return { pending, active, total: mockJobs.size };
            }),
            _mockJobs: mockJobs, // Expose for test manipulation
        },
    };
});

jest.mock('../source/SourceRegistry', () => ({
    sourceRegistry: {
        getProvider: jest.fn(() => ({
            parseChapterContent: jest.fn(() => '<p>Chapter content</p>'),
        })),
    },
}));

jest.mock('../network/fetcher', () => ({
    fetchPage: jest.fn().mockImplementation((url: string) => {
        // Simulate network delay for concurrent tests
        return new Promise(resolve => {
            setTimeout(() => resolve(`<html><body>Content from ${url}</body></html>`), 10);
        });
    }),
}));

jest.mock('../storage/fileSystem', () => ({
    saveChapter: jest.fn().mockImplementation(async () => {
        // Simulate file I/O delay
        await new Promise(resolve => setTimeout(resolve, 5));
        return '/path/to/chapter.html';
    }),
}));

jest.mock('../StorageService', () => ({
    storageService: {
        getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 2 }),
        getStory: jest.fn().mockImplementation(async (storyId: string) => {
            // Simulate storage delay
            await new Promise(resolve => setTimeout(resolve, 5));
            return {
                id: storyId,
                title: `Story ${storyId}`,
                chapters: [],
            };
        }),
        updateStory: jest.fn().mockImplementation(async () => {
            // Simulate update delay - this is where race conditions could occur
            await new Promise(resolve => setTimeout(resolve, 10));
        }),
        getSentenceRemovalList: jest.fn().mockResolvedValue([]),
    },
}));

jest.mock('../ForegroundServiceCoordinator', () => ({
    setDownloadState: jest.fn().mockResolvedValue(undefined),
    clearDownloadState: jest.fn().mockResolvedValue(undefined),
    showDownloadCompletionNotification: jest.fn().mockResolvedValue(undefined),
}));

jest.mock('../../utils/htmlUtils', () => ({
    removeUnwantedSentences: jest.fn((content) => content),
}));

describe('DownloadManager - Concurrent Downloads', () => {
    let manager: DownloadManager;
    let mockJobs: Map<string, any>;

    beforeEach(async () => {
        jest.clearAllMocks();
        manager = new DownloadManager();
        await manager.init();

        // Get reference to the mock jobs map
        const { downloadQueue } = require('../download/DownloadQueue');
        mockJobs = downloadQueue._mockJobs;
        mockJobs.clear();
    });

    describe('Story-Level Locking', () => {
        it('should prevent concurrent modifications to the same story', async () => {
            const storyId = 'story-1';
            const jobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId,
                    storyTitle: 'Test Story',
                    chapterIndex: 0,
                    chapter: { id: 'c1', title: 'Chapter 1', url: 'http://example.com/c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job2',
                    storyId,
                    storyTitle: 'Test Story',
                    chapterIndex: 1,
                    chapter: { id: 'c2', title: 'Chapter 2', url: 'http://example.com/c2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job3',
                    storyId,
                    storyTitle: 'Test Story',
                    chapterIndex: 2,
                    chapter: { id: 'c3', title: 'Chapter 3', url: 'http://example.com/c3', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(jobs);

            // Track updateStory calls
            const { storageService } = require('../StorageService');
            const updateCalls: any[] = [];
            storageService.updateStory.mockImplementation(async (story: any) => {
                updateCalls.push({ ...story });
                await new Promise(resolve => setTimeout(resolve, 10));
            });

            // Start the manager
            const completionPromise = new Promise<void>((resolve) => {
                manager.once('all-complete', resolve);
            });
            manager.start();

            await completionPromise;

            // Each chapter should update the story exactly once
            expect(storageService.updateStory).toHaveBeenCalledTimes(3);

            // Verify each update had correct downloadedChapters count
            const downloadedCounts = updateCalls.map(u => u.downloadedChapters).sort((a, b) => a - b);
            expect(downloadedCounts).toEqual([1, 2, 3]);
        });

        it('should handle concurrent downloads for different stories independently', async () => {
            const jobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 0,
                    chapter: { id: 's1c1', title: 'S1 Ch1', url: 'http://example.com/s1c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job2',
                    storyId: 'story-2',
                    storyTitle: 'Story 2',
                    chapterIndex: 0,
                    chapter: { id: 's2c1', title: 'S2 Ch1', url: 'http://example.com/s2c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job3',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 1,
                    chapter: { id: 's1c2', title: 'S1 Ch2', url: 'http://example.com/s1c2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(jobs);

            const { storageService } = require('../StorageService');
            const story1Updates: any[] = [];
            const story2Updates: any[] = [];
            storageService.updateStory.mockImplementation(async (story: any) => {
                if (story.id === 'story-1') {
                    story1Updates.push({ ...story });
                } else {
                    story2Updates.push({ ...story });
                }
                await new Promise(resolve => setTimeout(resolve, 10));
            });

            const completionPromise = new Promise<void>((resolve) => {
                manager.once('all-complete', resolve);
            });
            manager.start();

            await completionPromise;

            // Story 1 should have 2 updates (2 chapters)
            // Story 2 should have 1 update (1 chapter)
            expect(story1Updates.length).toBe(2);
            expect(story2Updates.length).toBe(1);
        });
    });

    describe('Concurrent Job Addition', () => {
        it('should handle rapid job additions during active processing', async () => {
            const initialJobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 0,
                    chapter: { id: 'c1', title: 'Ch1', url: 'http://example.com/c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(initialJobs);

            // Track queue-updated events
            const queueEvents: string[] = [];
            manager.on('queue-updated', () => {
                queueEvents.push('updated');
            });

            manager.start();

            // Wait a bit then add more jobs
            await new Promise(resolve => setTimeout(resolve, 5));

            const additionalJobs: DownloadJob[] = [
                {
                    id: 'job2',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 1,
                    chapter: { id: 'c2', title: 'Ch2', url: 'http://example.com/c2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job3',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 2,
                    chapter: { id: 'c3', title: 'Ch3', url: 'http://example.com/c3', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(additionalJobs);

            const completionPromise = new Promise<void>((resolve) => {
                manager.once('all-complete', resolve);
            });

            await completionPromise;

            // Should have processed all jobs
            expect(queueEvents.length).toBeGreaterThan(0);
            const { storageService } = require('../StorageService');
            expect(storageService.updateStory).toHaveBeenCalledTimes(3);
        });

        it('should handle simultaneous job additions', async () => {
            const jobSets: DownloadJob[][] = [
                [{
                    id: 'job1',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 0,
                    chapter: { id: 'c1', title: 'Ch1', url: 'http://example.com/c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                }],
                [{
                    id: 'job2',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 1,
                    chapter: { id: 'c2', title: 'Ch2', url: 'http://example.com/c2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                }],
                [{
                    id: 'job3',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 2,
                    chapter: { id: 'c3', title: 'Ch3', url: 'http://example.com/c3', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                }],
            ];

            // Add all job sets simultaneously
            await Promise.all(jobSet => manager.addJobs(jobSet));

            const { downloadQueue } = require('../download/DownloadQueue');
            expect(downloadQueue.addJob).toHaveBeenCalledTimes(3);
        });
    });

    describe('Cancellation During Processing', () => {
        it('should cancel active jobs when cancelAll is called', async () => {
            const jobs: DownloadJob[] = [
                {
                    id: 'job1',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 0,
                    chapter: { id: 'c1', title: 'Ch1', url: 'http://example.com/c1', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job2',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 1,
                    chapter: { id: 'c2', title: 'Ch2', url: 'http://example.com/c2', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
                {
                    id: 'job3',
                    storyId: 'story-1',
                    storyTitle: 'Story 1',
                    chapterIndex: 2,
                    chapter: { id: 'c3', title: 'Ch3', url: 'http://example.com/c3', downloaded: false },
                    status: 'pending',
                    addedAt: Date.now(),
                    retryCount: 0,
                },
            ];

            await manager.addJobs(jobs);

            const startedEvents: string[] = [];
            const completedEvents: string[] = [];

            manager.on('job-started', (job) => startedEvents.push(job.id));
            manager.on('job-completed', (job) => completedEvents.push(job.id));

            manager.start();

            // Cancel after a short delay
            await new Promise(resolve => setTimeout(resolve, 15));
            await manager.cancelAll();

            // Some jobs may have started, but not all should complete
            expect(startedEvents.length).toBeGreaterThan(0);
            expect(completedEvents.length).toBeLessThanOrEqual(startedEvents.length);
        });

        it('should handle multiple rapid cancel calls safely', async () => {
            const jobs: DownloadJob[] = [{
                id: 'job1',
                storyId: 'story-1',
                storyTitle: 'Story 1',
                chapterIndex: 0,
                chapter: { id: 'c1', title: 'Ch1', url: 'http://example.com/c1', downloaded: false },
                status: 'pending',
                addedAt: Date.now(),
                retryCount: 0,
            }];

            await manager.addJobs(jobs);
            manager.start();

            // Call cancel multiple times rapidly
            await Promise.all([
                manager.cancelAll(),
                manager.cancelAll(),
                manager.cancelAll(),
            ]);

            // Should not throw and should complete cleanly
            expect(manager['isProcessing']).toBe(false);
        });
    });

    describe('Event Emission Under Load', () => {
        it('should emit events in correct order even under concurrent load', async () => {
            const jobs: DownloadJob[] = Array.from({ length: 5 }, (_, i) => ({
                id: `job${i}`,
                storyId: 'story-1',
                storyTitle: 'Story 1',
                chapterIndex: i,
                chapter: {
                    id: `c${i}`,
                    title: `Chapter ${i}`,
                    url: `http://example.com/c${i}`,
                    downloaded: false
                },
                status: 'pending' as const,
                addedAt: Date.now(),
                retryCount: 0,
            }));

            await manager.addJobs(jobs);

            const eventLog: string[] = [];
            manager.on('job-started', (job) => eventLog.push(`started-${job.chapterIndex}`));
            manager.on('job-completed', (job) => eventLog.push(`completed-${job.chapterIndex}`));
            manager.on('job-failed', (job) => eventLog.push(`failed-${job.chapterIndex}`));

            const completionPromise = new Promise<void>((resolve) => {
                manager.once('all-complete', resolve);
            });
            manager.start();

            await completionPromise;

            // Verify event ordering: each job should be started before it's completed
            for (let i = 0; i < 5; i++) {
                const startedIndex = eventLog.indexOf(`started-${i}`);
                const completedIndex = eventLog.indexOf(`completed-${i}`);
                expect(startedIndex).toBeGreaterThanOrEqual(0);
                expect(completedIndex).toBeGreaterThanOrEqual(0);
                expect(startedIndex).toBeLessThan(completedIndex);
            }
        });
    });

    describe('Concurrency Limits', () => {
        it('should respect concurrency setting', async () => {
            // Set concurrency to 1
            const { storageService } = require('../StorageService');
            storageService.getSettings.mockResolvedValue({ downloadConcurrency: 1 });

            const manager2 = new DownloadManager();
            await manager2.init();

            const jobs: DownloadJob[] = Array.from({ length: 3 }, (_, i) => ({
                id: `job${i}`,
                storyId: 'story-1',
                storyTitle: 'Story 1',
                chapterIndex: i,
                chapter: {
                    id: `c${i}`,
                    title: `Chapter ${i}`,
                    url: `http://example.com/c${i}`,
                    downloaded: false
                },
                status: 'pending' as const,
                addedAt: Date.now(),
                retryCount: 0,
            }));

            let maxConcurrent = 0;
            let currentActive = 0;
            manager2.on('job-started', () => {
                currentActive++;
                maxConcurrent = Math.max(maxConcurrent, currentActive);
            });
            manager2.on('job-completed', () => {
                currentActive--;
            });

            await manager2.addJobs(jobs);

            const completionPromise = new Promise<void>((resolve) => {
                manager2.once('all-complete', resolve);
            });
            manager2.start();

            await completionPromise;

            // With concurrency of 1, we should never have more than 1 active job
            expect(maxConcurrent).toBeLessThanOrEqual(1);
        });
    });
});
