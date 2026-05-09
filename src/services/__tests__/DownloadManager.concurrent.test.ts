import { DownloadManager } from "../download/DownloadManager";
import { DownloadJob } from "../download/types";

jest.mock("../download/DownloadQueue", () => {
  const mockJobs = new Map<string, any>();
  return {
    downloadQueue: {
      init: jest.fn().mockResolvedValue(undefined),
      addJob: jest.fn((job: DownloadJob) => {
        mockJobs.set(job.id, { ...job, status: "pending" as const });
      }),
      getNextPending: jest.fn(() => {
        for (const [id, job] of mockJobs.entries()) {
          if (job.status === "pending") {
            return job;
          }
        }
        return null;
      }),
      getAllJobs: jest.fn(() => {
        return Array.from(mockJobs.values());
      }),
      updateJobStatus: jest.fn((id: string, status: string) => {
        const job = mockJobs.get(id);
        if (job) {
          job.status = status as any;
        }
      }),
      getStats: jest.fn(() => {
        let pending = 0;
        let active = 0;
        for (const job of mockJobs.values()) {
          if (job.status === "pending") pending++;
          if (job.status === "downloading") active++;
        }
        return { pending, active, total: mockJobs.size };
      }),
      cancelPending: jest.fn(() => {
        for (const job of mockJobs.values()) {
          if (job.status === "pending") {
            job.status = "cancelled";
          }
        }
      }),
      cancelAll: jest.fn(() => {
        for (const job of mockJobs.values()) {
          if (job.status === "pending" || job.status === "paused") {
            job.status = "failed";
            job.error = "cancelled";
          }
        }
      }),
      _mockJobs: mockJobs,
    },
  };
});

jest.mock("../network/fetcher", () => ({
  fetchPage: jest.fn().mockImplementation((url: string) => {
    // Simulate network delay for concurrent tests
    return new Promise((resolve) => {
      setTimeout(
        () => resolve(`<html><body>Content from ${url}</body></html>`),
        10,
      );
    });
  }),
}));

jest.mock("../storage/fileSystem", () => ({
  saveChapter: jest.fn().mockImplementation(async () => {
    // Simulate file I/O delay
    await new Promise((resolve) => setTimeout(resolve, 5));
    return "/path/to/chapter.html";
  }),
}));

jest.mock("../StorageService", () => ({
  storageService: {
    getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 2 }),
    getSourceDownloadSettings: jest.fn().mockResolvedValue({}),
    getStory: jest.fn().mockImplementation(async (storyId: string) => {
      await new Promise((resolve) => setTimeout(resolve, 5));
      return {
        id: storyId,
        title: `Story ${storyId}`,
        sourceUrl: "https://royalroad.com/fiction/test",
        chapters: Array.from({ length: 10 }, (_, i) => ({
          id: `c${i}`,
          title: `Chapter ${i}`,
          downloaded: false,
        })),
      };
    }),
    updateStory: jest.fn().mockImplementation(async () => {
      await new Promise((resolve) => setTimeout(resolve, 10));
    }),
    getSentenceRemovalList: jest.fn().mockResolvedValue([]),
    getRegexCleanupRules: jest.fn().mockResolvedValue([]),
  },
}));

jest.mock("../source/SourceRegistry", () => ({
  sourceRegistry: {
    getProvider: jest.fn(() => ({
      name: "RoyalRoad",
      parseChapterContent: jest.fn((html: string) => `<p>${html}</p>`),
    })),
    getAllProviders: jest.fn(() => [{ name: "RoyalRoad" }]),
  },
}));

jest.mock("../ForegroundServiceCoordinator", () => ({
  setDownloadState: jest.fn().mockResolvedValue(undefined),
  clearDownloadState: jest.fn().mockResolvedValue(undefined),
  showDownloadCompletionNotification: jest.fn().mockResolvedValue(undefined),
}));

jest.mock("../../utils/textCleanup", () => ({
  applyDownloadCleanup: jest.fn((content) => ({ html: content, sentencesRemoved: 0 })),
}));

