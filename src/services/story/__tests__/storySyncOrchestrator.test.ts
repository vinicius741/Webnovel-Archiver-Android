import {
  prepareStorySyncData,
  buildStoryForAdd,
  buildStoryForSync,
  buildPendingNewChapterIds,
  UnsupportedSourceError,
  EmptyChapterListError,
  PreparedStorySyncData,
} from "../storySyncOrchestrator";
import { sourceRegistry } from "../../source/SourceRegistry";
import * as fetcher from "../../network/fetcher";
import { mergeChapters } from "../../../utils/mergeChapters";
import { DownloadStatus, Story } from "../../../types";
import { SourceProvider, NovelMetadata } from "../../source/types";

jest.mock("../../source/SourceRegistry");
jest.mock("../../network/fetcher");
jest.mock("../../../utils/mergeChapters");

describe("storySyncOrchestrator", () => {
  const mockProvider: SourceProvider = {
    name: "TestProvider",
    baseUrl: "https://test.com",
    isSource: jest.fn().mockReturnValue(true),
    getStoryId: jest.fn().mockReturnValue("test-story-id"),
    parseMetadata: jest.fn(),
    getChapterList: jest.fn(),
    parseChapterContent: jest.fn(),
    getChapterId: (url: string) => {
      const match = url.match(/\/chapter\/(\d+)/);
      return match ? match[1] : undefined;
    },
  };

  const mockMetadata: NovelMetadata = {
    title: "Test Story",
    author: "Test Author",
    coverUrl: "http://test.com/cover.jpg",
    description: "Test description",
    tags: ["fantasy", "adventure"],
    score: "4.5",
    canonicalUrl: "http://test.com/canonical",
  };

  const mockStory: Story = {
    id: "test-story-id",
    title: "Test Story",
    author: "Test Author",
    sourceUrl: "http://test.com/story",
    coverUrl: "http://test.com/cover.jpg",
    description: "Test description",
    tags: ["fantasy"],
    score: "4.5",
    chapters: [
      {
        id: "c1",
        title: "Chapter 1",
        url: "http://test.com/c1",
        downloaded: true,
        filePath: "file://c1.html",
      },
    ],
    status: DownloadStatus.Partial,
    totalChapters: 1,
    downloadedChapters: 1,
    dateAdded: 1000,
    lastUpdated: 2000,
  };

  beforeEach(() => {
    jest.clearAllMocks();
    (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
    (fetcher.fetchPage as jest.Mock).mockResolvedValue("<html></html>");
    (mockProvider.parseMetadata as jest.Mock).mockReturnValue(mockMetadata);
    (mockProvider.getChapterList as jest.Mock).mockResolvedValue([
      { url: "http://test.com/c1", title: "Chapter 1" },
      { url: "http://test.com/c2", title: "Chapter 2" },
    ]);
    (mergeChapters as jest.Mock).mockReturnValue({
      chapters: [
        { id: "c1", title: "Chapter 1", url: "http://test.com/c1", downloaded: true },
        { id: "c2", title: "Chapter 2", url: "http://test.com/c2", downloaded: false },
      ],
      downloadedCount: 1,
      newChaptersCount: 1,
      newChapterIds: ["c2"],
      removedChapterIds: [],
      removedChapters: [],
      lastReadChapterId: undefined,
      lastReadChapterRemoved: false,
    });
  });

  describe("buildPendingNewChapterIds", () => {
    it("should merge existing pending with new chapter IDs", () => {
      const existingPending = ["c3"];
      const chapterIdsToAdd = ["c4", "c5"];
      const mergedChapters = [
        { id: "c1", downloaded: true },
        { id: "c2", downloaded: true },
        { id: "c3", downloaded: false },
        { id: "c4", downloaded: false },
        { id: "c5", downloaded: false },
      ];

      const result = buildPendingNewChapterIds(
        existingPending,
        chapterIdsToAdd,
        mergedChapters as any,
      );

      expect(result).toEqual(["c3", "c4", "c5"]);
    });

    it("should filter out already downloaded chapters", () => {
      const existingPending = ["c3"];
      const chapterIdsToAdd = ["c4"];
      const mergedChapters = [
        { id: "c1", downloaded: true },
        { id: "c2", downloaded: true },
        { id: "c3", downloaded: true }, // Now downloaded
        { id: "c4", downloaded: false },
      ];

      const result = buildPendingNewChapterIds(
        existingPending,
        chapterIdsToAdd,
        mergedChapters as any,
      );

      expect(result).toEqual(["c4"]);
    });

    it("should return undefined when all chapters are downloaded", () => {
      const existingPending = ["c3"];
      const chapterIdsToAdd = ["c4"];
      const mergedChapters = [
        { id: "c1", downloaded: true },
        { id: "c2", downloaded: true },
        { id: "c3", downloaded: true },
        { id: "c4", downloaded: true },
      ];

      const result = buildPendingNewChapterIds(
        existingPending,
        chapterIdsToAdd,
        mergedChapters as any,
      );

      expect(result).toBeUndefined();
    });

    it("should handle empty existing pending", () => {
      const chapterIdsToAdd = ["c2"];
      const mergedChapters = [
        { id: "c1", downloaded: true },
        { id: "c2", downloaded: false },
      ];

      const result = buildPendingNewChapterIds(
        undefined,
        chapterIdsToAdd,
        mergedChapters as any,
      );

      expect(result).toEqual(["c2"]);
    });
  });

  describe("prepareStorySyncData", () => {
    it("should throw UnsupportedSourceError for unknown URL", async () => {
      (sourceRegistry.getProvider as jest.Mock).mockReturnValue(undefined);

      await expect(
        prepareStorySyncData({ sourceUrl: "http://unknown.com/story" }),
      ).rejects.toThrow(UnsupportedSourceError);
    });

    it("should throw EmptyChapterListError when no chapters returned", async () => {
      (mockProvider.getChapterList as jest.Mock).mockResolvedValue([]);

      await expect(
        prepareStorySyncData({ sourceUrl: "http://test.com/story" }),
      ).rejects.toThrow(EmptyChapterListError);
    });

    it("should prepare sync data for new story", async () => {
      const result = await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
      });

      expect(result.provider).toBe(mockProvider);
      expect(result.storyId).toBe("test-story-id");
      expect(result.canonicalUrl).toBe("http://test.com/canonical");
      expect(result.metadata).toEqual(mockMetadata);
      expect(result.existingStory).toBeUndefined();
      expect(fetcher.fetchPage).toHaveBeenCalledWith("http://test.com/story");
    });

    it("should use existing story when provided", async () => {
      const result = await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
        existingStory: mockStory,
      });

      expect(result.existingStory).toEqual(mockStory);
      expect(result.storyId).toBe("test-story-id");
    });

    it("should load existing story via callback when provided", async () => {
      const loadExistingStory = jest.fn().mockResolvedValue(mockStory);

      const result = await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
        loadExistingStory,
      });

      expect(loadExistingStory).toHaveBeenCalledWith("test-story-id");
      expect(result.existingStory).toEqual(mockStory);
    });

    it("should call status callbacks", async () => {
      const onStatus = jest.fn();
      const onProgress = jest.fn();

      await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
        onStatus,
        onProgress,
      });

      expect(onStatus).toHaveBeenCalledWith("Fetching from TestProvider...");
      expect(onStatus).toHaveBeenCalledWith("Parsing chapters...");
    });

    it("should use canonical URL from metadata", async () => {
      const metadataWithCanonical = {
        ...mockMetadata,
        canonicalUrl: "http://canonical.url/story",
      };
      (mockProvider.parseMetadata as jest.Mock).mockReturnValue(
        metadataWithCanonical,
      );

      const result = await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
      });

      expect(result.canonicalUrl).toBe("http://canonical.url/story");
    });

    it("should use source URL as canonical when metadata has none", async () => {
      const metadataWithoutCanonical = {
        ...mockMetadata,
        canonicalUrl: undefined,
      };
      (mockProvider.parseMetadata as jest.Mock).mockReturnValue(
        metadataWithoutCanonical,
      );

      const result = await prepareStorySyncData({
        sourceUrl: "http://test.com/story",
      });

      expect(result.canonicalUrl).toBe("http://test.com/story");
    });
  });

  describe("buildStoryForAdd", () => {
    it("should build new story from prepared data", () => {
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "new-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: false },
          ],
          downloadedCount: 0,
          newChaptersCount: 1,
          newChapterIds: ["c1"],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForAdd(prepared);

      expect(result.id).toBe("new-story-id");
      expect(result.title).toBe("Test Story");
      expect(result.author).toBe("Test Author");
      expect(result.status).toBe(DownloadStatus.Idle);
      expect(result.totalChapters).toBe(1);
      expect(result.downloadedChapters).toBe(0);
      expect(result.dateAdded).toBeUndefined();
    });

    it("should set status to Partial when existing story has new chapters", () => {
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "existing-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        existingStory: mockStory,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
            { id: "c2", title: "Chapter 2", url: "http://c2", downloaded: false },
          ],
          downloadedCount: 1,
          newChaptersCount: 1,
          newChapterIds: ["c2"],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForAdd(prepared);

      expect(result.status).toBe(DownloadStatus.Partial);
    });

    it("should preserve existing story status when no new chapters", () => {
      const completedStory = {
        ...mockStory,
        status: DownloadStatus.Completed,
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "existing-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        existingStory: completedStory,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
          ],
          downloadedCount: 1,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForAdd(prepared);

      expect(result.status).toBe(DownloadStatus.Completed);
    });

    it("should preserve epub-related fields for existing story", () => {
      const storyWithEpub = {
        ...mockStory,
        epubPath: "file://book.epub",
        epubStale: false,
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "existing-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        existingStory: storyWithEpub,
        mergeResult: {
          chapters: [],
          downloadedCount: 0,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: "c1",
          lastReadChapterRemoved: false,
        },
        pendingNewChapterIds: ["c2"],
      };

      const result = buildStoryForAdd(prepared);

      expect(result.epubPath).toBe("file://book.epub");
      expect(result.epubStale).toBe(false);
      expect(result.lastReadChapterId).toBe("c1");
      expect(result.pendingNewChapterIds).toEqual(["c2"]);
    });
  });

  describe("buildStoryForSync", () => {
    it("should build updated story for sync", () => {
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
            { id: "c2", title: "Chapter 2", url: "http://c2", downloaded: false },
          ],
          downloadedCount: 1,
          newChaptersCount: 1,
          newChapterIds: ["c2"],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: mockStory,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.chapters).toHaveLength(2);
      expect(result.totalChapters).toBe(2);
      expect(result.downloadedChapters).toBe(1);
      expect(result.status).toBe(DownloadStatus.Partial);
      expect(result.lastUpdated).toBeGreaterThan(0);
    });

    it("should preserve current status when no new chapters", () => {
      const completedStory = { ...mockStory, status: DownloadStatus.Completed };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
          ],
          downloadedCount: 1,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: completedStory,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.status).toBe(DownloadStatus.Completed);
    });

    it("should update metadata from source", () => {
      const newMetadata = {
        ...mockMetadata,
        title: "Updated Title",
        tags: ["fantasy", "romance"],
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: newMetadata,
        mergeResult: {
          chapters: [],
          downloadedCount: 0,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: mockStory,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.title).toBe("Updated Title");
      expect(result.tags).toEqual(["fantasy", "romance"]);
    });

    it("should preserve current metadata when source returns empty", () => {
      const emptyMetadata = {
        title: "",
        author: "",
        coverUrl: "",
        description: "",
        tags: [] as string[],
        score: "0",
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: emptyMetadata,
        mergeResult: {
          chapters: [],
          downloadedCount: 0,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: mockStory,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.title).toBe("Test Story");
      expect(result.author).toBe("Test Author");
    });

    it("should mark epub as stale when chapters change and epub exists", () => {
      const storyWithEpub = {
        ...mockStory,
        epubPath: "file://book.epub",
        epubStale: false,
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
            { id: "c2", title: "Chapter 2", url: "http://c2", downloaded: false },
          ],
          downloadedCount: 1,
          newChaptersCount: 1,
          newChapterIds: ["c2"],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: storyWithEpub,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.epubStale).toBe(true);
    });

    it("should not mark epub stale when no epub exists", () => {
      const storyNoEpub = { ...mockStory, epubPath: undefined, epubPaths: undefined };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [
            { id: "c1", title: "Chapter 1", url: "http://c1", downloaded: true },
            { id: "c2", title: "Chapter 2", url: "http://c2", downloaded: false },
          ],
          downloadedCount: 1,
          newChaptersCount: 1,
          newChapterIds: ["c2"],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: storyNoEpub,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.epubStale).toBeFalsy();
    });

    it("should clear archive fields on sync", () => {
      const archivedStory: Story = {
        ...mockStory,
        isArchived: true,
        archiveOfStoryId: "original-id",
        archivedAt: Date.now(),
        archiveReason: "source_chapters_removed",
      };
      const prepared: PreparedStorySyncData = {
        provider: mockProvider,
        storyId: "test-story-id",
        canonicalUrl: "http://test.com/canonical",
        metadata: mockMetadata,
        mergeResult: {
          chapters: [],
          downloadedCount: 0,
          newChaptersCount: 0,
          newChapterIds: [],
          removedChapterIds: [],
          removedChapters: [],
          lastReadChapterId: undefined,
          lastReadChapterRemoved: false,
        },
      };

      const result = buildStoryForSync({
        currentStory: archivedStory,
        prepared,
        updatedEpubConfig: undefined,
      });

      expect(result.isArchived).toBe(false);
      expect(result.archiveOfStoryId).toBeUndefined();
      expect(result.archivedAt).toBeUndefined();
      expect(result.archiveReason).toBeUndefined();
    });
  });
});
