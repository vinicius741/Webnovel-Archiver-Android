import { Story, Chapter, DownloadStatus } from '../types';
import { fetchPage } from './network/fetcher';
import { parseChapterContent } from './parser/content';
import { saveChapter } from './storage/fileSystem';
import { storageService } from './StorageService';
import { notificationService } from './NotificationService';

class DownloadService {
    /**
     * Downloads a single chapter.
     * Updates the chapter object with file path and status, but does NOT save the story to storage.
     * Returns the updated chapter.
     */
    async downloadChapter(storyId: string, chapter: Chapter, index: number): Promise<Chapter> {
        if (!chapter.url) {
            console.warn(`[Download] Chapter ${index} has no URL`);
            return chapter;
        }

        try {
            console.log(`[Download] fetching ${chapter.url}`);
            const html = await fetchPage(chapter.url);
            const content = parseChapterContent(html);

            if (!content || content.length < 50) {
                console.warn(`[Download] Content seems empty for ${chapter.title}`);
            }

            const filePath = await saveChapter(storyId, index, chapter.title, content);

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
        const chapters = [...story.chapters];
        let downloadedCount = 0;

        // Reset status to downloading if not already
        if (story.status !== DownloadStatus.Completed) {
            await storageService.updateStoryStatus(story.id, DownloadStatus.Downloading);
        }

        try {
            await notificationService.startForegroundService(
                `Downloading ${story.title}`,
                'Starting download...'
            );

            // Sequential download to avoid overwhelming the target site (and memory)
            // In a real app we might do batches of 3-5
            for (let i = 0; i < chapters.length; i++) {
                const chapter = chapters[i];

                // Skip if already downloaded (basic check)
                if (chapter.downloaded && chapter.filePath) {
                    downloadedCount++;
                    onProgress?.(chapters.length, downloadedCount, `Skipping ${chapter.title}`);
                    continue;
                }

                onProgress?.(chapters.length, downloadedCount + 1, `Downloading ${chapter.title}`);
                await notificationService.updateProgress(
                    downloadedCount + 1,
                    chapters.length,
                    `Downloading ${i + 1}/${chapters.length}: ${chapter.title}`
                );

                const updatedChapter = await this.downloadChapter(story.id, chapter, i);
                chapters[i] = updatedChapter;

                if (updatedChapter.downloaded) {
                    downloadedCount++;
                }

                // Save progress every 5 chapters or on the last one
                if ((i + 1) % 5 === 0 || i === chapters.length - 1) {
                    const updatedStory: Story = {
                        ...story,
                        chapters,
                        downloadedChapters: downloadedCount,
                        lastUpdated: Date.now(),
                    };
                    await storageService.updateStory(updatedStory);
                }

                // Small delay to be nice to servers
                await new Promise(resolve => setTimeout(resolve, 500));
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
            await notificationService.stopForegroundService();
        }
    }
}

export const downloadService = new DownloadService();
