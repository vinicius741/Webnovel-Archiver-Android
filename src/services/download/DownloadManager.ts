import EventEmitter from "events";
import { downloadQueue } from "./DownloadQueue";
import { DownloadJob } from "./types";
import { DownloadStoryCache } from "./DownloadStoryCache";
import { DownloadNotificationManager } from "./DownloadNotificationManager";
import { sourceRegistry } from "../source/SourceRegistry";
import { fetchPage, HttpError } from "../network/fetcher";
import { saveChapter } from "../storage/fileSystem";
import { storageService } from "../StorageService";
import { DownloadStatus, SourceDownloadSettingsMap } from "../../types";
import { applyDownloadCleanup } from "../../utils/textCleanup";

const FLUSH_INTERVAL = 2000;
const STORY_RATE_LIMIT_FAILURE_THRESHOLD = 2;

export class DownloadManager extends EventEmitter {
  private isRunning = false;
  private activeWorkers = 0;
  private concurrency = 3;
  private globalDelay = 0;
  private cancelRequested = false;
  private cancelledJobIds = new Set<string>();
  private flushTimer: ReturnType<typeof setInterval> | null = null;
  private consecutiveRateLimitFailuresByStory = new Map<string, number>();

  private sourceSettings: SourceDownloadSettingsMap = {};
  private activeWorkersBySource = new Map<string, number>();
  private nextAllowedJobAtBySource = new Map<string, number>();

  private storyCache = new DownloadStoryCache();
  private notificationManager = new DownloadNotificationManager();

  constructor() {
    super();
    void this.init();
  }

  async init() {
    await downloadQueue.init();
    const [settings, sourceSettings] = await Promise.all([
      storageService.getSettings(),
      storageService.getSourceDownloadSettings(),
    ]);
    this.concurrency =
      settings.downloadConcurrency > 0 ? settings.downloadConcurrency : 3;
    this.globalDelay = settings.downloadDelay ?? 0;
    this.sourceSettings = sourceSettings;

    const stats = downloadQueue.getStats();
    if (stats.pending > 0) {
      void this.start();
    }
  }

  updateSettings(
    globalConcurrency: number,
    globalDelay: number,
    sourceMap: SourceDownloadSettingsMap,
  ) {
    this.concurrency =
      globalConcurrency > 0 ? globalConcurrency : this.concurrency;
    this.globalDelay = globalDelay;
    this.sourceSettings = sourceMap;
  }

  getSourceSettings(providerName: string): {
    concurrency: number;
    delay: number;
  } {
    const override = this.sourceSettings[providerName];
    return {
      concurrency: override?.concurrency ?? this.concurrency,
      delay: override?.delay ?? this.globalDelay,
    };
  }

  private getProviderNameForJob(job: DownloadJob): string | undefined {
    return sourceRegistry.getProvider(job.chapter.url)?.name;
  }

  private pickNextEligibleJob(): DownloadJob | undefined {
    const allJobs = downloadQueue.getAllJobs();
    const now = Date.now();

    for (const job of allJobs) {
      if (job.status !== "pending") continue;

      const providerName = this.getProviderNameForJob(job);
      if (!providerName) continue;

      const sourceConcurrency = this.getSourceSettings(providerName).concurrency;
      const activeForSource =
        this.activeWorkersBySource.get(providerName) ?? 0;
      if (activeForSource >= sourceConcurrency) continue;

      const nextAllowedAt =
        this.nextAllowedJobAtBySource.get(providerName) ?? 0;
      if (nextAllowedAt > now) continue;

      return job;
    }

    return undefined;
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
    this.cancelledJobIds.delete(job.id);
    downloadQueue.addJob(job);
    this.emit("queue-updated");
    void this.start();
  }

