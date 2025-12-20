import { Story, Chapter, DownloadStatus } from '../types';
import { downloadManager } from './download/DownloadManager';
import { storageService } from './StorageService';

class DownloadService {

    /**
     * Queues a single chapter for download.
     */
    async downloadChapter(story: Story, chapter: Chapter, index: number): Promise<Chapter> {
        // We add to queue and return immediately.
        // The UI should rely on listeners or storage updates.
        await downloadManager.addJob({
            id: `${story.id}_${index}`,
            storyId: story.id,
            storyTitle: story.title,
            chapterIndex: index,
            chapter: chapter,
            status: 'pending',
            addedAt: Date.now(),
            retryCount: 0
        });

        // Optimistically update status to show pending? 
        // Or just return as is.
        return chapter;
    }

    /**
     * Queues a range of chapters.
     */
    async downloadRange(
        story: Story,
        startIndex: number,
        endIndex: number
    ): Promise<Story> {
        const jobs = story.chapters
            .map((ch, index) => ({ chapter: ch, index }))
            .filter(({ index }) => index >= startIndex && index <= endIndex)
            .filter(({ chapter }) => !chapter.downloaded) // Skip already downloaded? Use logic preference.
            .map(({ chapter, index }) => ({
                id: `${story.id}_${index}`,
                storyId: story.id,
                storyTitle: story.title,
                chapterIndex: index,
                chapter: chapter,
                status: 'pending' as const,
                addedAt: Date.now(),
                retryCount: 0
            }));

        if (jobs.length === 0) return story;

        // Set status to Downloading
        const updatedStory = {
            ...story,
            status: DownloadStatus.Downloading,
            lastUpdated: Date.now()
        };
        await storageService.updateStory(updatedStory);

        await downloadManager.addJobs(jobs);

        return updatedStory;
    }

    /**
     * Queues all chapters.
     */
    async downloadAllChapters(
        story: Story
    ): Promise<Story> {
        return this.downloadRange(story, 0, story.chapters.length - 1);
    }

    /**
     * Expose manager for event subscriptions
     */
    getManager() {
        return downloadManager;
    }
}

export const downloadService = new DownloadService();
