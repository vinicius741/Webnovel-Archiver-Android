import AsyncStorage from "@react-native-async-storage/async-storage";
import { DownloadJob, DownloadJobErrorDetails, JobStatus } from "./types";

const QUEUE_STORAGE_KEY = "wa_download_queue_v2";

export class DownloadQueue {
  private jobs: DownloadJob[] = [];
  private initialized = false;

  async init(): Promise<void> {
    if (this.initialized) return;
    try {
      const json = await AsyncStorage.getItem(QUEUE_STORAGE_KEY);
      if (json) {
        this.jobs = JSON.parse(json);
        let needsSave = false;
        this.jobs.forEach((job) => {
          if (job.status === "downloading") {
            job.status = "pending";
            needsSave = true;
          }
        });
        if (needsSave) {
          await this._save();
        }
      }
    } catch (e) {
      console.error("[DownloadQueue] Failed to load queue", e);
    }
    this.initialized = true;
  }

  private async _save(): Promise<void> {
    try {
      await AsyncStorage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(this.jobs));
    } catch (e) {
      console.error("[DownloadQueue] Failed to save queue", e);
    }
  }

  async save(): Promise<void> {
    await this._save();
  }

  addJob(job: DownloadJob): void {
    const existingIndex = this.jobs.findIndex((j) => j.id === job.id);
    if (existingIndex !== -1) {
      const existing = this.jobs[existingIndex];
      if (
        existing.status === "failed" ||
        existing.status === "completed" ||
        existing.status === "cancelled"
      ) {
        this.jobs[existingIndex] = this.preparePendingJob(job, {
          retryCount: existing.retryCount ?? 0,
        });
        void this._save();
      }
      return;
    }
    this.jobs.push({ ...job });
    void this._save();
  }

  removeJob(id: string): void {
    this.jobs = this.jobs.filter((j) => j.id !== id);
    void this._save();
  }

  updateJobStatus(
    id: string,
    status: JobStatus,
    error?: string,
    details?: Partial<DownloadJobErrorDetails>,
  ): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job) {
      job.status = status;
      if (error !== undefined) {
        job.error = error;
      }
      if (details?.category) {
        job.errorCategory = details.category;
      }
      if (details?.code !== undefined) {
        job.errorCode = details.code;
      }
      if (details?.failedAt !== undefined) {
        job.lastFailedAt = details.failedAt;
      }
      if (status === "paused") {
        job.pausedAt = Date.now();
        job.nextRetryAt = undefined;
      }
      if (status === "downloading" || status === "completed") {
        job.nextRetryAt = undefined;
        job.pausedAt = undefined;
      }
      if (status === "completed") {
        this.clearError(job);
      }
      void this._save();
    }
  }

  updateJob(updatedJob: DownloadJob): void {
    const index = this.jobs.findIndex((j) => j.id === updatedJob.id);
    if (index !== -1) {
      this.jobs[index] = updatedJob;
      void this._save();
    }
  }

  getNextPending(): DownloadJob | undefined {
    return this.jobs.find((j) => j.status === "pending");
  }

  getAllJobs(): DownloadJob[] {
    return [...this.jobs];
  }

  getJobsForStory(storyId: string): DownloadJob[] {
    return this.jobs.filter((j) => j.storyId === storyId);
  }

  getStats(): {
    pending: number;
    active: number;
    total: number;
    paused: number;
    completed: number;
    failed: number;
    cancelled: number;
  } {
    const pending = this.jobs.filter((j) => j.status === "pending").length;
    const active = this.jobs.filter((j) => j.status === "downloading").length;
    const paused = this.jobs.filter((j) => j.status === "paused").length;
    const completed = this.jobs.filter((j) => j.status === "completed").length;
    const failed = this.jobs.filter((j) => j.status === "failed").length;
    const cancelled = this.jobs.filter((j) => j.status === "cancelled").length;
    return {
      pending,
      active,
      total: this.jobs.length,
      paused,
      completed,
      failed,
      cancelled,
    };
  }

  pauseJob(id: string): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job && (job.status === "pending" || job.status === "downloading")) {
      job.status = "paused";
      job.pausedAt = Date.now();
      void this._save();
    }
  }

  resumeJob(id: string): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job && job.status === "paused") {
      job.status = "pending";
      job.pausedAt = undefined;
      job.nextRetryAt = undefined;
      void this._save();
    }
  }

  retryJob(id: string): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job && job.status === "failed") {
      this.markPending(job);
      job.retryCount = (job.retryCount || 0) + 1;
      void this._save();
    }
  }

  retryFailedForStory(storyId: string): number {
    return this.retryFailedJobs((job) => job.storyId === storyId);
  }

  retryAllFailed(): number {
    return this.retryFailedJobs(() => true);
  }

  private retryFailedJobs(shouldRetry: (job: DownloadJob) => boolean): number {
    let count = 0;
    this.jobs.forEach((job) => {
      if (job.status === "failed" && shouldRetry(job)) {
        this.markPending(job);
        job.retryCount = (job.retryCount || 0) + 1;
        count++;
      }
    });
    if (count > 0) {
      void this._save();
    }
    return count;
  }

  scheduleRetry(
    id: string,
    nextRetryAt: number,
    error: string,
    details: Partial<DownloadJobErrorDetails> = {},
  ): void {
    const job = this.jobs.find((j) => j.id === id);
    if (!job) return;
    job.status = "pending";
    job.retryCount = (job.retryCount || 0) + 1;
    job.error = error;
    job.errorCategory = details.category;
    job.errorCode = details.code;
    job.lastFailedAt = details.failedAt ?? Date.now();
    job.nextRetryAt = nextRetryAt;
    job.pausedAt = undefined;
    void this._save();
  }

  pauseAll(): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.status === "pending" || job.status === "downloading") {
        job.status = "paused";
        job.pausedAt = Date.now();
        job.nextRetryAt = undefined;
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }

  pausePendingForStory(storyId: string, reason?: string): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.storyId === storyId && job.status === "pending") {
        job.status = "paused";
        job.pausedAt = Date.now();
        if (reason) {
          job.error = reason;
        }
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }

  resumeAll(): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.status === "paused") {
        job.status = "pending";
        job.pausedAt = undefined;
        job.nextRetryAt = undefined;
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }

  cancelAll(): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (
        job.status === "pending" ||
        job.status === "paused" ||
        job.status === "downloading"
      ) {
        job.status = "cancelled";
        job.error = "cancelled";
        job.errorCategory = "cancelled";
        job.errorCode = "CANCELLED";
        job.lastFailedAt = Date.now();
        job.nextRetryAt = undefined;
        job.pausedAt = undefined;
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }

  clearCompleted(storyId?: string): void {
    if (storyId) {
      this.jobs = this.jobs.filter(
        (j) => !(j.storyId === storyId && j.status === "completed"),
      );
    } else {
      this.jobs = this.jobs.filter((j) => j.status !== "completed");
    }
    void this._save();
  }

  clearFinished(storyId?: string): void {
    if (storyId) {
      this.jobs = this.jobs.filter(
        (j) =>
          !(
            j.storyId === storyId &&
            (j.status === "completed" ||
              j.status === "failed" ||
              j.status === "cancelled")
          ),
      );
    } else {
      this.jobs = this.jobs.filter(
        (j) =>
          j.status !== "completed" &&
          j.status !== "failed" &&
          j.status !== "cancelled",
      );
    }
    void this._save();
  }

  removeJobsForStory(storyId: string): void {
    this.jobs = this.jobs.filter((j) => j.storyId !== storyId);
    void this._save();
  }

  clearAll(): void {
    this.jobs = [];
    void this._save();
  }

  cancelPending(reason: string = "cancelled"): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.status === "pending") {
        job.status = "cancelled";
        job.error = reason;
        job.errorCategory = "cancelled";
        job.errorCode = "CANCELLED";
        job.lastFailedAt = Date.now();
        job.nextRetryAt = undefined;
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }

  cancelJob(id: string, reason: string = "cancelled"): void {
    const job = this.jobs.find((j) => j.id === id);
    if (
      job &&
      (job.status === "pending" ||
        job.status === "paused" ||
        job.status === "downloading")
    ) {
      job.status = "cancelled";
      job.error = reason;
      job.errorCategory = "cancelled";
      job.errorCode = "CANCELLED";
      job.lastFailedAt = Date.now();
      job.pausedAt = undefined;
      job.nextRetryAt = undefined;
      void this._save();
    }
  }

  private preparePendingJob(
    job: DownloadJob,
    overrides: Partial<DownloadJob> = {},
  ): DownloadJob {
    return {
      ...job,
      ...overrides,
      status: "pending",
      error: undefined,
      errorCategory: undefined,
      errorCode: undefined,
      lastFailedAt: undefined,
      nextRetryAt: undefined,
      pausedAt: undefined,
    };
  }

  private markPending(job: DownloadJob): void {
    job.status = "pending";
    this.clearError(job);
    job.nextRetryAt = undefined;
    job.pausedAt = undefined;
  }

  private clearError(job: DownloadJob): void {
    job.error = undefined;
    job.errorCategory = undefined;
    job.errorCode = undefined;
    job.lastFailedAt = undefined;
  }
}

export const downloadQueue = new DownloadQueue();
