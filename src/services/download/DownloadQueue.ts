import AsyncStorage from '@react-native-async-storage/async-storage';
import { DownloadJob, JobStatus } from './types';

const QUEUE_STORAGE_KEY = 'wa_download_queue_v2';

export class DownloadQueue {
    private jobs: DownloadJob[] = [];
    private initialized = false;

    async init(): Promise<void> {
        if (this.initialized) return;
        try {
            const json = await AsyncStorage.getItem(QUEUE_STORAGE_KEY);
            if (json) {
                this.jobs = JSON.parse(json);
                // Reset stuck jobs
                this.jobs.forEach(job => {
                    if (job.status === 'downloading') {
                        job.status = 'pending';
                    }
                });
            }
        } catch (e) {
            console.error('[DownloadQueue] Failed to load queue', e);
        }
        this.initialized = true;
    }

    private async save(): Promise<void> {
        try {
            await AsyncStorage.setItem(QUEUE_STORAGE_KEY, JSON.stringify(this.jobs));
        } catch (e) {
            console.error('[DownloadQueue] Failed to save queue', e);
        }
    }

    addJob(job: DownloadJob): void {
        const existingIndex = this.jobs.findIndex(j => j.id === job.id);
        if (existingIndex !== -1) {
            const existing = this.jobs[existingIndex];
            // If it was failed or completed (re-download), reset it
            if (existing.status === 'failed' || existing.status === 'completed') {
                this.jobs[existingIndex] = { ...job, status: 'pending', retryCount: 0 };
                this.save();
            }
            return;
        }
        this.jobs.push(job);
        this.save();
    }

    removeJob(id: string): void {
        this.jobs = this.jobs.filter(j => j.id !== id);
        this.save();
    }

    updateJobStatus(id: string, status: JobStatus, error?: string): void {
        const job = this.jobs.find(j => j.id === id);
        if (job) {
            job.status = status;
            if (error) job.error = error;
            this.save();
        }
    }

    updateJob(updatedJob: DownloadJob): void {
        const index = this.jobs.findIndex(j => j.id === updatedJob.id);
        if (index !== -1) {
            this.jobs[index] = updatedJob;
            this.save();
        }
    }

    getNextPending(): DownloadJob | undefined {
        return this.jobs.find(j => j.status === 'pending');
    }

    getJobsForStory(storyId: string): DownloadJob[] {
        return this.jobs.filter(j => j.storyId === storyId);
    }

    getStats(): { pending: number, active: number, total: number } {
        const pending = this.jobs.filter(j => j.status === 'pending').length;
        const active = this.jobs.filter(j => j.status === 'downloading').length;
        return { pending, active, total: this.jobs.length };
    }

    clearCompleted(storyId?: string): void {
        if (storyId) {
            this.jobs = this.jobs.filter(j => !(j.storyId === storyId && j.status === 'completed'));
        } else {
            this.jobs = this.jobs.filter(j => j.status !== 'completed');
        }
        this.save();
    }

    clearAll(): void {
        this.jobs = [];
        this.save();
    }
}

export const downloadQueue = new DownloadQueue();
