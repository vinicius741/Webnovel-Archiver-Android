import AsyncStorage from "@react-native-async-storage/async-storage";
import { storageService } from "../StorageService";
import * as fileSystem from "../storage/fileSystem";
import { Story, DownloadStatus } from "../../types";

jest.mock("../storage/fileSystem", () => ({
  deleteNovel: jest.fn(),
  clearAllFiles: jest.fn(),
  copyChapterToNovel: jest.fn(),
}));
jest.mock("../download/DownloadQueue", () => ({
  downloadQueue: {
    clearAll: jest.fn(),
  },
}));

describe("StorageService", () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  describe("getLibrary", () => {
    it("should return empty array if storage is null", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
      const result = await storageService.getLibrary();
      expect(result).toEqual([]);
    });

    it("should return parsed library", async () => {
      const mockLibrary = [{ id: "1", title: "Test Story" }];
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify(mockLibrary),
      );
      const result = await storageService.getLibrary();
      expect(result).toEqual(mockLibrary);
    });

    it("should handle errors gracefully", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );
      const result = await storageService.getLibrary();
      expect(result).toEqual([]);
    });
  });

  describe("addStory", () => {
    it("should add a new story", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([]));
      const newStory: Story = {
        id: "1",
        title: "New Story",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [],
        status: DownloadStatus.Idle,
        totalChapters: 0,
        downloadedChapters: 0,
      };

      await storageService.addStory(newStory);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        "wa_library_v1",
        expect.stringContaining(JSON.stringify(newStory.title)),
      );
    });

    it("should update existing story", async () => {
      const existingStory: Story = {
        id: "1",
        title: "Old Title",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [],
        dateAdded: 12345,
        status: DownloadStatus.Idle,
        totalChapters: 0,
        downloadedChapters: 0,
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([existingStory]),
      );

      const updatedStory: Story = { ...existingStory, title: "New Title" };
      await storageService.addStory(updatedStory);

      expect(AsyncStorage.setItem).toHaveBeenCalled();
      const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
      const savedLibrary = JSON.parse(saveCall[1]);
      expect(savedLibrary[0].title).toBe("New Title");
      expect(savedLibrary[0].dateAdded).toBe(12345); // Should preserve dateAdded
    });
  });

  describe("deleteStory", () => {
    it("should delete story from library and file system", async () => {
      const story: Story = {
        id: "1",
        title: "Delete Me",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [],
        status: DownloadStatus.Idle,
        totalChapters: 0,
        downloadedChapters: 0,
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([story]),
      );

      await storageService.deleteStory("1");

      expect(fileSystem.deleteNovel).toHaveBeenCalledWith("1");
      expect(AsyncStorage.setItem).toHaveBeenCalledWith("wa_library_v1", "[]");
    });
  });

  describe("createArchivedStorySnapshot", () => {
    it("should create an archived snapshot with copied chapter files", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([]));
      (fileSystem.copyChapterToNovel as jest.Mock)
        .mockResolvedValueOnce("file://archive/c1.html")
        .mockResolvedValueOnce("file://archive/c2.html");

      const story: Story = {
        id: "rr_1",
        title: "Archive Me",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [
          {
            id: "c1",
            title: "Chapter 1",
            url: "http://test/c1",
            downloaded: true,
            filePath: "file://live/c1.html",
          },
          {
            id: "c2",
            title: "Chapter 2",
            url: "http://test/c2",
            downloaded: true,
            filePath: "file://live/c2.html",
          },
        ],
        status: DownloadStatus.Completed,
        totalChapters: 2,
        downloadedChapters: 2,
        epubPath: "file://existing.epub",
        epubPaths: ["file://existing.epub"],
        pendingNewChapterIds: ["c3"],
        tabId: "tab-1",
      };

      const archivedStory = await storageService.createArchivedStorySnapshot(
        story,
        "source_chapters_removed",
      );

      expect(archivedStory.id).toMatch(/^rr_1__archive_\d+_[a-z0-9]+$/);
      expect(archivedStory.isArchived).toBe(true);
      expect(archivedStory.archiveOfStoryId).toBe("rr_1");
      expect(archivedStory.archiveReason).toBe("source_chapters_removed");
      expect(archivedStory.tabId).toBe("tab-1");
      expect(archivedStory.epubPath).toBeUndefined();
      expect(archivedStory.epubPaths).toBeUndefined();
      expect(archivedStory.pendingNewChapterIds).toBeUndefined();
      expect(archivedStory.chapters[0].filePath).toBe("file://archive/c1.html");
      expect(archivedStory.chapters[1].filePath).toBe("file://archive/c2.html");
      expect(fileSystem.copyChapterToNovel).toHaveBeenCalledTimes(2);
    });
  });

  describe("updateStoryStatus", () => {
    it("should update status", async () => {
      const story: Story = {
        id: "1",
        title: "Status Test",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [],
        status: DownloadStatus.Idle,
        totalChapters: 0,
        downloadedChapters: 0,
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([story]),
      );

      await storageService.updateStoryStatus("1", DownloadStatus.Downloading);

      const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
      const savedLibrary = JSON.parse(saveCall[1]);
      expect(savedLibrary[0].status).toBe(DownloadStatus.Downloading);
    });
  });

  describe("updateLastRead", () => {
    it("should update last read chapter and timestamp", async () => {
      const story: Story = {
        id: "1",
        title: "Read Test",
        author: "Me",
        sourceUrl: "http://test",
        coverUrl: "http://cover",
        chapters: [],
        status: DownloadStatus.Idle,
        totalChapters: 0,
        downloadedChapters: 0,
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([story]),
      );

      await storageService.updateLastRead("1", "chap1");

      const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
      const savedLibrary = JSON.parse(saveCall[1]);
      expect(savedLibrary[0].lastReadChapterId).toBe("chap1");
      expect(savedLibrary[0].lastUpdated).toBeDefined();
    });
  });

  describe("Settings", () => {
    it("should get default settings if none saved", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
      const settings = await storageService.getSettings();
      expect(settings.downloadConcurrency).toBeDefined();
    });

    it("should save settings", async () => {
      const settings = {
        downloadConcurrency: 2,
        downloadDelay: 1000,
        maxChaptersPerEpub: 150,
      };
      await storageService.saveSettings(settings);
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        "wa_settings_v1",
        JSON.stringify(settings),
      );
    });
  });

  describe("Sentence Removal List", () => {
    it("should get default list if none saved", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
      const list = await storageService.getSentenceRemovalList();
      expect(list.length).toBeGreaterThan(0);
    });

    it("should save list", async () => {
      const list = ["remove me"];
      await storageService.saveSentenceRemovalList(list);
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        "wa_sentence_removal_v1",
        JSON.stringify(list),
      );
    });
  });

  describe("Regex Cleanup Rules", () => {
    it("should get default rules when storage is empty", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
      const rules = await storageService.getRegexCleanupRules();
      expect(rules).toEqual([]);
    });

    it("should save sanitized regex rules", async () => {
      const rules = [
        {
          id: "rule-1",
          name: "Remove separators",
          pattern: "(?:[-=]){5,}",
          flags: "gi",
          enabled: true,
          appliesTo: "both" as const,
        },
      ];

      await storageService.saveRegexCleanupRules(rules);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        "wa_regex_cleanup_rules_v1",
        JSON.stringify([
          {
            ...rules[0],
            flags: "gi",
          },
        ]),
      );
    });

    it("should discard invalid rules when loading", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([
          {
            id: "invalid",
            name: "",
            pattern: "(?:[-=]){5,}",
            flags: "im",
            enabled: true,
            appliesTo: "both",
          },
          {
            id: "valid",
            name: "Valid rule",
            pattern: "(?:[-=]){5,}",
            flags: "im",
            enabled: true,
            appliesTo: "both",
          },
        ]),
      );

      const rules = await storageService.getRegexCleanupRules();
      expect(rules).toHaveLength(1);
      expect(rules[0].id).toBe("valid");
    });

    it("should report rejected rules through diagnostics API", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([
          {
            id: "valid",
            name: "Valid rule",
            pattern: "(?:[-=]){5,}",
            flags: "im",
            enabled: true,
            appliesTo: "both",
          },
          {
            id: "invalid",
            name: "Unsafe",
            pattern: "(.+)+",
            flags: "",
            enabled: true,
            appliesTo: "both",
          },
        ]),
      );

      const result = await storageService.getRegexCleanupRulesWithDiagnostics();
      expect(result.rules).toHaveLength(1);
      expect(result.rejected).toHaveLength(1);
      expect(result.rejected[0].id).toBe("invalid");
    });
  });

  describe("clearAll", () => {
    it("should clear storage and files", async () => {
      await storageService.clearAll();
      const { downloadQueue } = require("../download/DownloadQueue");
      expect(downloadQueue.clearAll).toHaveBeenCalled();
      expect(AsyncStorage.clear).toHaveBeenCalled();
      expect(fileSystem.clearAllFiles).toHaveBeenCalled();
    });
  });
});
