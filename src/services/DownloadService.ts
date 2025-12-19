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
     * Downloads a specific range of chapters.
     */
    async downloadRange(
        story: Story,
        startIndex: number,
        endIndex: number,
        onProgress?: (total: number, current: number, currentChapter: string) => void
    ): Promise<Story> {
        // Create a list of chapters within the range (inclusive)
        // startIndex and endIndex are 0-based indices from the full chapters array
        const chaptersToDownload = story.chapters
            .map((ch, index) => ({ chapter: ch, index }))
            .filter(({ index }) => index >= startIndex && index <= endIndex);

        return this.processDownloadQueue(story, chaptersToDownload, onProgress);
    }

    /**
     * Downloads all chapters for a story.
     */
    async downloadAllChapters(
        story: Story,
        onProgress?: (total: number, current: number, currentChapter: string) => void
    ): Promise<Story> {
        const chaptersToDownload = story.chapters
            .map((ch, index) => ({ chapter: ch, index }));

        return this.processDownloadQueue(story, chaptersToDownload, onProgress);
    }

    /**
     * Internal method to process a queue of chapters to download.
     */
    private async processDownloadQueue(
        story: Story,
        allChaptersWithIndex: { chapter: Chapter, index: number }[],
        onProgress?: (total: number, current: number, currentChapter: string) => void
    ): Promise<Story> {
        if (this.activeDownloads.has(story.id)) {
            console.warn(`[DownloadService] Download already in progress for story: ${story.title} (${story.id})`);
            return story;
        }

        this.activeDownloads.add(story.id);

        let chapters = [...story.chapters];
        let downloadedCount = story.downloadedChapters || 0;

        // Reset status to downloading if not already
        if (story.status !== DownloadStatus.Completed) {
            await storageService.updateStoryStatus(story.id, DownloadStatus.Downloading);
        }

        try {
            // Filter only those that need downloading from the provided list
            const queue = allChaptersWithIndex
                .filter(({ chapter }) => !chapter.downloaded || !chapter.filePath);

            // The total we are trying to reach for this session relates to the *queue* size + already downloaded in this set?
            // Actually, usually progress is shown relative to the *total chapters in story* or *total requested*?
            // existing logic used `chapters.length` (total in story). Let's stick to that for "All", 
            // but for "Range", it might be confusing if we say "Downloading 5/1000" when we only acted on 10.
            // However, the internal logic below updates the *whole story* object.

            // Let's stick to showing progress relative to the *selection* if it's a range, or total if it's all?
            // "Starting download... (X/Y)"

            // For simplicity and consistency with `downloadAllChapters` behavior in UI (which expects total progress),
            // let's define "total" as the number of items we are *attempting* to process in this call plus what's already done in this subset?
            // Or just use the queue.length.

            // Existing logic used `chapters.length` as the denominator.
            // If I download a range 10-20 (11 chars), and story has 100.
            // If I return progress 1/11, that's fine for the modal.
            // But if `StoryActions` expects whole story progress...

            // The `useStoryDetails` hook uses the progress to show a bar.
            // If the user selects a range, the bar should probably reflect that range's progress?
            // Let's pass the valid "total" for this operation.

            const totalToProcess = queue.length;
            let processedInThisSession = 0;

            await notificationService.startForegroundService(
                `Downloading ${story.title}`,
                `Starting download... (0/${totalToProcess})`
            );

            const settings = await storageService.getSettings();
            const CONCURRENCY = settings.downloadConcurrency || 1;
            const DELAY = settings.downloadDelay || 500;

            console.log(`[DownloadService] Starting download. Queue size: ${totalToProcess}, Concurrency: ${CONCURRENCY}`);

            if (totalToProcess === 0) {
                // Nothing to do
            } else {
                let queueIndex = 0;
                const activeWorkers: Promise<void>[] = [];

                const updateProgressSafe = async (completed: number, currentTitle: string) => {
                    // We report: Total items = totalToProcess
                    // Current items = processedInThisSession
                    onProgress?.(totalToProcess, completed, currentTitle);
                    await notificationService.updateProgress(completed, totalToProcess, `Downloading: ${currentTitle}`);
                };

                const worker = async () => {
                    while (queueIndex < queue.length) {
                        const job = queue[queueIndex++];
                        const { chapter, index: originalIndex } = job;

                        // Notify start
                        processedInThisSession++;
                        await updateProgressSafe(processedInThisSession, chapter.title);

                        const updatedChapter = await this.downloadChapter(story, chapter, originalIndex);
                        chapters[originalIndex] = updatedChapter;

                        // Update global downloaded count for the story
                        // Recalculate from source of truth to be safe
                        downloadedCount = chapters.filter(c => c.downloaded).length;

                        // Save periodically
                        if (processedInThisSession % 5 === 0) {
                            const updatedStory: Story = {
                                ...story,
                                chapters,
                                downloadedChapters: downloadedCount,
                                lastUpdated: Date.now(),
                            };
                            await storageService.updateStory(updatedStory);
                        }

                        if (DELAY > 0) {
                            await new Promise(resolve => setTimeout(resolve, DELAY));
                        }
                    }
                };

                for (let i = 0; i < CONCURRENCY; i++) {
                    activeWorkers.push(worker());
                }

                await Promise.all(activeWorkers);
            }

            // Final save
            downloadedCount = chapters.filter(c => c.downloaded).length;
            // Determine status: if we downloaded *everything* in the story, it's Completed.
            // Otherwise it is Partial.
            const finalStatus = downloadedCount === chapters.length ? DownloadStatus.Completed : DownloadStatus.Partial;

            const finalStory: Story = {
                ...story,
                chapters,
                downloadedChapters: downloadedCount,
                status: finalStatus,
                lastUpdated: Date.now(),
            };

            await storageService.updateStory(finalStory);

            await notificationService.showCompletionNotification(
                'Download Finished',
                `${story.title}: processed ${totalToProcess} chapters.`
            );

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
