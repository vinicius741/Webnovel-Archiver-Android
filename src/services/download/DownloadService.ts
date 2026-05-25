import { Story, Chapter, DownloadStatus } from "../../types";
import { downloadManager } from "./DownloadManager";
import { storageService } from "../storage/StorageService";
import { readChapterFile, writeChapterFile } from "../storage/fileSystem";
import {
  compileSentencePatternsExport,
  compileRegexRules,
  applyDownloadCleanupPrecompiled,
} from "../../utils/textCleanup";
import { DownloadJob } from "./types";

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

  private static readonly CLEANUP_CONCURRENCY = 5;

  /**
   * Applies text cleanup rules to already downloaded chapters offline.
   *
   * Processes chapters in parallel batches to overlap async file I/O
   * and avoid recompiling regex patterns per chapter.
   */
  async applySentenceRemovalToStory(
    story: Story,
    onProgress?: (current: number, total: number, chapterTitle: string) => void,
  ): Promise<{ processed: number; errors: number; sentencesRemoved: number }> {
    const [sentenceRemovalList, regexCleanupRules] = await Promise.all([
      storageService.getSentenceRemovalList(),
      storageService.getRegexCleanupRules(),
    ]);

    const sentencePatterns = compileSentencePatternsExport(sentenceRemovalList);
    const compiledRegexRules = compileRegexRules(regexCleanupRules, "download");

    const downloadable = story.chapters
      .map((chapter, index) => ({ chapter, index }))
      .filter(({ chapter }) => chapter.downloaded && !!chapter.filePath);

    const total = story.chapters.length;
    let processed = 0;
    let errors = 0;
    let sentencesRemoved = 0;

    const concurrency = DownloadService.CLEANUP_CONCURRENCY;

    for (let batch = 0; batch < downloadable.length; batch += concurrency) {
      const chunk = downloadable.slice(batch, batch + concurrency);

      const results = await Promise.allSettled(
        chunk.map(async ({ chapter }) => {
          const html = await readChapterFile(chapter.filePath!);
          if (!html) return null;
          const result = applyDownloadCleanupPrecompiled(
            html,
            sentencePatterns,
            compiledRegexRules,
          );
          await writeChapterFile(chapter.filePath!, result.html);
          return result;
        }),
      );

      for (let j = 0; j < results.length; j++) {
        const chapter = chunk[j].chapter;
        const settled = results[j];

        if (settled.status === "fulfilled") {
          if (settled.value) {
            processed++;
            sentencesRemoved += settled.value.sentencesRemoved;
          }
        } else {
          console.error(
            `Failed to process chapter: ${chapter.title}`,
            settled.reason,
          );
          errors++;
        }

        if (onProgress) {
          onProgress(batch + j + 1, total, chapter.title);
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
