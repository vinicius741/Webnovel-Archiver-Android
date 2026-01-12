import { DownloadQueue } from '../download/DownloadQueue';
import { DownloadJob } from '../download/types';
import AsyncStorage from '@react-native-async-storage/async-storage';

jest.mock('@react-native-async-storage/async-storage');

describe('DownloadQueue', () => {
    const QUEUE_STORAGE_KEY = 'wa_download_queue_v2';

    const mockJob: DownloadJob = {
        id: 'job1',
        storyId: 'story1',
        storyTitle: 'Story 1',
        chapterIndex: 0,
        chapter: {
            id: 'c1',
            title: 'Chapter 1',
            url: 'http://example.com/chapter1',
            downloaded: false,
        },
        status: 'pending',
        addedAt: Date.now(),
        retryCount: 0,
    };

    const mockJob2: DownloadJob = {
        id: 'job2',
        storyId: 'story1',
        storyTitle: 'Story 1',
        chapterIndex: 1,
        chapter: {
            id: 'c2',
            title: 'Chapter 2',
            url: 'http://example.com/chapter2',
            downloaded: false,
        },
        status: 'downloading',
        addedAt: Date.now(),
        retryCount: 0,
    };

    const mockJob3: DownloadJob = {
        id: 'job3',
        storyId: 'story2',
        storyTitle: 'Story 2',
        chapterIndex: 0,
        chapter: {
            id: 'c3',
            title: 'Chapter 3',
            url: 'http://example.com/chapter3',
            downloaded: false,
        },
        status: 'completed',
        addedAt: Date.now(),
        retryCount: 0,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
        (AsyncStorage.setItem as jest.Mock).mockResolvedValue(undefined);
    });

    it('should initialize with empty queue', async () => {
        const queue = new DownloadQueue();
        await queue.init();

        expect(AsyncStorage.getItem).toHaveBeenCalledWith(QUEUE_STORAGE_KEY);
    });

    it('should load saved queue from storage', async () => {
        const savedQueue = JSON.stringify([mockJob, mockJob2]);
        (AsyncStorage.getItem as jest.Mock).mockResolvedValue(savedQueue);

        const queue = new DownloadQueue();
        await queue.init();

        expect(AsyncStorage.getItem).toHaveBeenCalledWith(QUEUE_STORAGE_KEY);
    });

    it('should reset downloading jobs on init', async () => {
        const savedQueue = JSON.stringify([mockJob2]);
        (AsyncStorage.getItem as jest.Mock).mockResolvedValue(savedQueue);

        const queue = new DownloadQueue();
        await queue.init();

        expect(AsyncStorage.setItem).toHaveBeenCalledWith(
            QUEUE_STORAGE_KEY,
            expect.stringContaining('"status":"pending"')
        );
    });

    it('should add new job to queue', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);

        expect(AsyncStorage.setItem).toHaveBeenCalled();
    });

    it('should replace existing job with failed or completed status', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob3);
        queue.addJob({ ...mockJob3, status: 'failed' });

        expect(AsyncStorage.setItem).toHaveBeenCalled();
    });

    it('should not replace existing job if status is pending', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);

        const beforeSetCalls = (AsyncStorage.setItem as jest.Mock).mock.calls.length;
        queue.addJob(mockJob);

        expect((AsyncStorage.setItem as jest.Mock).mock.calls.length).toBe(beforeSetCalls);
    });

    it('should add multiple jobs', async () => {
        const queue = new DownloadQueue();
        await queue.init();

        queue.addJob(mockJob);
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);

        expect(AsyncStorage.setItem).toHaveBeenCalled();
    });

    it('should remove job from queue', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);
        queue.removeJob('job1');

        expect(AsyncStorage.setItem).toHaveBeenCalled();
    });

    it('should update job status', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        const testJob = { ...mockJob, id: 'status-test-job' };
        queue.addJob(testJob);
        queue.updateJobStatus('status-test-job', 'downloading');

        expect(AsyncStorage.setItem).toHaveBeenCalledWith(
            QUEUE_STORAGE_KEY,
            expect.stringContaining('"status":"downloading"')
        );
    });

    it('should update job status with error', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob({ ...mockJob, id: 'test-job' });
        queue.updateJobStatus('test-job', 'failed', 'Network error');

        expect(AsyncStorage.setItem).toHaveBeenCalledWith(
            QUEUE_STORAGE_KEY,
            expect.stringContaining('"error":"Network error"')
        );
    });

    it('should update entire job', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        const testJob = { ...mockJob, id: 'update-job-test' };
        queue.addJob(testJob);
        const updatedJob = { ...testJob, retryCount: 2 };
        queue.updateJob(updatedJob);

        expect(AsyncStorage.setItem).toHaveBeenCalledWith(
            QUEUE_STORAGE_KEY,
            expect.stringContaining('"retryCount":2')
        );
    });

    it('should get next pending job', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob({ ...mockJob, id: 'pending-job' });
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);

        const nextJob = queue.getNextPending();

        expect(nextJob).toEqual(expect.objectContaining({ id: 'pending-job', status: 'pending' }));
    });

    it('should return undefined if no pending jobs', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);

        const nextJob = queue.getNextPending();

        expect(nextJob).toBeUndefined();
    });

    it('should get jobs for specific story', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);

        const story1Jobs = queue.getJobsForStory('story1');

        expect(story1Jobs).toHaveLength(2);
        expect(story1Jobs.every(j => j.storyId === 'story1')).toBe(true);
    });

    it('should return empty array for story with no jobs', async () => {
        const queue = new DownloadQueue();
        await queue.init();

        const jobs = queue.getJobsForStory('nonexistent');

        expect(jobs).toEqual([]);
    });

    it('should get queue stats', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob({ ...mockJob, id: 'pending-job' });
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);

        const stats = queue.getStats();

        expect(stats.total).toBe(3);
        expect(stats.pending).toBe(1);
        expect(stats.active).toBe(1);
    });

    it('should get stats for empty queue', async () => {
        const queue = new DownloadQueue();
        await queue.init();

        const stats = queue.getStats();

        expect(stats.total).toBe(0);
        expect(stats.pending).toBe(0);
        expect(stats.active).toBe(0);
    });

    it('should clear completed jobs for specific story', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);
        queue.addJob(mockJob3);
        queue.clearCompleted('story2');

        const story1Jobs = queue.getJobsForStory('story1');
        const story2Jobs = queue.getJobsForStory('story2');

        expect(story1Jobs).toHaveLength(1);
        expect(story2Jobs).toHaveLength(0);
    });

    it('should clear all completed jobs', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);
        queue.addJob(mockJob2);
        queue.addJob(mockJob3);
        queue.clearCompleted();

        const stats = queue.getStats();
        expect(stats.total).toBe(2);
    });

    it('should clear all jobs', async () => {
        const queue = new DownloadQueue();
        await queue.init();
        queue.addJob(mockJob);
        queue.addJob(mockJob2);
        queue.clearAll();

        const stats = queue.getStats();
        expect(stats.total).toBe(0);
    });

    it('should handle storage error gracefully', async () => {
        (AsyncStorage.getItem as jest.Mock).mockRejectedValue(new Error('Storage error'));

        const queue = new DownloadQueue();
        await queue.init();

        expect(queue.getStats().total).toBe(0);
    });

    it('should handle save error gracefully', async () => {
        (AsyncStorage.setItem as jest.Mock).mockRejectedValue(new Error('Save error'));

        const queue = new DownloadQueue();
        await queue.init();

        expect(() => queue.addJob(mockJob)).not.toThrow();
    });
});
