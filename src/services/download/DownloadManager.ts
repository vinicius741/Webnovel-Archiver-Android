import EventEmitter from "events";
import { downloadQueue } from "./DownloadQueue";
import { DownloadJob } from "./types";
import { DownloadStoryCache } from "./DownloadStoryCache";
import { DownloadNotificationManager } from "./DownloadNotificationManager";
import { sourceRegistry } from "../source/SourceRegistry";
import { fetchPage } from "../network/fetcher";
import { saveChapter } from "../storage/fileSystem";
import { storageService } from "../StorageService";
import { DownloadStatus } from "../../types";
import { applyDownloadCleanup } from "../../utils/textCleanup";

const FLUSH_INTERVAL = 2000;

export class DownloadManager extends EventEmitter {
  private isRunning = false;
  private activeWorkers = 0;
  private concurrency = 3;
  private cancelRequested = false;
  private flushTimer: ReturnType<typeof setInterval> | null = null;

  private storyCache = new DownloadStoryCache();
  private notificationManager = new DownloadNotificationManager();

  constructor() {
    super();
    void this.init();
  }

  async init() {
    await downloadQueue.init();
    const settings = await storageService.getSettings();
    this.concurrency =
      settings.downloadConcurrency > 0 ? settings.downloadConcurrency : 3;

    const stats = downloadQueue.getStats();
    if (stats.pending > 0) {
      void this.start();
    }
  }

  private startFlushTimer() {
    if (this.flushTimer) return;
    this.flushTimer = setInterval(() => {
      void this.storyCache.flushAllPending();
    }, FLUSH_INTERVAL);
  }

  private stopFlushTimer() {
    if (this.flushTimer) {
      clearInterval(this.flushTimer);
      this.flushTimer = null;
    }
  }

  async addJob(job: DownloadJob) {
    downloadQueue.addJob(job);
    this.emit("queue-updated");
    void this.start();
  }

  async addJobs(jobs: DownloadJob[]) {
    jobs.forEach((j) => downloadQueue.addJob(j));
    this.emit("queue-updated");
    void this.start();
  }

  async start() {
    if (this.isRunning) return;
    this.cancelRequested = false;
    this.isRunning = true;
    this.startFlushTimer();
    void this.processLoop();
    void this.notificationManager.showInitial();
  }

  private async processLoop() {
    while (true) {
      if (this.activeWorkers >= this.concurrency) {
        await new Promise((resolve) => setTimeout(resolve, 200));
        continue;
      }

      if (this.cancelRequested) {
        if (this.activeWorkers === 0) {
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 200));
        continue;
      }

      const pending = downloadQueue.getNextPending();

      if (!pending) {
        if (this.activeWorkers === 0) {
          break;
        }
        await new Promise((resolve) => setTimeout(resolve, 200));
        continue;
      }

      this.activeWorkers++;
      // We do NOT await here. We fire and forget (the promise is handled).
      this.processJob(pending).then(() => {
        this.activeWorkers--;
        this.emit("queue-updated");
        void this.notificationManager.update();
      }).catch((err) => {
        console.error("[DownloadManager] Worker failed:", err);
      });

      // Brief pause to allow event loop to breathe
      await new Promise((resolve) => setTimeout(resolve, 10));
    }

