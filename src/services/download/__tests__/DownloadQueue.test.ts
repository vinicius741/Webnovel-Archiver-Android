import AsyncStorage from '@react-native-async-storage/async-storage';
import { DownloadQueue } from '../DownloadQueue';
import { DownloadJob, JobStatus } from '../types';
import { Chapter } from '../../../types';

jest.mock('@react-native-async-storage/async-storage', () => ({
    getItem: jest.fn(),
    setItem: jest.fn(),
}));

describe('DownloadQueue', () => {
    let queue: DownloadQueue;

    const mockChapter: Chapter = {
        id: 'c1',
        title: 'Chapter 1',
        url: 'https://example.com/ch1',
    };

    const mockJob: DownloadJob = {
        id: 'job1',
        storyId: 'story1',
        storyTitle: 'Test Story',
        chapterIndex: 0,
        chapter: mockChapter,
        status: 'pending',
        addedAt: Date.now(),
        retryCount: 0,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        queue = new DownloadQueue();
    });

    describe('Initialization', () => {
        it('should initialize with empty queue if no saved data', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

            await queue.init();

            expect(AsyncStorage.getItem).toHaveBeenCalledWith('wa_download_queue_v2');
        });

        it('should load queue from AsyncStorage', async () => {
            const savedJobs = [mockJob, { ...mockJob, id: 'job2' }];
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify(savedJobs));

            await queue.init();

            expect(AsyncStorage.getItem).toHaveBeenCalledWith('wa_download_queue_v2');
        });

        it('should reset stuck jobs on init', async () => {
            const stuckJob: DownloadJob = { ...mockJob, status: 'downloading' };
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([stuckJob]));

            await queue.init();
            const stats = queue.getStats();

            expect(stats.pending).toBe(1);
            expect(stats.active).toBe(0);
        });

        it('should handle corrupted queue data gracefully', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue('invalid json');

            await queue.init();

            expect(AsyncStorage.getItem).toHaveBeenCalled();
            const stats = queue.getStats();
            expect(stats.total).toBe(0);
        });

        it('should handle storage error gracefully', async () => {
            (AsyncStorage.getItem as jest.Mock).mockRejectedValue(new Error('Storage error'));

            await queue.init();

            const stats = queue.getStats();
            expect(stats.total).toBe(0);
        });

        it('should not reinitialize if already initialized', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

            await queue.init();
            await queue.init();

            expect(AsyncStorage.getItem).toHaveBeenCalledTimes(1);
        });
    });

    describe('Persistence', () => {
        it('should save queue to AsyncStorage', () => {
            (queue as any).jobs = [mockJob];
            (queue as any).save();

            expect(AsyncStorage.setItem).toHaveBeenCalledWith(
                'wa_download_queue_v2',
                JSON.stringify([mockJob])
            );
        });

        it('should handle save errors gracefully', () => {
            (AsyncStorage.setItem as jest.Mock).mockRejectedValue(new Error('Save error'));

            (queue as any).jobs = [mockJob];
            (queue as any).save();

            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });
    });

    describe('Job Management', () => {
        it('should add job to queue', async () => {
            await queue.init();

            queue.addJob(mockJob);

            const stats = queue.getStats();
            expect(stats.total).toBe(1);
            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });

        it('should add multiple jobs', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.addJob({ ...mockJob, id: 'job2' });
            queue.addJob({ ...mockJob, id: 'job3' });

            const stats = queue.getStats();
            expect(stats.total).toBe(3);
        });

        it('should update existing job', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.updateJobStatus('job1', 'downloading');

            const nextJob = queue.getNextPending();
            expect(nextJob).toBeUndefined();

            const stats = queue.getStats();
            expect(stats.active).toBe(0);
        });

        it('should reset failed job to pending when adding again', async () => {
            await queue.init();

            const failedJob: DownloadJob = { ...mockJob, status: 'failed', error: 'test error' };
            queue.addJob(failedJob);

            queue.addJob(mockJob);

            const stats = queue.getStats();
            expect(stats.pending).toBe(1);
        });

        it('should reset completed job to pending when adding again', async () => {
            await queue.init();

            const completedJob: DownloadJob = { ...mockJob, status: 'completed', retryCount: 3 };
            queue.addJob(completedJob);

            queue.addJob(mockJob);

            const stats = queue.getStats();
            expect(stats.pending).toBe(1);
        });

        it('should not modify pending job when adding again', async () => {
            await queue.init();

            queue.addJob(mockJob);
            const statsBefore = queue.getStats();

            queue.addJob(mockJob);

            const statsAfter = queue.getStats();
            expect(statsAfter.pending).toBe(statsBefore.pending);
        });

        it('should update job status', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.updateJobStatus('job1', 'downloading');

            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });

        it('should update job status with error message', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.updateJobStatus('job1', 'failed', 'Network error');

            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });

        it('should update entire job object', async () => {
            await queue.init();

            queue.addJob(mockJob);
            const updatedJob: DownloadJob = {
                ...mockJob,
                status: 'completed',
                retryCount: 5,
            };
            queue.updateJob(updatedJob);

            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });

        it('should remove job from queue', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.removeJob('job1');

            const stats = queue.getStats();
            expect(stats.total).toBe(0);
            expect(AsyncStorage.setItem).toHaveBeenCalled();
        });

        it('should remove multiple jobs', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.addJob({ ...mockJob, id: 'job2' });
            queue.addJob({ ...mockJob, id: 'job3' });

            queue.removeJob('job1');
            queue.removeJob('job2');

            const stats = queue.getStats();
            expect(stats.total).toBe(1);
        });

        it('should clear all jobs', async () => {
            await queue.init();

            queue.addJob(mockJob);
            queue.addJob({ ...mockJob, id: 'job2' });

            queue.clearAll();

            const stats = queue.getStats();
            expect(stats.total).toBe(0);
        });

        it('should clear completed jobs for specific story', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'completed' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'pending', storyId: 'story2' });
            queue.addJob({ ...mockJob, id: 'job3', status: 'completed' });

            queue.clearCompleted('story1');

            const stats = queue.getStats();
            expect(stats.total).toBe(2);
        });

        it('should clear all completed jobs', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'completed' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'pending' });
            queue.addJob({ ...mockJob, id: 'job3', status: 'completed' });

            queue.clearCompleted();

            const stats = queue.getStats();
            expect(stats.total).toBe(1);
            expect(stats.pending).toBe(1);
        });
    });

    describe('Statistics', () => {
        it('should calculate pending jobs', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'pending' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'downloading' });
            queue.addJob({ ...mockJob, id: 'job3', status: 'pending' });

            const stats = queue.getStats();
            expect(stats.pending).toBe(2);
        });

        it('should calculate active jobs', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'pending' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'downloading' });
            queue.addJob({ ...mockJob, id: 'job3', status: 'downloading' });

            const stats = queue.getStats();
            expect(stats.active).toBe(2);
        });

        it('should calculate total jobs', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'pending' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'downloading' });
            queue.addJob({ ...mockJob, id: 'job3', status: 'completed' });
            queue.addJob({ ...mockJob, id: 'job4', status: 'failed' });

            const stats = queue.getStats();
            expect(stats.total).toBe(4);
        });

        it('should return empty stats for empty queue', async () => {
            await queue.init();

            const stats = queue.getStats();
            expect(stats.pending).toBe(0);
            expect(stats.active).toBe(0);
            expect(stats.total).toBe(0);
        });
    });

    describe('Queue Operations', () => {
        it('should get next pending job', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'pending' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'downloading' });

            const nextJob = queue.getNextPending();
            expect(nextJob).toBeDefined();
            expect(nextJob?.id).toBe('job1');
        });

        it('should return undefined when no pending jobs', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', status: 'completed' });
            queue.addJob({ ...mockJob, id: 'job2', status: 'failed' });

            const nextJob = queue.getNextPending();
            expect(nextJob).toBeUndefined();
        });

        it('should get jobs for specific story', async () => {
            await queue.init();

            queue.addJob({ ...mockJob, id: 'job1', storyId: 'story1' });
            queue.addJob({ ...mockJob, id: 'job2', storyId: 'story1' });
            queue.addJob({ ...mockJob, id: 'job3', storyId: 'story2' });

            const story1Jobs = queue.getJobsForStory('story1');
            expect(story1Jobs).toHaveLength(2);

            const story2Jobs = queue.getJobsForStory('story2');
            expect(story2Jobs).toHaveLength(1);
        });

        it('should return empty array for non-existent story', async () => {
            await queue.init();

            const jobs = queue.getJobsForStory('nonexistent');
            expect(jobs).toHaveLength(0);
        });
    });
});
