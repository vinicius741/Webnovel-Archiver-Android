import { Story, Chapter, DownloadStatus } from "../types";
import { downloadManager } from "./download/DownloadManager";
import { storageService } from "./StorageService";
import { readChapterFile, saveChapter } from "./storage/fileSystem";
import { applyDownloadCleanup } from "../utils/textCleanup";
import { DownloadJob } from "./download/types";

class DownloadService {
  private createJobsFromIndexes(
    story: Story,
    indexes: number[],
  ): DownloadJob[] {
    return indexes
      .filter((index) => index >= 0 && index < story.chapters.length)
      .map((index) => ({ chapter: story.chapters[index], index }))
      .filter(({ chapter }) => !chapter.downloaded)
      .map(({ chapter, index }) => ({
        id: `${story.id}_${index}`,
        storyId: story.id,
        storyTitle: story.title,
        chapterIndex: index,
        chapter,
        status: "pending" as const,
        addedAt: Date.now(),
        retryCount: 0,
      }));
  }

  private buildQueuedStory(story: Story): Story {
    return {
      ...story,
      status: DownloadStatus.Downloading,
      lastUpdated: Date.now(),
    };
  }

  /**
   * Queues a single chapter for download.
   */
  async downloadChapter(
    story: Story,
    chapter: Chapter,
    index: number,
  ): Promise<Chapter> {
    // We add to queue and return immediately.
    // The UI should rely on listeners or storage updates.
    await downloadManager.addJob({
      id: `${story.id}_${index}`,
      storyId: story.id,
      storyTitle: story.title,
      chapterIndex: index,
      chapter: chapter,
      status: "pending",
      addedAt: Date.now(),
      retryCount: 0,
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
    endIndex: number,
  ): Promise<Story> {
    const indexes = Array.from(
      { length: endIndex - startIndex + 1 },
      (_, i) => startIndex + i,
    );
    const jobs = this.createJobsFromIndexes(story, indexes);

    if (jobs.length === 0) return story;

    const updatedStory = this.buildQueuedStory(story);
    await downloadManager.addJobs(jobs);

    return updatedStory;
  }

  /**
   * Queues specific chapters by ID.
   */
  async downloadChaptersByIds(
    story: Story,
    chapterIds: string[],
  ): Promise<Story> {
    if (chapterIds.length === 0) return story;

    const idSet = new Set(chapterIds);
    const indexes = story.chapters.reduce<number[]>((acc, chapter, index) => {
      if (idSet.has(chapter.id)) {
        acc.push(index);
      }
      return acc;
    }, []);
    const jobs = this.createJobsFromIndexes(story, indexes);

    if (jobs.length === 0) return story;

    const updatedStory = this.buildQueuedStory(story);
    await downloadManager.addJobs(jobs);

    return updatedStory;
  }

  /**
   * Queues all chapters.
   */
  async downloadAllChapters(story: Story): Promise<Story> {
    return this.downloadRange(story, 0, story.chapters.length - 1);
  }

  /**
   * Applies text cleanup rules to already downloaded chapters offline.
   */
  async applySentenceRemovalToStory(
    story: Story,
    onProgress?: (current: number, total: number, chapterTitle: string) => void,
  ): Promise<{ processed: number; errors: number; sentencesRemoved: number }> {
    const [sentenceRemovalList, regexCleanupRules] = await Promise.all([
      storageService.getSentenceRemovalList(),
      storageService.getRegexCleanupRules(),
    ]);
    let processed = 0;
    let errors = 0;
    let sentencesRemoved = 0;

    for (let i = 0; i < story.chapters.length; i++) {
      const chapter = story.chapters[i];

      if (chapter.downloaded && chapter.filePath) {
        try {
          const html = await readChapterFile(chapter.filePath);
          if (html) {
            const result = applyDownloadCleanup(
              html,
              sentenceRemovalList,
              regexCleanupRules,
            );
            await saveChapter(story.id, i, chapter.title, result.html);
            sentencesRemoved += result.sentencesRemoved;
            processed++;
          }
        } catch (error) {
          console.error(
            `Failed to process chapter ${i}: ${chapter.title}`,
            error,
          );
          errors++;
        }

        if (onProgress) {
          onProgress(i + 1, story.chapters.length, chapter.title);
        }
      }
    }

    const updatedStory = {
      ...story,
      epubStale: true,
      lastUpdated: Date.now(),
    };
    await storageService.updateStory(updatedStory);

    return { processed, errors, sentencesRemoved };
  }

  /**
   * Expose manager for event subscriptions
   */
  getManager() {
    return downloadManager;
  }
}

export const downloadService = new DownloadService();