    this.isRunning = false;
    this.stopFlushTimer();
    await this.storyCache.flushAllPending();
    await this.notificationManager.showCompletion(this.cancelRequested);
    this.emit("all-complete");
  }

  async pauseJob(jobId: string) {
    const job = downloadQueue.getAllJobs().find((j) => j.id === jobId);
    if (job && (job.status === "pending" || job.status === "downloading")) {
      downloadQueue.pauseJob(jobId);
      this.emit("job-paused", job);
      this.emit("queue-updated");
      this.emit("notification-update");
    }
  }

  async resumeJob(jobId: string) {
    const job = downloadQueue.getAllJobs().find((j) => j.id === jobId);
    if (job && job.status === "paused") {
      downloadQueue.resumeJob(jobId);
      this.emit("job-resumed", job);
      this.emit("queue-updated");
      this.emit("notification-update");
      void this.start();
    }
  }

  async pauseAll() {
    downloadQueue.pauseAll();
    this.emit("queue-updated");
    this.emit("notification-update");
  }

  async resumeAll() {
    downloadQueue.resumeAll();
    this.emit("queue-updated");
    this.emit("notification-update");
    void this.start();
  }

  async cancelJob(jobId: string) {
    const job = downloadQueue.getAllJobs().find((j) => j.id === jobId);
    if (
      job &&
      (job.status === "pending" ||
        job.status === "paused" ||
        job.status === "downloading")
    ) {
      downloadQueue.updateJobStatus(jobId, "failed", "cancelled by user");
      this.emit("job-failed", job, new Error("cancelled by user"));
      this.emit("queue-updated");
      this.emit("notification-update");
    }
  }

  async cancelAll() {
    downloadQueue.cancelAll();
    this.emit("queue-updated");
    this.emit("notification-update");
  }

  async retryJob(jobId: string): Promise<boolean> {
    const job = downloadQueue.getAllJobs().find((j) => j.id === jobId);
    if (!job) {
      console.warn(
        `[DownloadManager] Cannot retry job ${jobId}: job not found`,
      );
      return false;
    }
    if (job.status !== "failed") {
      console.warn(
        `[DownloadManager] Cannot retry job ${jobId}: status is ${job.status}, expected 'failed'`,
      );
      return false;
    }
    downloadQueue.retryJob(jobId);
    this.emit("queue-updated");
    this.emit("notification-update");
    void this.start();
    return true;
  }

  private async processJob(job: DownloadJob) {
    if (job.status !== "pending") return;

    downloadQueue.updateJobStatus(job.id, "downloading");
    this.emit("job-started", job);

    try {
      const filePath = await this.executeDownload(job);

      const story = await this.storyCache.getCachedStory(job.storyId);
      if (story) {
        const chapters = [...story.chapters];
        if (chapters[job.chapterIndex]) {
          chapters[job.chapterIndex] = {
            ...chapters[job.chapterIndex],
            filePath,
            downloaded: true,
          };

          const downloadedCount = chapters.filter((c) => c.downloaded).length;
          const status =
            downloadedCount === chapters.length
              ? DownloadStatus.Completed
              : DownloadStatus.Partial;

          this.storyCache.updateCachedStory(job.storyId, {
            ...story,
            chapters,
            downloadedChapters: downloadedCount,
            status,
            lastUpdated: Date.now(),
          });

          this.storyCache.queuePendingUpdate(
            job.storyId,
            job.chapterIndex,
            filePath,
          );

          if (this.storyCache.shouldFlush(job.storyId)) {
            this.storyCache.flushStoryToStorage(job.storyId).catch((err) => {
              console.error(
                `[DownloadManager] Flush failed for ${job.storyId}`,
                err,
              );
            });
          }
        }
      }

      downloadQueue.updateJobStatus(job.id, "completed");
      this.emit("job-completed", job, filePath);
    } catch (error: unknown) {
      console.error(`[DownloadManager] Job ${job.id} failed`, error);
      const message = error instanceof Error ? error.message : "Unknown error";
      downloadQueue.updateJobStatus(job.id, "failed", message);
      this.emit("job-failed", job, error);
    }
  }

  private async executeDownload(job: DownloadJob): Promise<string> {
    const story = await storageService.getStory(job.storyId);
    if (!story) throw new Error("Story not found");

    const provider = sourceRegistry.getProvider(story.sourceUrl);
    if (!provider) throw new Error(`No provider for ${story.sourceUrl}`);

    if (!job.chapter.url) throw new Error("Chapter has no URL");

    const html = await fetchPage(job.chapter.url);
    const content = provider.parseChapterContent(html);

    if (!content || content.length < 50) {
      throw new Error("Content empty or too short");
    }

    const [sentenceRemovalList, regexCleanupRules] = await Promise.all([
      storageService.getSentenceRemovalList(),
      storageService.getRegexCleanupRules(),
    ]);
    const cleanedContent = applyDownloadCleanup(
      content,
      sentenceRemovalList,
      regexCleanupRules,
    );

    return await saveChapter(
      job.storyId,
      job.chapterIndex,
      job.chapter.title,
      cleanedContent,
    );
  }
}

export const downloadManager = new DownloadManager();
