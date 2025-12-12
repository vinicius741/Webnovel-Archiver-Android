import { Story, Chapter, DownloadStatus } from '../types';
import { fetchPage } from './network/fetcher';
import { saveChapter } from './storage/fileSystem';
import { storageService } from './StorageService';
import { notificationService } from './NotificationService';
import { sourceRegistry } from './source/SourceRegistry';

class DownloadService {
    private activeDownloads = new Set<string>();

    /**
     * Downloads a single chapter.
     * Updates the chapter object with file path and status, but does NOT save the story to storage.
     * Returns the updated chapter.
     */
    async downloadChapter(story: Story, chapter: Chapter, index: number): Promise<Chapter> {
        if (!chapter.url) {
            console.warn(`[Download] Chapter ${index} has no URL`);
            return chapter;
        }

        const provider = sourceRegistry.getProvider(story.sourceUrl);
        if (!provider) {
             console.error(`[Download] No provider found for story source: ${story.sourceUrl}`);
             return chapter;
        }

        try {
            console.log(`[Download] fetching ${chapter.url}`);
            const html = await fetchPage(chapter.url);
            const content = provider.parseChapterContent(html);

            if (!content || content.length < 50) {
                console.warn(`[Download] Content seems empty for ${chapter.title}`);
            }

            const filePath = await saveChapter(story.id, index, chapter.title, content);

            return {
                ...chapter,
                filePath,
                content: undefined, // ensure we don't store large content in JSON
                downloaded: true,
            };
        } catch (error) {
            console.error(`[Download] Failed chapter ${index}:`, error);
            return chapter;
        }
    }

    /**
     * Downloads all chapters for a story.
     * Updates storage periodically.
     */
    async downloadAllChapters(
        story: Story,
        onProgress?: (total: number, current: number, currentChapter: string) => void
    ): Promise<Story> {
        if (this.activeDownloads.has(story.id)) {
            console.warn(`[DownloadService] Download already in progress for story: ${story.title} (${story.id})`);
            return story;
        }

        this.activeDownloads.add(story.id);

        let chapters = [...story.chapters];
        let downloadedCount = 0;

        // Reset status to downloading if not already
        if (story.status !== DownloadStatus.Completed) {
            await storageService.updateStoryStatus(story.id, DownloadStatus.Downloading);
        }

        try {
            // Calculate initial downloadedCount before starting foreground service
            const initialDownloadedCount = chapters.filter(ch => ch.downloaded || ch.filePath).length;

            await notificationService.startForegroundService(
                `Downloading ${story.title}`,
                `Starting download... (${initialDownloadedCount}/${chapters.length})`
            );

            // Get settings for concurrency and delay
            const settings = await storageService.getSettings();
            const CONCURRENCY = settings.downloadConcurrency || 1;
            const DELAY = settings.downloadDelay || 500;

            console.log(`[DownloadService] Starting download with concurrency: ${CONCURRENCY}, delay: ${DELAY}ms`);

            // Filter chapters needed to download
            // We keep the original index to update the correct chapter in the main array
            const chaptersToDownload = chapters
                .map((ch, index) => ({ chapter: ch, index }))
                .filter(({ chapter }) => !chapter.downloaded || !chapter.filePath);

            // Already downloaded ones count towards progress
            downloadedCount = chapters.length - chaptersToDownload.length;

            // Track progress monotonically for UI/Notification
            let progressCount = downloadedCount;
            let lastReportedProgress = 0;

            const updateProgressSafe = async (current: number, total: number, message: string) => {
                // Ensure we never move the progress bar backwards
                if (current > lastReportedProgress) {
                    lastReportedProgress = current;
                    onProgress?.(total, current, message);
                    await notificationService.updateProgress(current, total, message);
                }
            };

            if (chaptersToDownload.length === 0) {
                // All done already
            } else {
                // Worker Pool Implementation
                let currentIndex = 0;
                const activeWorkers: Promise<void>[] = [];

                const worker = async () => {
                    while (currentIndex < chaptersToDownload.length) {
                        const jobIndex = currentIndex++;
                        const { chapter } = chaptersToDownload[jobIndex];
                        const { index: originalIndex } = chaptersToDownload[jobIndex];

                        // Increment progress counter (started work)
                        progressCount++;
                        const currentProgress = progressCount;

                        await updateProgressSafe(
                            currentProgress,
                            chapters.length,
                            `Downloading ${currentProgress}/${chapters.length}: ${chapter.title}`
                        );

                        const updatedChapter = await this.downloadChapter(story, chapter, originalIndex);
                        chapters[originalIndex] = updatedChapter;

                        if (updatedChapter.downloaded) {
                            downloadedCount++;
                        }

                        // Save progress periodically (handled safely despite concurrency?)
                        // With concurrency, maybe safer to save less often or use a mutex?
                        // For now, let's save every 5 *downloads* completed, triggered by one worker
                        if (downloadedCount % 5 === 0) {
                            const updatedStory: Story = {
                                ...story,
                                chapters,
                                downloadedChapters: downloadedCount,
                                lastUpdated: Date.now(),
                            };
                            await storageService.updateStory(updatedStory);
                        }

                        // Delay before picking next task
                        if (DELAY > 0) {
                            await new Promise(resolve => setTimeout(resolve, DELAY));
                        }
                    }
                };

                // Start initial workers
                for (let i = 0; i < CONCURRENCY; i++) {
                    activeWorkers.push(worker());
                }

                await Promise.all(activeWorkers);
            }

            const finalStatus = downloadedCount === chapters.length ? DownloadStatus.Completed : DownloadStatus.Partial;

            const finalStory: Story = {
                ...story,
                chapters,
                downloadedChapters: downloadedCount,
                status: finalStatus,
                lastUpdated: Date.now(),
            };

            await storageService.updateStory(finalStory);

            if (finalStatus === DownloadStatus.Completed) {
                await notificationService.showCompletionNotification(
                    'Download Complete',
                    `${story.title} has been downloaded successfully.`
                );
            } else {
                await notificationService.showCompletionNotification(
                    'Download Paused',
                    `${story.title} download stopped or partial.`
                );
            }

            return finalStory;
        } catch (error) {
            console.error('[DownloadService] Error downloading chapters:', error);
            await notificationService.showCompletionNotification(
                'Download Failed',
                'An error occurred while downloading.'
            );
            return story;
        } finally {
            this.activeDownloads.delete(story.id);
            await notificationService.stopForegroundService();
        }
    }
}

export const downloadService = new DownloadService();