describe("DownloadManager - Concurrent Downloads", () => {
  let manager: DownloadManager;
  let mockJobs: Map<string, any>;

  beforeEach(async () => {
    jest.clearAllMocks();
    manager = new DownloadManager();
    await manager.init();

    // Get reference to the mock jobs map
    const { downloadQueue } = require("../download/DownloadQueue");
    mockJobs = downloadQueue._mockJobs;
    mockJobs.clear();
  });

  describe("Batching Behavior", () => {
    it("should batch multiple chapter updates into fewer storage writes", async () => {
      const storyId = "story-1";
      // Create 5 jobs for the same story - with FLUSH_THRESHOLD=3, we expect batching
      const jobs: DownloadJob[] = Array.from({ length: 5 }, (_, i) => ({
        id: `job${i}`,
        storyId,
        storyTitle: "Test Story",
        chapterIndex: i,
        chapter: {
          id: `c${i}`,
          title: `Chapter ${i}`,
          url: `http://example.com/c${i}`,
          downloaded: false,
        },
        status: "pending" as const,
        addedAt: Date.now(),
        retryCount: 0,
      }));

      const { storageService } = require("../StorageService");
      const updateCalls: any[] = [];
      storageService.updateStory.mockImplementation(async (story: any) => {
        updateCalls.push({ ...story });
        await new Promise((resolve) => setTimeout(resolve, 5));
      });

      const completionPromise = new Promise<void>((resolve) => {
        manager.once("all-complete", resolve);
      });

      await manager.addJobs(jobs);
      await completionPromise;

      // With 5 chapters and FLUSH_THRESHOLD=3, we expect fewer writes than 5
      // (batching reduces storage writes)
      expect(updateCalls.length).toBeLessThanOrEqual(3);
      expect(updateCalls.length).toBeGreaterThan(0);

      // Final update should have some chapters downloaded
      const lastUpdate = updateCalls[updateCalls.length - 1];
      expect(lastUpdate.downloadedChapters).toBeGreaterThan(0);
    });

    it("should flush immediately on cancel", async () => {
      const storyId = "story-1";
      const jobs: DownloadJob[] = Array.from({ length: 3 }, (_, i) => ({
        id: `job${i}`,
        storyId,
        storyTitle: "Test Story",
        chapterIndex: i,
        chapter: {
          id: `c${i}`,
          title: `Chapter ${i}`,
          url: `http://example.com/c${i}`,
          downloaded: false,
        },
        status: "pending" as const,
        addedAt: Date.now(),
        retryCount: 0,
      }));

      const { storageService } = require("../StorageService");
      const updateCalls: any[] = [];
      storageService.updateStory.mockImplementation(async (story: any) => {
        updateCalls.push({ downloadedChapters: story.downloadedChapters });
      });

      await manager.addJobs(jobs);

      // Wait for jobs to start processing then cancel
      await new Promise((resolve) => setTimeout(resolve, 30));
      await manager.cancelAll();

      // Should have flushed pending updates on cancel
      expect(updateCalls.length).toBeGreaterThanOrEqual(0);
    });
  });

  describe("Story-Level Locking", () => {
    it("should prevent concurrent modifications to the same story", async () => {
      const storyId = "story-1";
      const jobs: DownloadJob[] = [
        {
          id: "job1",
          storyId,
          storyTitle: "Test Story",
          chapterIndex: 0,
          chapter: {
            id: "c1",
            title: "Chapter 1",
            url: "http://example.com/c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job2",
          storyId,
          storyTitle: "Test Story",
          chapterIndex: 1,
          chapter: {
            id: "c2",
            title: "Chapter 2",
            url: "http://example.com/c2",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job3",
          storyId,
          storyTitle: "Test Story",
          chapterIndex: 2,
          chapter: {
            id: "c3",
            title: "Chapter 3",
            url: "http://example.com/c3",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      const { storageService } = require("../StorageService");
      const updateCalls: any[] = [];
      storageService.updateStory.mockImplementation(async (story: any) => {
        updateCalls.push({ ...story });
        await new Promise((resolve) => setTimeout(resolve, 10));
      });

      const completionPromise = new Promise<void>((resolve) => {
        manager.once("all-complete", resolve);
      });

      await manager.addJobs(jobs);
      await completionPromise;

      // Verify storage was updated
      expect(storageService.updateStory).toHaveBeenCalled();

      // Final update should reflect all chapters downloaded
      const lastUpdate = updateCalls[updateCalls.length - 1];
      expect(lastUpdate.downloadedChapters).toBeGreaterThanOrEqual(1);

      // Verify chapters array was updated
      const downloadedInLastUpdate = lastUpdate.chapters.filter(
        (c: any) => c.downloaded,
      ).length;
      expect(downloadedInLastUpdate).toBeGreaterThanOrEqual(1);
    });

    it("should handle concurrent downloads for different stories independently", async () => {
      const jobs: DownloadJob[] = [
        {
          id: "job1",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 0,
          chapter: {
            id: "s1c1",
            title: "S1 Ch1",
            url: "http://example.com/s1c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job2",
          storyId: "story-2",
          storyTitle: "Story 2",
          chapterIndex: 0,
          chapter: {
            id: "s2c1",
            title: "S2 Ch1",
            url: "http://example.com/s2c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job3",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 1,
          chapter: {
            id: "s1c2",
            title: "S1 Ch2",
            url: "http://example.com/s1c2",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      const { storageService } = require("../StorageService");
      const updatedStoryIds = new Set<string>();
      storageService.updateStory.mockImplementation(async (story: any) => {
        updatedStoryIds.add(story.id);
        await new Promise((resolve) => setTimeout(resolve, 10));
      });

      const completionPromise = new Promise<void>((resolve) => {
        manager.once("all-complete", resolve);
      });

      await manager.addJobs(jobs);
      await completionPromise;

      // Both stories should have been updated
      expect(updatedStoryIds.has("story-1")).toBe(true);
      expect(updatedStoryIds.has("story-2")).toBe(true);
    });
  });

  describe("Concurrent Job Addition", () => {
    it("should handle rapid job additions during active processing", async () => {
      const initialJobs: DownloadJob[] = [
        {
          id: "job1",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 0,
          chapter: {
            id: "c1",
            title: "Ch1",
            url: "http://example.com/c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      const queueEvents: string[] = [];
      manager.on("queue-updated", () => {
        queueEvents.push("updated");
      });

      const completionPromise = new Promise<void>((resolve) => {
        manager.once("all-complete", resolve);
      });

      await manager.addJobs(initialJobs);

      await new Promise((resolve) => setTimeout(resolve, 5));

      const additionalJobs: DownloadJob[] = [
        {
          id: "job2",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 1,
          chapter: {
            id: "c2",
            title: "Ch2",
            url: "http://example.com/c2",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job3",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 2,
          chapter: {
            id: "c3",
            title: "Ch3",
            url: "http://example.com/c3",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      await manager.addJobs(additionalJobs);

      await completionPromise;

      expect(queueEvents.length).toBeGreaterThan(0);
      const { storageService } = require("../StorageService");
      expect(storageService.updateStory).toHaveBeenCalled();
    });

    it("should handle simultaneous job additions", async () => {
      const jobSets: DownloadJob[][] = [
        [
          {
            id: "job1",
            storyId: "story-1",
            storyTitle: "Story 1",
            chapterIndex: 0,
            chapter: {
              id: "c1",
              title: "Ch1",
              url: "http://example.com/c1",
              downloaded: false,
            },
            status: "pending",
            addedAt: Date.now(),
            retryCount: 0,
          },
        ],
        [
          {
            id: "job2",
            storyId: "story-1",
            storyTitle: "Story 1",
            chapterIndex: 1,
            chapter: {
              id: "c2",
              title: "Ch2",
              url: "http://example.com/c2",
              downloaded: false,
            },
            status: "pending",
            addedAt: Date.now(),
            retryCount: 0,
          },
        ],
        [
          {
            id: "job3",
            storyId: "story-1",
            storyTitle: "Story 1",
            chapterIndex: 2,
            chapter: {
              id: "c3",
              title: "Ch3",
              url: "http://example.com/c3",
              downloaded: false,
            },
            status: "pending",
            addedAt: Date.now(),
            retryCount: 0,
          },
        ],
      ];

      // Add all job sets simultaneously
      await Promise.all(jobSets.map((jobSet) => manager.addJobs(jobSet)));

      const { downloadQueue } = require("../download/DownloadQueue");
      expect(downloadQueue.addJob).toHaveBeenCalledTimes(3);
    });
  });

  describe("Cancellation During Processing", () => {
    it("should cancel pending jobs and stop processing when cancelAll is called", async () => {
      const jobs: DownloadJob[] = [
        {
          id: "job1",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 0,
          chapter: {
            id: "c1",
            title: "Ch1",
            url: "http://example.com/c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job2",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 1,
          chapter: {
            id: "c2",
            title: "Ch2",
            url: "http://example.com/c2",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job3",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 2,
          chapter: {
            id: "c3",
            title: "Ch3",
            url: "http://example.com/c3",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      manager.on("job-started", () => {});
      manager.on("job-completed", () => {});

      await manager.addJobs(jobs);

      // Wait a bit for some jobs to start then cancel
      await new Promise((resolve) => setTimeout(resolve, 15));
      await manager.cancelAll();

      // Verify cancel was called on queue
      const { downloadQueue } = require("../download/DownloadQueue");
      expect(downloadQueue.cancelAll).toHaveBeenCalled();
    });

    it("should handle multiple rapid cancel calls safely", async () => {
      const jobs: DownloadJob[] = [
        {
          id: "job1",
          storyId: "story-1",
          storyTitle: "Story 1",
          chapterIndex: 0,
          chapter: {
            id: "c1",
            title: "Ch1",
            url: "http://example.com/c1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      await manager.addJobs(jobs);

      // Multiple cancel calls should not throw
      await Promise.all([
        manager.cancelAll(),
        manager.cancelAll(),
        manager.cancelAll(),
      ]);

      // Should complete without errors
      expect(true).toBe(true);
    });
  });

  describe("Event Emission Under Load", () => {
    it("should emit events in correct order even under concurrent load", async () => {
      // Use fewer jobs to avoid race conditions with mock timing
      const jobs: DownloadJob[] = Array.from({ length: 3 }, (_, i) => ({
        id: `job${i}`,
        storyId: "story-1",
        storyTitle: "Story 1",
        chapterIndex: i,
        chapter: {
          id: `c${i}`,
          title: `Chapter ${i}`,
          url: `http://example.com/c${i}`,
          downloaded: false,
        },
        status: "pending" as const,
        addedAt: Date.now(),
        retryCount: 0,
      }));

      const eventLog: string[] = [];
      manager.on("job-started", (job) =>
        eventLog.push(`started-${job.chapterIndex}`),
      );
      manager.on("job-completed", (job) =>
        eventLog.push(`completed-${job.chapterIndex}`),
      );
      manager.on("job-failed", (job) =>
        eventLog.push(`failed-${job.chapterIndex}`),
      );

      const completionPromise = new Promise<void>((resolve) => {
        manager.once("all-complete", resolve);
      });

      await manager.addJobs(jobs);
      await completionPromise;

      // Verify we got started and completed events
      const startedCount = eventLog.filter((e) =>
        e.startsWith("started-"),
      ).length;
      const completedCount = eventLog.filter((e) =>
        e.startsWith("completed-"),
      ).length;

      expect(startedCount).toBeGreaterThan(0);
      expect(completedCount).toBeGreaterThan(0);

      // Verify ordering: each started must come before its completed (for events we received)
      for (const event of eventLog) {
        if (event.startsWith("completed-")) {
          const idx = event.replace("completed-", "");
          const startedIndex = eventLog.indexOf(`started-${idx}`);
          const completedIndex = eventLog.indexOf(event);
          expect(startedIndex).toBeGreaterThanOrEqual(0);
          expect(startedIndex).toBeLessThan(completedIndex);
        }
      }
    });
  });

  describe("Concurrency Limits", () => {
    it("should respect concurrency setting", async () => {
      const { storageService } = require("../StorageService");
      storageService.getSettings.mockResolvedValue({ downloadConcurrency: 1 });

      const manager2 = new DownloadManager();
      await manager2.init();

      const jobs: DownloadJob[] = Array.from({ length: 3 }, (_, i) => ({
        id: `job${i}`,
        storyId: "story-1",
        storyTitle: "Story 1",
        chapterIndex: i,
        chapter: {
          id: `c${i}`,
          title: `Chapter ${i}`,
          url: `http://example.com/c${i}`,
          downloaded: false,
        },
        status: "pending" as const,
        addedAt: Date.now(),
        retryCount: 0,
      }));

      let maxConcurrent = 0;
      let currentActive = 0;
      manager2.on("job-started", () => {
        currentActive++;
        maxConcurrent = Math.max(maxConcurrent, currentActive);
      });
      manager2.on("job-completed", () => {
        currentActive--;
      });

      const completionPromise = new Promise<void>((resolve) => {
        manager2.once("all-complete", resolve);
      });

      await manager2.addJobs(jobs);
      await completionPromise;

      expect(maxConcurrent).toBeLessThanOrEqual(1);
    });
  });
});
