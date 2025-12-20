import { Story, Chapter } from '../../types';

export type JobStatus = 'pending' | 'downloading' | 'failed' | 'completed';

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
}

export interface QueueStats {
    total: number;
    pending: number;
    active: number;
    completed: number;
    failed: number;
}
