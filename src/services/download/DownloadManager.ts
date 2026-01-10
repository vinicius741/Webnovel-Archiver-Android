import EventEmitter from 'events';
import { DownloadQueue, downloadQueue } from './DownloadQueue';
import { DownloadJob, JobStatus } from './types';
import { sourceRegistry } from '../source/SourceRegistry';
import { fetchPage } from '../network/fetcher';
import { saveChapter } from '../storage/fileSystem';
import { storageService } from '../StorageService';
import { notificationService } from '../NotificationService';
import { DownloadStatus, Story, Chapter } from '../../types';
import { removeUnwantedSentences } from '../../utils/htmlUtils';

export class DownloadManager extends EventEmitter {
    private isRunning = false;
    private activeWorkers = 0;
    private concurrency = 3;
    private storyLocks = new Map<string, Promise<void>>();
    private lastNotificationUpdate = 0;

    constructor() {
        super();
        this.init();
    }

    async init() {
        await downloadQueue.init();
        const settings = await storageService.getSettings();
        this.concurrency = settings.downloadConcurrency > 0 ? settings.downloadConcurrency : 3;

        // Check for any jobs that were "downloading" and reset them is handled by queue.init
        // Auto-resume if there are pending jobs?
        const stats = downloadQueue.getStats();
        if (stats.pending > 0) {
            this.start();
        }
    }

    async addJob(job: DownloadJob) {
        downloadQueue.addJob(job);
        this.emit('queue-updated');
        this.start();
    }

    async addJobs(jobs: DownloadJob[]) {
        jobs.forEach(j => downloadQueue.addJob(j));
        this.emit('queue-updated');
        this.start();
    }

    async start() {
        if (this.isRunning) return;
        this.isRunning = true;
        this.processLoop();
        this.startNotification();
    }

    private async startNotification() {
        // Initial notification
        const stats = downloadQueue.getStats();
        if (stats.pending > 0 || stats.active > 0) {
            await notificationService.startForegroundService(
                'Downloading chapters',
                `Remaining: ${stats.pending}`
            );
        }
    }

    private async updateNotification() {
        const now = Date.now();
        if (now - this.lastNotificationUpdate < 1000) return; // Throttle 1s
        this.lastNotificationUpdate = now;

        const stats = downloadQueue.getStats();
        if (stats.pending === 0 && stats.active === 0) {
            await notificationService.stopForegroundService();
            return;
        }

        await notificationService.updateProgress(
            stats.total - stats.pending, // completed (roughly, doesn't account for failures well but ok)
            stats.total,
            `Active: ${stats.active}, Pending: ${stats.pending}`
        );
    }

    private async processLoop() {
        // Main loop
        while (true) {
            // Check concurrency
            if (this.activeWorkers >= this.concurrency) {
                await new Promise(resolve => setTimeout(resolve, 200));
                continue;
            }

            const pending = downloadQueue.getNextPending();

            if (!pending) {
                // If no pending and no active workers, we are done.
                if (this.activeWorkers === 0) {
                    break;
                }
                // Wait for workers to finish
                await new Promise(resolve => setTimeout(resolve, 200));
                continue;
            }

            // Start a worker
            this.activeWorkers++;
            // We do NOT await here. We fire and forget (the promise is handled).
            this.processJob(pending).then(() => {
                this.activeWorkers--;
                this.emit('queue-updated');
                this.updateNotification();
            });

            // Brief pause to allow event loop to breathe
            await new Promise(resolve => setTimeout(resolve, 10));
        }

        this.isRunning = false;
        await notificationService.stopForegroundService();
        await notificationService.showCompletionNotification(
            'Downloads Completed',
            'All queued chapters have been processed.'
        );
        this.emit('all-complete');
    }

    private async processJob(job: DownloadJob) {
        if (job.status !== 'pending') return;

        downloadQueue.updateJobStatus(job.id, 'downloading');
        this.emit('job-started', job);

        try {
            const filePath = await this.executeDownload(job);

            // Critical Section: Update Story
            await this.acquireStoryLock(job.storyId, async () => {
                // Re-fetch story inside lock to ensure we have latest version
                const story = await storageService.getStory(job.storyId);
                if (story) {
                    const chapters = [...story.chapters];
                    if (chapters[job.chapterIndex]) {
                        chapters[job.chapterIndex] = {
                            ...chapters[job.chapterIndex],
                            filePath,
                            downloaded: true,
                        };

                        const downloadedCount = chapters.filter(c => c.downloaded).length;
                        const status = downloadedCount === chapters.length ? DownloadStatus.Completed : DownloadStatus.Partial;

                        await storageService.updateStory({
                            ...story,
                            chapters,
                            downloadedChapters: downloadedCount,
                            status,
                            lastUpdated: Date.now()
                        });
                    }
                }
            });

            downloadQueue.updateJobStatus(job.id, 'completed');
            this.emit('job-completed', job);

        } catch (error: any) {
            console.error(`[DownloadManager] Job ${job.id} failed`, error);
            downloadQueue.updateJobStatus(job.id, 'failed', error.message);
            this.emit('job-failed', job, error);
        }
    }

    private async acquireStoryLock(storyId: string, action: () => Promise<void>) {
        // Simple mutex queue
        const previousLock = this.storyLocks.get(storyId) || Promise.resolve();

        // Ensure we run regardless of previous failure
        const myLock = previousLock
            .catch(() => { })
            .then(async () => {
                try {
                    await action();
                } catch (e) {
                    console.error(`[DownloadManager] Story lock error for ${storyId}`, e);
                }
            });

        this.storyLocks.set(storyId, myLock);

        return myLock;
    }

    private async executeDownload(job: DownloadJob): Promise<string> {
        const story = await storageService.getStory(job.storyId);
        if (!story) throw new Error('Story not found');

        const provider = sourceRegistry.getProvider(story.sourceUrl);
        if (!provider) throw new Error(`No provider for ${story.sourceUrl}`);

        if (!job.chapter.url) throw new Error('Chapter has no URL');

        const html = await fetchPage(job.chapter.url);
        const content = provider.parseChapterContent(html);

        if (!content || content.length < 50) {
            throw new Error('Content empty or too short');
        }

        const sentenceRemovalList = await storageService.getSentenceRemovalList();
        const cleanedContent = removeUnwantedSentences(content, sentenceRemovalList);

        return await saveChapter(job.storyId, job.chapterIndex, job.chapter.title, cleanedContent);
    }
}

export const downloadManager = new DownloadManager();
