import { renderHook, act } from "@testing-library/react-native";
import { useDownloadQueue } from "../useDownloadQueue";
import { downloadManager } from "../../services/download/DownloadManager";
import { downloadQueue } from "../../services/download/DownloadQueue";
import { DownloadJob, QueueStats } from "../../services/download/types";

jest.mock("../../services/download/DownloadManager", () => ({
  downloadManager: {
    on: jest.fn(),
    off: jest.fn(),
    pauseJob: jest.fn().mockResolvedValue(undefined),
    resumeJob: jest.fn().mockResolvedValue(undefined),
    cancelJob: jest.fn().mockResolvedValue(undefined),
    pauseAll: jest.fn().mockResolvedValue(undefined),
    resumeAll: jest.fn().mockResolvedValue(undefined),
    cancelAll: jest.fn().mockResolvedValue(undefined),
    retryJob: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock("../../services/download/DownloadQueue", () => ({
  downloadQueue: {
    getAllJobs: jest.fn().mockReturnValue([]),
    getStats: jest.fn().mockReturnValue({
      total: 0,
      pending: 0,
      active: 0,
      completed: 0,
      failed: 0,
      paused: 0,
    }),
    clearCompleted: jest.fn(),
  },
}));

describe("useDownloadQueue", () => {
  const mockJob: DownloadJob = {
    id: "job-1",
    storyId: "story-1",
    storyTitle: "Test Story",
    chapterIndex: 0,
    chapter: {
      id: "c1",
      title: "Chapter 1",
      url: "http://test.com/c1",
    },
    status: "pending",
    addedAt: Date.now(),
    retryCount: 0,
  };

  const mockStats: QueueStats = {
    total: 1,
    pending: 1,
    active: 0,
    completed: 0,
    failed: 0,
    paused: 0,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    (downloadQueue.getAllJobs as jest.Mock).mockReturnValue([]);
    (downloadQueue.getStats as jest.Mock).mockReturnValue({
      total: 0,
      pending: 0,
      active: 0,
      completed: 0,
      failed: 0,
      paused: 0,
    });
  });

  describe("initialization", () => {
    it("should initialize with queue snapshot", () => {
      const { result } = renderHook(() => useDownloadQueue());

      expect(result.current.jobs).toEqual([]);
      expect(result.current.stats).toEqual({
        total: 0,
        pending: 0,
        active: 0,
        completed: 0,
        failed: 0,
        paused: 0,
      });
    });

    it("should subscribe to download manager events", () => {
      renderHook(() => useDownloadQueue());

      expect(downloadManager.on).toHaveBeenCalledWith(
        "queue-updated",
        expect.any(Function),
      );
      expect(downloadManager.on).toHaveBeenCalledWith(
        "job-started",
        expect.any(Function),
      );
      expect(downloadManager.on).toHaveBeenCalledWith(
        "job-completed",
        expect.any(Function),
      );
      expect(downloadManager.on).toHaveBeenCalledWith(
        "job-failed",
        expect.any(Function),
      );
      expect(downloadManager.on).toHaveBeenCalledWith(
        "job-paused",
        expect.any(Function),
      );
      expect(downloadManager.on).toHaveBeenCalledWith(
        "job-resumed",
        expect.any(Function),
      );
    });

    it("should unsubscribe on unmount", () => {
      const { unmount } = renderHook(() => useDownloadQueue());

      unmount();

      expect(downloadManager.off).toHaveBeenCalledTimes(6);
    });
  });

  describe("jobsByStory", () => {
    it("should group jobs by story", () => {
      (downloadQueue.getAllJobs as jest.Mock).mockReturnValue([
        { ...mockJob, id: "job-1", storyId: "story-1" },
        { ...mockJob, id: "job-2", storyId: "story-1" },
        { ...mockJob, id: "job-3", storyId: "story-2", storyTitle: "Story 2" },
      ]);

      const { result } = renderHook(() => useDownloadQueue());

      expect(result.current.jobsByStory).toHaveLength(2);
      expect(result.current.jobsByStory[0].storyId).toBe("story-2");
      expect(result.current.jobsByStory[0].jobs).toHaveLength(1);
      expect(result.current.jobsByStory[1].storyId).toBe("story-1");
      expect(result.current.jobsByStory[1].jobs).toHaveLength(2);
    });

    it("should return empty array when no jobs", () => {
      const { result } = renderHook(() => useDownloadQueue());

      expect(result.current.jobsByStory).toEqual([]);
    });
  });

  describe("pauseJob", () => {
    it("should call downloadManager.pauseJob", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.pauseJob("job-1");
      });

      expect(downloadManager.pauseJob).toHaveBeenCalledWith("job-1");
    });
  });

  describe("resumeJob", () => {
    it("should call downloadManager.resumeJob", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.resumeJob("job-1");
      });

      expect(downloadManager.resumeJob).toHaveBeenCalledWith("job-1");
    });
  });

  describe("cancelJob", () => {
    it("should call downloadManager.cancelJob", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.cancelJob("job-1");
      });

      expect(downloadManager.cancelJob).toHaveBeenCalledWith("job-1");
    });
  });

  describe("pauseAll", () => {
    it("should call downloadManager.pauseAll", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.pauseAll();
      });

      expect(downloadManager.pauseAll).toHaveBeenCalled();
    });
  });

  describe("resumeAll", () => {
    it("should call downloadManager.resumeAll", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.resumeAll();
      });

      expect(downloadManager.resumeAll).toHaveBeenCalled();
    });
  });

  describe("cancelAll", () => {
    it("should call downloadManager.cancelAll", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.cancelAll();
      });

      expect(downloadManager.cancelAll).toHaveBeenCalled();
    });
  });

  describe("clearCompleted", () => {
    it("should call downloadQueue.clearCompleted and refresh", () => {
      const { result } = renderHook(() => useDownloadQueue());

      act(() => {
        result.current.clearCompleted();
      });

      expect(downloadQueue.clearCompleted).toHaveBeenCalled();
      expect(downloadQueue.getAllJobs).toHaveBeenCalledTimes(2); // Initial + refresh
    });
  });

  describe("retryJob", () => {
    it("should call downloadManager.retryJob", async () => {
      const { result } = renderHook(() => useDownloadQueue());

      await act(async () => {
        await result.current.retryJob("job-1");
      });

      expect(downloadManager.retryJob).toHaveBeenCalledWith("job-1");
    });
  });

  describe("refreshState", () => {
    it("should refresh jobs and stats from queue", () => {
      (downloadQueue.getAllJobs as jest.Mock).mockReturnValueOnce([]);
      (downloadQueue.getStats as jest.Mock).mockReturnValueOnce(mockStats);

      const { result } = renderHook(() => useDownloadQueue());

      act(() => {
        result.current.refreshState();
      });

      expect(downloadQueue.getAllJobs).toHaveBeenCalled();
      expect(downloadQueue.getStats).toHaveBeenCalled();
    });
  });
});