  async addJobs(jobs: DownloadJob[]) {
    jobs.forEach((job) => this.cancelledJobIds.delete(job.id));
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
    const processedStoryIds = new Set<string>();

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

      const pending = this.pickNextEligibleJob();

      if (!pending) {
        if (this.activeWorkers === 0) {
          const allJobs = downloadQueue.getAllJobs();
          const now = Date.now();
          let sleepUntil = 0;
          let hasPending = false;
          for (const job of allJobs) {
            if (job.status !== "pending") continue;
            const providerName = this.getProviderNameForJob(job);
            if (!providerName) continue;
            hasPending = true;
            const nextAllowedAt =
              this.nextAllowedJobAtBySource.get(providerName) ?? 0;
            if (nextAllowedAt > now) {
              if (sleepUntil === 0 || nextAllowedAt < sleepUntil) {
                sleepUntil = nextAllowedAt;
              }
            }
          }
          if (!hasPending) {
            break;
          }
          if (sleepUntil > now) {
            await new Promise((resolve) =>
              setTimeout(resolve, sleepUntil - now),
            );
            continue;
          }
          await new Promise((resolve) => setTimeout(resolve, 200));
          continue;
        }
        await new Promise((resolve) => setTimeout(resolve, 200));
        continue;
      }

      const providerName = this.getProviderNameForJob(pending);
      processedStoryIds.add(pending.storyId);

      this.activeWorkers++;
      if (providerName) {
        this.activeWorkersBySource.set(
          providerName,
          (this.activeWorkersBySource.get(providerName) ?? 0) + 1,
        );
      }

      const sourceDelay = providerName
        ? this.getSourceSettings(providerName).delay
        : 0;

      // We do NOT await here. We fire and forget (the promise is handled).
      this.processJob(pending).then(() => {
        this.activeWorkers--;
        if (providerName) {
          const current = this.activeWorkersBySource.get(providerName) ?? 0;
          this.activeWorkersBySource.set(
            providerName,
            Math.max(0, current - 1),
          );
          if (sourceDelay > 0) {
            this.nextAllowedJobAtBySource.set(
              providerName,
              Date.now() + sourceDelay,
            );
          }
        }
        this.emit("queue-updated");
        void this.notificationManager.update();
      }).catch((err) => {
        this.activeWorkers--;
        if (providerName) {
          const current = this.activeWorkersBySource.get(providerName) ?? 0;
          this.activeWorkersBySource.set(
            providerName,
            Math.max(0, current - 1),
          );
        }
        console.error("[DownloadManager] Worker failed:", err);
      });

      // Brief pause to allow event loop to breathe
      await new Promise((resolve) => setTimeout(resolve, 10));
    }

    this.isRunning = false;
    this.stopFlushTimer();
    await this.storyCache.flushAllPending();
    await this.notificationManager.showCompletion(this.cancelRequested);
    this.emit("all-complete", Array.from(processedStoryIds));
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
      this.cancelledJobIds.add(jobId);
      downloadQueue.cancelJob(jobId, "cancelled by user");
      this.emit("job-failed", job, new Error("cancelled by user"));
      this.emit("queue-updated");
      this.emit("notification-update");
    }
  }

  async cancelAll() {
    this.cancelRequested = true;
    downloadQueue
      .getAllJobs()
      .filter(
        (job) =>
          job.status === "pending" ||
          job.status === "paused" ||
          job.status === "downloading",
      )
      .forEach((job) => this.cancelledJobIds.add(job.id));
    downloadQueue.cancelAll();
    this.emit("queue-updated");
    this.emit("notification-update");
  }

  async removeStory(storyId: string) {
    downloadQueue
      .getJobsForStory(storyId)
      .forEach((job) => this.cancelledJobIds.add(job.id));
    downloadQueue.removeJobsForStory(storyId);
    this.storyCache.clearStoryCache(storyId);
    this.consecutiveRateLimitFailuresByStory.delete(storyId);
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
      if (this.cancelledJobIds.has(job.id)) {
        this.cancelledJobIds.delete(job.id);
        return;
      }

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
      this.consecutiveRateLimitFailuresByStory.delete(job.storyId);
      this.emit("job-completed", job, filePath);
    } catch (error: unknown) {
      if (this.cancelledJobIds.has(job.id)) {
        this.cancelledJobIds.delete(job.id);
        return;
      }
      await this.handleRateLimitFailure(job, error);
      console.error(`[DownloadManager] Job ${job.id} failed`, error);
      const message = error instanceof Error ? error.message : "Unknown error";
      downloadQueue.updateJobStatus(job.id, "failed", message);
      this.emit("job-failed", job, error);
    }
  }

  private async handleRateLimitFailure(
    job: DownloadJob,
    error: unknown,
  ): Promise<void> {
    const story = await storageService.getStory(job.storyId);
    const isScribbleHubStory = story?.sourceUrl?.includes("scribblehub.com");
    const isRateLimitError =
      error instanceof HttpError &&
      (error.status === 403 || error.status === 429);

    if (!isScribbleHubStory || !isRateLimitError) {
      this.consecutiveRateLimitFailuresByStory.delete(job.storyId);
      return;
    }

    const failureCount =
      (this.consecutiveRateLimitFailuresByStory.get(job.storyId) ?? 0) + 1;
    this.consecutiveRateLimitFailuresByStory.set(job.storyId, failureCount);

    if (failureCount < STORY_RATE_LIMIT_FAILURE_THRESHOLD) {
      return;
    }

    const pauseReason =
      "Paused remaining chapter downloads after repeated ScribbleHub 403/429 responses. Resume later to retry.";
    downloadQueue.pausePendingForStory(job.storyId, pauseReason);
    this.emit("queue-updated");
    this.emit("notification-update");
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
      cleanedContent.html,
    );
  }
}

export const downloadManager = new DownloadManager();
