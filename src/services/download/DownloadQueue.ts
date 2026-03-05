import AsyncStorage from "@react-native-async-storage/async-storage";
import { DownloadJob, JobStatus } from "./types";

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
      if (existing.status === "failed" || existing.status === "completed") {
        this.jobs[existingIndex] = { ...job, status: "pending", retryCount: 0 };
        void this._save();
      }
      return;
    }
    this.jobs.push(job);
    void this._save();
  }

  removeJob(id: string): void {
    this.jobs = this.jobs.filter((j) => j.id !== id);
    void this._save();
  }

  updateJobStatus(id: string, status: JobStatus, error?: string): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job) {
      job.status = status;
      if (error) job.error = error;
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
  } {
    const pending = this.jobs.filter((j) => j.status === "pending").length;
    const active = this.jobs.filter((j) => j.status === "downloading").length;
    const paused = this.jobs.filter((j) => j.status === "paused").length;
    const completed = this.jobs.filter((j) => j.status === "completed").length;
    const failed = this.jobs.filter((j) => j.status === "failed").length;
    return {
      pending,
      active,
      total: this.jobs.length,
      paused,
      completed,
      failed,
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
      void this._save();
    }
  }

  retryJob(id: string): void {
    const job = this.jobs.find((j) => j.id === id);
    if (job && job.status === "failed") {
      job.status = "pending";
      job.retryCount = (job.retryCount || 0) + 1;
      job.error = undefined;
      void this._save();
    }
  }

  pauseAll(): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.status === "pending" || job.status === "downloading") {
        job.status = "paused";
        job.pausedAt = Date.now();
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
      if (job.status === "pending" || job.status === "paused") {
        job.status = "failed";
        job.error = "cancelled";
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

  clearAll(): void {
    this.jobs = [];
    void this._save();
  }

  cancelPending(reason: string = "cancelled"): void {
    let changed = false;
    this.jobs.forEach((job) => {
      if (job.status === "pending") {
        job.status = "failed";
        job.error = reason;
        changed = true;
      }
    });
    if (changed) {
      void this._save();
    }
  }
}

export const downloadQueue = new DownloadQueue();
