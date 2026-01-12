
import { renderHook, waitFor, act } from '@testing-library/react-native';
import { useDownloadProgress } from '../useDownloadProgress';
import { downloadManager } from '../../services/download/DownloadManager';
import { downloadQueue } from '../../services/download/DownloadQueue';
import { DownloadJob } from '../../services/download/types';

jest.mock('../../services/download/DownloadManager');
jest.mock('../../services/download/DownloadQueue', () => ({
    downloadQueue: {
        init: jest.fn().mockResolvedValue(undefined),
        getJobsForStory: jest.fn(),
        getStats: jest.fn(() => ({ pending: 0, active: 0, total: 0, failed: 0 })),
    },
}));

const mockGetJobsForStory = jest.fn();
(downloadQueue.getJobsForStory as jest.Mock) = mockGetJobsForStory;

describe('useDownloadProgress', () => {
    const storyId = 'story1';

    beforeEach(() => {
        jest.clearAllMocks();
        mockGetJobsForStory.mockReturnValue([]);
        (downloadQueue.getStats as jest.Mock).mockReturnValue({ pending: 0, active: 0, total: 0, failed: 0 });
        (downloadManager.on as jest.Mock).mockReturnValue(downloadManager);
        (downloadManager.off as jest.Mock).mockReturnValue(downloadManager);
    });

    it('should initialize with no progress when no jobs exist', () => {
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.progress).toBe(0);
        expect(result.current.status).toBe('');
        expect(result.current.isDownloading).toBe(false);
    });

    it('should handle active download jobs', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c2', storyId, storyTitle: 'Test', chapterIndex: 1, chapter: { id: 'c2', title: 'Chapter 2', url: 'http://c2' }, status: 'downloading', addedAt: 0, retryCount: 0 },
            { id: 'c3', storyId, storyTitle: 'Test', chapterIndex: 2, chapter: { id: 'c3', title: 'Chapter 3', url: 'http://c3' }, status: 'pending', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.isDownloading).toBe(true);
        expect(result.current.progress).toBe(1 / 3);
        expect(result.current.status).toBe('Downloading: Chapter 2 (1/3)');
    });

    it('should handle queued jobs with no active download', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c2', storyId, storyTitle: 'Test', chapterIndex: 1, chapter: { id: 'c2', title: 'Chapter 2', url: 'http://c2' }, status: 'pending', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.isDownloading).toBe(true);
        expect(result.current.progress).toBe(0.5);
        expect(result.current.status).toBe('Queued (1/2)');
    });

    it('should handle completed download with all chapters successful', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c2', storyId, storyTitle: 'Test', chapterIndex: 1, chapter: { id: 'c2', title: 'Chapter 2', url: 'http://c2' }, status: 'completed', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.isDownloading).toBe(false);
        expect(result.current.progress).toBe(1);
        expect(result.current.status).toBe('Download Complete');
    });

    it('should handle completed download with some failed chapters', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c2', storyId, storyTitle: 'Test', chapterIndex: 1, chapter: { id: 'c2', title: 'Chapter 2', url: 'http://c2' }, status: 'failed', addedAt: 0, retryCount: 0 },
            { id: 'c3', storyId, storyTitle: 'Test', chapterIndex: 2, chapter: { id: 'c3', title: 'Chapter 3', url: 'http://c3' }, status: 'completed', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.isDownloading).toBe(false);
        expect(result.current.progress).toBe(1);
        expect(result.current.status).toBe('Finished (1 failed)');
    });

    it('should subscribe to download events', () => {
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        renderHook(() => useDownloadProgress(storyId));

        expect(downloadManager.on).toHaveBeenCalledWith('queue-updated', expect.any(Function));
        expect(downloadManager.on).toHaveBeenCalledWith('job-started', expect.any(Function));
        expect(downloadManager.on).toHaveBeenCalledWith('job-completed', expect.any(Function));
        expect(downloadManager.on).toHaveBeenCalledWith('job-failed', expect.any(Function));
    });

    it('should update on queue updated event', async () => {
        let updateHandler: any;
        downloadManager.on = jest.fn((event, handler) => {
            if (event === 'queue-updated') updateHandler = handler;
            return downloadManager;
        });
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'downloading', addedAt: 0, retryCount: 0 },
        ]);

        await waitFor(() => {
            if (updateHandler) {
                act(() => updateHandler());
            }
        });

        expect(result.current.isDownloading).toBe(true);
    });

    it('should update on job started event for the correct story', async () => {
        let startHandler: any;
        downloadManager.on = jest.fn((event, handler) => {
            if (event === 'job-started') startHandler = handler;
            return downloadManager;
        });
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'downloading', addedAt: 0, retryCount: 0 },
        ]);

        await waitFor(() => {
            if (startHandler) {
                act(() => startHandler({ storyId }));
            }
        });

        expect(result.current.isDownloading).toBe(true);
    });

    it('should not update on job started event for different story', async () => {
        let startHandler: any;
        downloadManager.on = jest.fn((event, handler) => {
            if (event === 'job-started') startHandler = handler;
            return downloadManager;
        });
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        await waitFor(() => {
            if (startHandler) startHandler({ storyId: 'otherStory' });
        });

        expect(result.current.isDownloading).toBe(false);
    });

    it('should update on job completed event', async () => {
        let completedHandler: any;
        downloadManager.on = jest.fn((event, handler) => {
            if (event === 'job-completed') completedHandler = handler;
            return downloadManager;
        });
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
        ]);

        await waitFor(() => {
            if (completedHandler) completedHandler({ storyId });
        });

        expect(result.current.isDownloading).toBe(false);
    });

    it('should update on job failed event', async () => {
        let failedHandler: any;
        downloadManager.on = jest.fn((event, handler) => {
            if (event === 'job-failed') failedHandler = handler;
            return downloadManager;
        });
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'failed', addedAt: 0, retryCount: 0 },
        ]);

        await waitFor(() => {
            if (failedHandler) failedHandler({ storyId });
        });

        expect(result.current.isDownloading).toBe(false);
    });

    it('should unsubscribe from events on unmount', () => {
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue([]);

        const { unmount } = renderHook(() => useDownloadProgress(storyId));

        unmount();

        expect(downloadManager.off).toHaveBeenCalledWith('queue-updated', expect.any(Function));
        expect(downloadManager.off).toHaveBeenCalledWith('job-started', expect.any(Function));
        expect(downloadManager.off).toHaveBeenCalledWith('job-completed', expect.any(Function));
        expect(downloadManager.off).toHaveBeenCalledWith('job-failed', expect.any(Function));
    });

    it('should handle download progress calculation correctly', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Chapter 1', url: 'http://c1' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c2', storyId, storyTitle: 'Test', chapterIndex: 1, chapter: { id: 'c2', title: 'Chapter 2', url: 'http://c2' }, status: 'completed', addedAt: 0, retryCount: 0 },
            { id: 'c3', storyId, storyTitle: 'Test', chapterIndex: 2, chapter: { id: 'c3', title: 'Chapter 3', url: 'http://c3' }, status: 'pending', addedAt: 0, retryCount: 0 },
            { id: 'c4', storyId, storyTitle: 'Test', chapterIndex: 3, chapter: { id: 'c4', title: 'Chapter 4', url: 'http://c4' }, status: 'downloading', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.progress).toBe(0.5);
    });

    it('should show correct chapter title in status when downloading', () => {
        const mockJobs: DownloadJob[] = [
            { id: 'c1', storyId, storyTitle: 'Test', chapterIndex: 0, chapter: { id: 'c1', title: 'Active Chapter Title', url: 'http://c1' }, status: 'downloading', addedAt: 0, retryCount: 0 },
        ];
        (downloadQueue.getJobsForStory as jest.Mock).mockReturnValue(mockJobs);

        const { result } = renderHook(() => useDownloadProgress(storyId));

        expect(result.current.status).toBe('Downloading: Active Chapter Title (0/1)');
    });
});
