import { Chapter } from "../../types";

export type JobStatus =
  | "pending"
  | "downloading"
  | "failed"
  | "completed"
  | "paused"
  | "cancelled";

export type DownloadErrorCategory =
  | "network"
  | "rate_limit"
  | "parse"
  | "storage"
  | "missing_story"
  | "missing_provider"
  | "invalid_chapter"
  | "cancelled"
  | "unknown";

export interface DownloadJobErrorDetails {
  message: string;
  category: DownloadErrorCategory;
  code?: string;
  failedAt: number;
}

export interface DownloadJob {
  id: string; // usually `${storyId}_${chapterIndex}`
  storyId: string;
  storyTitle: string;
  chapterIndex: number;
  chapter: Chapter;
  status: JobStatus;
  addedAt: number;
  retryCount: number;
  error?: string;
  errorCategory?: DownloadErrorCategory;
  errorCode?: string;
  lastFailedAt?: number;
  nextRetryAt?: number;
  maxRetries?: number;
  pausedAt?: number;
}

export interface QueueStats {
  total: number;
  pending: number;
  active: number;
  completed: number;
  failed: number;
  paused: number;
  cancelled: number;
}
