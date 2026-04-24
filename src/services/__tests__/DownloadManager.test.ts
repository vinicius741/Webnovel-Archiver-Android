import { DownloadManager } from "../download/DownloadManager";
import { DownloadJob } from "../download/types";
import { HttpError } from "../network/fetcher";

jest.mock("../download/DownloadQueue", () => ({
  downloadQueue: {
    init: jest.fn().mockResolvedValue(undefined),
    addJob: jest.fn(),
    getNextPending: jest.fn(),
    getAllJobs: jest.fn().mockReturnValue([]),
    updateJobStatus: jest.fn(),
    pausePendingForStory: jest.fn(),
    getStats: jest.fn().mockReturnValue({ pending: 0, active: 0, total: 0 }),
  },
}));

jest.mock("../source/SourceRegistry", () => ({
  sourceRegistry: {
    getProvider: jest.fn(),
    getAllProviders: jest.fn().mockReturnValue([
      { name: "RoyalRoad" },
      { name: "ScribbleHub" },
    ]),
  },
}));

jest.mock("../network/fetcher", () => {
  const actual = jest.requireActual("../network/fetcher");

  return {
    ...actual,
    fetchPage: jest.fn().mockResolvedValue("<html><p>Test content</p></html>"),
  };
});

jest.mock("../storage/fileSystem", () => ({
  saveChapter: jest.fn().mockResolvedValue("/path/to/chapter.txt"),
}));

jest.mock("../StorageService", () => ({
  storageService: {
    getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 3, downloadDelay: 500 }),
    getSourceDownloadSettings: jest.fn().mockResolvedValue({}),
    getStory: jest.fn().mockResolvedValue(null),
    updateStory: jest.fn().mockResolvedValue(undefined),
    getSentenceRemovalList: jest.fn().mockResolvedValue([]),
    getRegexCleanupRules: jest.fn().mockResolvedValue([]),
  },
}));

jest.mock("../ForegroundServiceCoordinator", () => ({
  setDownloadState: jest.fn().mockResolvedValue(undefined),
  clearDownloadState: jest.fn().mockResolvedValue(undefined),
  showDownloadCompletionNotification: jest.fn().mockResolvedValue(undefined),
}));

jest.mock("../../utils/textCleanup", () => ({
  applyDownloadCleanup: jest.fn((content) => content),
}));

