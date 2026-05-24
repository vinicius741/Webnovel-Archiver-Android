import { cleanupOrphanedJobs } from "../orphanJobCleaner";
import { downloadQueue } from "../../download/DownloadQueue";
import { sourceRegistry } from "../../source/SourceRegistry";
import { storageService } from "../../storage/StorageService";
import { DownloadJob } from "../types";

jest.mock("../../download/DownloadQueue");
jest.mock("../../source/SourceRegistry");
jest.mock("../../storage/StorageService");

const mockDownloadQueue = downloadQueue as jest.Mocked<typeof downloadQueue>;
const mockSourceRegistry = sourceRegistry as jest.Mocked<typeof sourceRegistry>;
const mockStorageService = storageService as jest.Mocked<typeof storageService>;

const makeJob = (
  overrides: Partial<DownloadJob> & { id: string; storyId: string },
): DownloadJob => ({
  storyTitle: "Test Story",
  chapterIndex: 0,
  chapter: {
    id: "ch1",
    title: "Chapter 1",
    url: "https://www.scribblehub.com/read/1-test/chapter/100/",
  },
  status: "pending",
  addedAt: Date.now(),
  retryCount: 0,
  ...overrides,
});

const royalRoadProvider = {
  name: "Royal Road",
  baseUrl: "https://www.royalroad.com",
  isSource: jest.fn().mockReturnValue(true),
  getStoryId: jest.fn(),
  parseMetadata: jest.fn(),
  getChapterList: jest.fn(),
  parseChapterContent: jest.fn(),
};

describe("cleanupOrphanedJobs", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it("should return 0 when no jobs are orphaned", () => {
    mockDownloadQueue.getAllJobs.mockReturnValue([
      makeJob({
        id: "rr_1_0",
        storyId: "rr_1",
        chapter: {
          id: "ch1",
          title: "Chapter 1",
          url: "https://www.royalroad.com/fiction/1/chapter/100/chapter-1",
        },
      }),
    ]);
    mockSourceRegistry.getProvider.mockReturnValue(royalRoadProvider);

    const result = cleanupOrphanedJobs();

    expect(result.cleanedJobCount).toBe(0);
    expect(result.affectedStoryIds).toEqual([]);
    expect(mockDownloadQueue.updateJobStatus).not.toHaveBeenCalled();
  });

  it("should mark orphaned jobs as failed and recover stuck stories", async () => {
    mockDownloadQueue.getAllJobs.mockReturnValue([
      makeJob({ id: "sh_1_0", storyId: "sh_1" }),
    ]);
    mockSourceRegistry.getProvider.mockReturnValue(undefined);
    mockDownloadQueue.getJobsForStory.mockReturnValue([]);
    mockStorageService.getStory.mockResolvedValue({
      id: "sh_1",
      title: "Test Story",
      author: "Author",
      sourceUrl: "https://www.scribblehub.com/series/1-test/",
      status: "downloading",
      totalChapters: 10,
      downloadedChapters: 0,
      chapters: [
        { id: "ch1", title: "Chapter 1", url: "https://www.scribblehub.com/read/1-test/chapter/100/" },
      ],
    });

    const result = cleanupOrphanedJobs();

    expect(result.cleanedJobCount).toBe(1);
    expect(result.affectedStoryIds).toEqual(["sh_1"]);
    expect(mockDownloadQueue.updateJobStatus).toHaveBeenCalledWith(
      "sh_1_0",
      "failed",
      "No matching source provider",
    );

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockStorageService.updateStoryStatus).toHaveBeenCalledWith(
      "sh_1",
      "idle",
    );
  });

  it("should set status to partial when some chapters are already downloaded", async () => {
    mockDownloadQueue.getAllJobs.mockReturnValue([
      makeJob({ id: "sh_1_5", storyId: "sh_1", chapterIndex: 5 }),
    ]);
    mockSourceRegistry.getProvider.mockReturnValue(undefined);
    mockDownloadQueue.getJobsForStory.mockReturnValue([]);
    mockStorageService.getStory.mockResolvedValue({
      id: "sh_1",
      title: "Test Story",
      author: "Author",
      sourceUrl: "https://www.scribblehub.com/series/1-test/",
      status: "downloading",
      totalChapters: 10,
      downloadedChapters: 3,
      chapters: [
        { id: "ch1", title: "Chapter 1", url: "", downloaded: true, filePath: "/path/1" },
        { id: "ch2", title: "Chapter 2", url: "", downloaded: true, filePath: "/path/2" },
        { id: "ch3", title: "Chapter 3", url: "", downloaded: true, filePath: "/path/3" },
        { id: "ch4", title: "Chapter 4", url: "" },
        { id: "ch5", title: "Chapter 5", url: "" },
        { id: "ch6", title: "Chapter 6", url: "" },
      ],
    });

    cleanupOrphanedJobs();

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockStorageService.updateStoryStatus).toHaveBeenCalledWith(
      "sh_1",
      "partial",
    );
  });

  it("should not change story status if story is not in downloading state", async () => {
    mockDownloadQueue.getAllJobs.mockReturnValue([
      makeJob({ id: "sh_1_0", storyId: "sh_1" }),
    ]);
    mockSourceRegistry.getProvider.mockReturnValue(undefined);
    mockDownloadQueue.getJobsForStory.mockReturnValue([]);
    mockStorageService.getStory.mockResolvedValue({
      id: "sh_1",
      title: "Test Story",
      author: "Author",
      sourceUrl: "https://www.scribblehub.com/series/1-test/",
      status: "idle",
      totalChapters: 10,
      downloadedChapters: 0,
      chapters: [],
    });

    cleanupOrphanedJobs();

    await new Promise((resolve) => setTimeout(resolve, 0));

    expect(mockStorageService.updateStoryStatus).not.toHaveBeenCalled();
  });

  it("should skip completed and failed jobs", () => {
    mockDownloadQueue.getAllJobs.mockReturnValue([
      makeJob({ id: "sh_1_0", storyId: "sh_1", status: "completed" }),
      makeJob({ id: "sh_1_1", storyId: "sh_1", status: "failed" }),
    ]);

    const result = cleanupOrphanedJobs();

    expect(result.cleanedJobCount).toBe(0);
    expect(mockDownloadQueue.updateJobStatus).not.toHaveBeenCalled();
  });
});