describe("DownloadManager", () => {
  let manager: DownloadManager;

  beforeEach(() => {
    jest.clearAllMocks();
    const { storageService } = require("../StorageService");
    storageService.getSettings.mockResolvedValue({ downloadConcurrency: 3, downloadDelay: 500 });
    storageService.getSourceDownloadSettings.mockResolvedValue({});
    manager = new DownloadManager();
  });

  describe("Initialization", () => {
    it("should initialize queue and settings", async () => {
      await manager.init();

      const { downloadQueue } = require("../download/DownloadQueue");
      const { storageService } = require("../StorageService");

      expect(downloadQueue.init).toHaveBeenCalled();
      expect(storageService.getSettings).toHaveBeenCalled();
      expect(storageService.getSourceDownloadSettings).toHaveBeenCalled();
    });
  });

  describe("Adding Jobs", () => {
    it("should add single job to queue", async () => {
      const job: DownloadJob = {
        id: "job1",
        storyId: "story1",
        storyTitle: "Test Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Chapter 1",
          url: "http://example.com/ch1",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      await manager.addJob(job);

      const { downloadQueue } = require("../download/DownloadQueue");
      expect(downloadQueue.addJob).toHaveBeenCalledWith(job);
    });

    it("should add multiple jobs to queue", async () => {
      const jobs: DownloadJob[] = [
        {
          id: "job1",
          storyId: "story1",
          storyTitle: "Test Story",
          chapterIndex: 0,
          chapter: {
            id: "c1",
            title: "Chapter 1",
            url: "http://example.com/ch1",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
        {
          id: "job2",
          storyId: "story1",
          storyTitle: "Test Story",
          chapterIndex: 1,
          chapter: {
            id: "c2",
            title: "Chapter 2",
            url: "http://example.com/ch2",
            downloaded: false,
          },
          status: "pending",
          addedAt: Date.now(),
          retryCount: 0,
        },
      ];

      await manager.addJobs(jobs);

      const { downloadQueue } = require("../download/DownloadQueue");
      expect(downloadQueue.addJob).toHaveBeenCalledTimes(2);
    });

    it("should emit queue-updated event when adding jobs", async () => {
      const job: DownloadJob = {
        id: "job1",
        storyId: "story1",
        storyTitle: "Test Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Chapter 1",
          url: "http://example.com/ch1",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      const eventSpy = jest.fn();
      manager.on("queue-updated", eventSpy);

      await manager.addJob(job);

      expect(eventSpy).toHaveBeenCalled();
    });
  });

  describe("Concurrency Control", () => {
    it("should respect concurrency setting from storage", async () => {
      const { storageService } = require("../StorageService");
      storageService.getSettings.mockResolvedValue({ downloadConcurrency: 5, downloadDelay: 500 });

      await manager.init();

      expect(storageService.getSettings).toHaveBeenCalled();
    });

    it("should default to 3 if setting is invalid", async () => {
      const { storageService } = require("../StorageService");
      storageService.getSettings.mockResolvedValue({ downloadConcurrency: 0, downloadDelay: 0 });

      await manager.init();

      expect(storageService.getSettings).toHaveBeenCalled();
    });
  });

  describe("Event Emission", () => {
    it("should emit events during job lifecycle", () => {
      const startedSpy = jest.fn();
      const completedSpy = jest.fn();
      const failedSpy = jest.fn();
      const queueUpdatedSpy = jest.fn();
      const allCompleteSpy = jest.fn();

      manager.on("job-started", startedSpy);
      manager.on("job-completed", completedSpy);
      manager.on("job-failed", failedSpy);
      manager.on("queue-updated", queueUpdatedSpy);
      manager.on("all-complete", allCompleteSpy);

      expect(startedSpy).toBeDefined();
      expect(completedSpy).toBeDefined();
      expect(failedSpy).toBeDefined();
      expect(queueUpdatedSpy).toBeDefined();
      expect(allCompleteSpy).toBeDefined();
    });
  });

  describe("Executing Downloads", () => {
    it("should use the source provider parser for Scribble Hub chapter content", async () => {
      const { storageService } = require("../StorageService");
      const { sourceRegistry } = require("../source/SourceRegistry");
      const { fetchPage } = require("../network/fetcher");
      const { saveChapter } = require("../storage/fileSystem");

      const provider = {
        parseChapterContent: jest
          .fn()
          .mockReturnValue("<p>This is long enough parsed Scribble Hub chapter content for saving.</p>"),
      };
      storageService.getStory.mockResolvedValue({
        id: "sh_1056226",
        title: "Outrun",
        sourceUrl:
          "https://www.scribblehub.com/series/1056226/outrun--cyberpunk-litrpg/",
        chapters: [],
      });
      sourceRegistry.getProvider.mockReturnValue(provider);
      fetchPage.mockResolvedValue("<html><div id=\"chp_raw\">Raw chapter</div></html>");

      const job: DownloadJob = {
        id: "sh_1056226_0",
        storyId: "sh_1056226",
        storyTitle: "Outrun",
        chapterIndex: 0,
        chapter: {
          id: "sh_2294631",
          title: "Chapter 376",
          url: "https://www.scribblehub.com/read/1056226-outrun/chapter/2294631/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      const filePath = await (manager as any).executeDownload(job);

      expect(sourceRegistry.getProvider).toHaveBeenCalledWith(
        "https://www.scribblehub.com/series/1056226/outrun--cyberpunk-litrpg/",
      );
      expect(fetchPage).toHaveBeenCalledWith(job.chapter.url);
      expect(provider.parseChapterContent).toHaveBeenCalledWith(
        "<html><div id=\"chp_raw\">Raw chapter</div></html>",
      );
      expect(saveChapter).toHaveBeenCalledWith(
        "sh_1056226",
        0,
        "Chapter 376",
        "<p>This is long enough parsed Scribble Hub chapter content for saving.</p>",
      );
      expect(filePath).toBe("/path/to/chapter.txt");
    });

    it("should pause remaining ScribbleHub jobs after repeated 403 failures", async () => {
      const { downloadQueue } = require("../download/DownloadQueue");
      const { storageService } = require("../StorageService");

      storageService.getStory.mockResolvedValue({
        id: "sh_849523",
        title: "Eternal Lotus Flower",
        sourceUrl:
          "https://www.scribblehub.com/series/849523/eternal-lotus-flower/",
        chapters: [],
      });

      const firstJob: DownloadJob = {
        id: "sh_849523_12",
        storyId: "sh_849523",
        storyTitle: "Eternal Lotus Flower",
        chapterIndex: 12,
        chapter: {
          id: "sh_1401215",
          title: "Chapter 13",
          url: "https://www.scribblehub.com/read/849523-story/chapter/1401215/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };
      const secondJob: DownloadJob = {
        ...firstJob,
        id: "sh_849523_13",
        chapterIndex: 13,
        chapter: {
          ...firstJob.chapter,
          id: "sh_1412162",
          title: "Chapter 14",
          url: "https://www.scribblehub.com/read/849523-story/chapter/1412162/",
        },
      };

      await (manager as any).handleRateLimitFailure(
        firstJob,
        new HttpError(403, firstJob.chapter.url),
      );
      expect(downloadQueue.pausePendingForStory).not.toHaveBeenCalled();

      await (manager as any).handleRateLimitFailure(
        secondJob,
        new HttpError(403, secondJob.chapter.url),
      );

      expect(downloadQueue.pausePendingForStory).toHaveBeenCalledWith(
        "sh_849523",
        expect.stringContaining("Paused remaining chapter downloads"),
      );
    });
  });

  describe("Source-Specific Settings", () => {
    it("should return global settings when no source override exists", async () => {
      await manager.init();

      const settings = manager.getSourceSettings("UnknownSource");
      expect(settings.concurrency).toBe(3);
      expect(settings.delay).toBe(500);
    });

    it("should return source override when one exists", async () => {
      const { storageService } = require("../StorageService");
      storageService.getSourceDownloadSettings.mockResolvedValue({
        ScribbleHub: { concurrency: 1, delay: 2000 },
      });

      await manager.init();

      const settings = manager.getSourceSettings("ScribbleHub");
      expect(settings.concurrency).toBe(1);
      expect(settings.delay).toBe(2000);
    });

    it("should fall back to global for non-overridden properties", async () => {
      const { storageService } = require("../StorageService");
      storageService.getSourceDownloadSettings.mockResolvedValue({
        ScribbleHub: { concurrency: 2, delay: 1000 },
      });

      await manager.init();

      const royalRoadSettings = manager.getSourceSettings("RoyalRoad");
      expect(royalRoadSettings.concurrency).toBe(3);
      expect(royalRoadSettings.delay).toBe(500);
    });

    it("should update settings at runtime via updateSettings", async () => {
      await manager.init();

      manager.updateSettings(5, 1000, {
        ScribbleHub: { concurrency: 1, delay: 3000 },
      });

      expect(manager.getSourceSettings("ScribbleHub")).toEqual({
        concurrency: 1,
        delay: 3000,
      });
      expect(manager.getSourceSettings("RoyalRoad")).toEqual({
        concurrency: 5,
        delay: 1000,
      });
    });

    it("should not change concurrency when updateSettings receives 0", async () => {
      await manager.init();
      const before = manager.getSourceSettings("RoyalRoad").concurrency;

      manager.updateSettings(0, 500, {});

      expect(manager.getSourceSettings("RoyalRoad").concurrency).toBe(before);
    });
  });

  describe("pickNextEligibleJob", () => {
    it("should skip jobs whose source has reached concurrency limit", async () => {
      const { downloadQueue } = require("../download/DownloadQueue");
      const { sourceRegistry } = require("../source/SourceRegistry");

      sourceRegistry.getProvider.mockImplementation((url: string) => {
        if (url.includes("scribblehub.com")) {
          return { name: "ScribbleHub" };
        }
        if (url.includes("royalroad.com")) {
          return { name: "RoyalRoad" };
        }
        return undefined;
      });

      manager.updateSettings(3, 0, {
        ScribbleHub: { concurrency: 1, delay: 0 },
      });

      const scribbleJob: DownloadJob = {
        id: "sh_1",
        storyId: "sh_1",
        storyTitle: "SH Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Ch1",
          url: "https://www.scribblehub.com/read/1-story/chapter/1/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      downloadQueue.getAllJobs.mockReturnValue([scribbleJob]);

      // Simulate 1 active worker for ScribbleHub (at limit of 1)
      (manager as any).activeWorkersBySource.set("ScribbleHub", 1);

      const result = (manager as any).pickNextEligibleJob();
      expect(result).toBeUndefined();
    });

    it("should pick a job from a different source when one is at capacity", async () => {
      const { downloadQueue } = require("../download/DownloadQueue");
      const { sourceRegistry } = require("../source/SourceRegistry");

      sourceRegistry.getProvider.mockImplementation((url: string) => {
        if (url.includes("scribblehub.com")) {
          return { name: "ScribbleHub" };
        }
        if (url.includes("royalroad.com")) {
          return { name: "RoyalRoad" };
        }
        return undefined;
      });

      manager.updateSettings(3, 0, {
        ScribbleHub: { concurrency: 1, delay: 0 },
      });

      const scribbleJob: DownloadJob = {
        id: "sh_1",
        storyId: "sh_1",
        storyTitle: "SH Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Ch1",
          url: "https://www.scribblehub.com/read/1-story/chapter/1/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      const rrJob: DownloadJob = {
        id: "rr_1",
        storyId: "rr_1",
        storyTitle: "RR Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Ch1",
          url: "https://www.royalroad.com/fiction/1/test/chapter/1/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      downloadQueue.getAllJobs.mockReturnValue([scribbleJob, rrJob]);

      // ScribbleHub at limit
      (manager as any).activeWorkersBySource.set("ScribbleHub", 1);

      const result = (manager as any).pickNextEligibleJob();
      expect(result).toBeDefined();
      expect(result.id).toBe("rr_1");
    });

    it("should skip jobs whose source is on cooldown", async () => {
      const { downloadQueue } = require("../download/DownloadQueue");
      const { sourceRegistry } = require("../source/SourceRegistry");

      sourceRegistry.getProvider.mockImplementation((url: string) => {
        if (url.includes("scribblehub.com")) {
          return { name: "ScribbleHub" };
        }
        return undefined;
      });

      manager.updateSettings(3, 0, {
        ScribbleHub: { concurrency: 2, delay: 2000 },
      });

      const job: DownloadJob = {
        id: "sh_1",
        storyId: "sh_1",
        storyTitle: "SH Story",
        chapterIndex: 0,
        chapter: {
          id: "c1",
          title: "Ch1",
          url: "https://www.scribblehub.com/read/1-story/chapter/1/",
          downloaded: false,
        },
        status: "pending",
        addedAt: Date.now(),
        retryCount: 0,
      };

      downloadQueue.getAllJobs.mockReturnValue([job]);

      // Cooldown for 2 seconds from now
      (manager as any).nextAllowedJobAtBySource.set(
        "ScribbleHub",
        Date.now() + 2000,
      );

      const result = (manager as any).pickNextEligibleJob();
      expect(result).toBeUndefined();
    });
  });
});
