import AsyncStorage from "@react-native-async-storage/async-storage";
import { LibraryStorage } from "../libraryStorage";
import { DownloadStatus, Story } from "../../../types";
import * as fileSystem from "../fileSystem";
import { STORAGE_KEYS, storyKey } from "../storageKeys";

jest.mock("../fileSystem");

describe("LibraryStorage", () => {
  let libraryStorage: LibraryStorage;

  const mockStory: Story = {
    id: "test-story-1",
    title: "Test Story",
    author: "Test Author",
    sourceUrl: "http://test.com/story",
    coverUrl: "http://test.com/cover.jpg",
    chapters: [
      {
        id: "c1",
        title: "Chapter 1",
        url: "http://test.com/c1",
        downloaded: true,
        filePath: "file://c1.html",
      },
      {
        id: "c2",
        title: "Chapter 2",
        url: "http://test.com/c2",
        downloaded: false,
      },
    ],
    status: DownloadStatus.Partial,
    totalChapters: 2,
    downloadedChapters: 1,
    dateAdded: 1000,
    lastUpdated: 2000,
    tags: ["fantasy"],
  };

  /** Helper: prime the per-story index + individual story keys */
  const seedPerStoryStorage = (stories: Story[]) => {
    const ids = stories.map((s) => s.id);
    (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
      if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify(ids);
      if (key === STORAGE_KEYS.LIBRARY_LEGACY) return null; // already migrated
      if (key.startsWith("wa_story_")) {
        const storyId = key.slice("wa_story_".length);
        const s = stories.find((st) => st.id === storyId);
        return s ? JSON.stringify(s) : null;
      }
      return null;
    });
  };

  beforeEach(() => {
    jest.clearAllMocks();
    libraryStorage = new LibraryStorage();
    // Default: no legacy data, empty index (already migrated)
    (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
      if (key === STORAGE_KEYS.LIBRARY_INDEX) return null;
      if (key === STORAGE_KEYS.LIBRARY_LEGACY) return null;
      return null;
    });
  });

  // ── Migration ────────────────────────────────────────────────────

  describe("migration from legacy blob", () => {
    it("should migrate wa_library_v1 to per-story keys on first access", async () => {
      const legacy = [mockStory];
      // Simulate AsyncStorage with in-memory backing
      const store = new Map<string, string>();
      store.set(STORAGE_KEYS.LIBRARY_LEGACY, JSON.stringify(legacy));

      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => store.get(key) ?? null);
      (AsyncStorage.setItem as jest.Mock).mockImplementation(async (key: string, value: string) => { store.set(key, value); });
      (AsyncStorage.removeItem as jest.Mock).mockImplementation(async (key: string) => { store.delete(key); });

      const result = await libraryStorage.getLibrary();

      // Should have written the index
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.LIBRARY_INDEX,
        JSON.stringify(["test-story-1"]),
      );
      // Should have written individual story key
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        storyKey("test-story-1"),
        JSON.stringify(mockStory),
      );
      // Should have removed legacy blob
      expect(AsyncStorage.removeItem).toHaveBeenCalledWith(
        STORAGE_KEYS.LIBRARY_LEGACY,
      );
      expect(result).toEqual([mockStory]);
    });

    it("should be a no-op when index already exists", async () => {
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });

      await libraryStorage.getLibrary();

      // Should NOT have tried to migrate legacy data
      expect(AsyncStorage.removeItem).not.toHaveBeenCalled();
    });

    it("should handle empty legacy store gracefully", async () => {
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return null;
        if (key === STORAGE_KEYS.LIBRARY_LEGACY) return null;
        return null;
      });

      const result = await libraryStorage.getLibrary();
      expect(result).toEqual([]);
    });
  });

  // ── getLibrary ────────────────────────────────────────────────────

  describe("getLibrary", () => {
    it("should return parsed library from per-story keys", async () => {
      seedPerStoryStorage([mockStory]);

      const result = await libraryStorage.getLibrary();

      expect(result).toEqual([mockStory]);
    });

    it("should return empty array when no stories exist", async () => {
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });

      const result = await libraryStorage.getLibrary();

      expect(result).toEqual([]);
    });

    it("should skip corrupted individual story entries", async () => {
      const ids = ["good", "bad"];
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify(ids);
        if (key === storyKey("good")) return JSON.stringify({ ...mockStory, id: "good" });
        if (key === storyKey("bad")) return "not-valid-json{{{";
        return null;
      });

      const result = await libraryStorage.getLibrary();

      expect(result).toHaveLength(1);
      expect(result[0].id).toBe("good");
    });
  });

  // ── saveLibrary ────────────────────────────────────────────────────

  describe("saveLibrary", () => {
    it("should save each story to its own key and update the index", async () => {
      const library = [mockStory];
      await libraryStorage.saveLibrary(library);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.LIBRARY_INDEX,
        JSON.stringify(["test-story-1"]),
      );
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        storyKey("test-story-1"),
        JSON.stringify(mockStory),
      );
    });
  });

  // ── addStory ──────────────────────────────────────────────────────

  describe("addStory", () => {
    it("should add new story to library", async () => {
      // Empty index
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });

      await libraryStorage.addStory(mockStory);

      // Should update index
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.LIBRARY_INDEX,
        JSON.stringify(["test-story-1"]),
      );
      // Should write story to its own key
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        storyKey("test-story-1"),
        expect.any(String),
      );
      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.id).toBe("test-story-1");
      expect(savedStory.dateAdded).toBeGreaterThan(0);
    });

    it("should update existing story while preserving dateAdded", async () => {
      const existingStory: Story = {
        ...mockStory,
        dateAdded: 1000,
        title: "Old Title",
      };
      seedPerStoryStorage([existingStory]);

      const updatedStory: Story = {
        ...mockStory,
        title: "New Title",
      };
      await libraryStorage.addStory(updatedStory);

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.title).toBe("New Title");
      expect(savedStory.dateAdded).toBe(1000);
    });

    it("should add story to end of library index", async () => {
      const existingStory: Story = {
        ...mockStory,
        id: "existing-story",
      };
      seedPerStoryStorage([existingStory]);

      const newStory: Story = {
        ...mockStory,
        id: "new-story",
      };
      await libraryStorage.addStory(newStory);

      // Index should have both
      const indexCall = (AsyncStorage.setItem as jest.Mock).mock.calls.find(
        (c: string[]) => c[0] === STORAGE_KEYS.LIBRARY_INDEX,
      );
      expect(JSON.parse(indexCall![1])).toEqual([
        "existing-story",
        "new-story",
      ]);
    });
  });

  // ── getStory ──────────────────────────────────────────────────────

  describe("getStory", () => {
    it("should return story by id", async () => {
      seedPerStoryStorage([mockStory]);

      const result = await libraryStorage.getStory("test-story-1");

      expect(result).toEqual(mockStory);
    });

    it("should return undefined for non-existent story", async () => {
      seedPerStoryStorage([mockStory]);

      const result = await libraryStorage.getStory("non-existent");

      expect(result).toBeUndefined();
    });
  });

  // ── updateStory ───────────────────────────────────────────────────

  describe("updateStory", () => {
    it("should update story via addStory", async () => {
      seedPerStoryStorage([mockStory]);

      const updatedStory: Story = {
        ...mockStory,
        title: "Updated Title",
      };
      await libraryStorage.updateStory(updatedStory);

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.title).toBe("Updated Title");
    });
  });

  // ── deleteStory ───────────────────────────────────────────────────

  describe("deleteStory", () => {
    it("should delete story and its files", async () => {
      seedPerStoryStorage([mockStory]);
      (fileSystem.deleteNovel as jest.Mock).mockResolvedValue(undefined);

      await libraryStorage.deleteStory("test-story-1");

      expect(fileSystem.deleteNovel).toHaveBeenCalledWith("test-story-1");
      // Should update index to empty
      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.LIBRARY_INDEX,
        JSON.stringify([]),
      );
      // Should remove individual story key
      expect(AsyncStorage.removeItem).toHaveBeenCalledWith(
        storyKey("test-story-1"),
      );
    });

    it("should continue deleting from storage even if file deletion fails", async () => {
      seedPerStoryStorage([mockStory]);
      (fileSystem.deleteNovel as jest.Mock).mockRejectedValue(
        new Error("File error"),
      );

      await libraryStorage.deleteStory("test-story-1");

      expect(AsyncStorage.removeItem).toHaveBeenCalledWith(
        storyKey("test-story-1"),
      );
    });
  });

  // ── updateStoryStatus ─────────────────────────────────────────────

  describe("updateStoryStatus", () => {
    it("should update story status", async () => {
      seedPerStoryStorage([mockStory]);

      await libraryStorage.updateStoryStatus(
        "test-story-1",
        DownloadStatus.Completed,
      );

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.status).toBe(DownloadStatus.Completed);
    });

    it("should do nothing if story not found", async () => {
      seedPerStoryStorage([mockStory]);

      await libraryStorage.updateStoryStatus(
        "non-existent",
        DownloadStatus.Completed,
      );

      // Only the migration check reads happen; no writes
      const setCalls = (AsyncStorage.setItem as jest.Mock).mock.calls;
      expect(setCalls.length).toBe(0);
    });
  });

  // ── updateLastRead ────────────────────────────────────────────────

  describe("updateLastRead", () => {
    it("should update last read chapter", async () => {
      seedPerStoryStorage([mockStory]);

      await libraryStorage.updateLastRead("test-story-1", "c2");

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.lastReadChapterId).toBe("c2");
      expect(savedStory.lastUpdated).toBeGreaterThan(0);
    });
  });

  // ── moveStoriesToTab ──────────────────────────────────────────────

  describe("moveStoriesToTab", () => {
    it("should move stories to specified tab", async () => {
      seedPerStoryStorage([mockStory]);

      await libraryStorage.moveStoriesToTab(["test-story-1"], "tab-1");

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.tabId).toBe("tab-1");
    });

    it("should move stories to null tab (unassign)", async () => {
      seedPerStoryStorage([{ ...mockStory, tabId: "old-tab" }]);

      await libraryStorage.moveStoriesToTab(["test-story-1"], null);

      const savedStory = JSON.parse(
        (AsyncStorage.setItem as jest.Mock).mock.calls.find(
          (c: string[]) => c[0] === storyKey("test-story-1"),
        )![1],
      );
      expect(savedStory.tabId).toBeNull();
    });

    it("should handle multiple stories", async () => {
      const story2: Story = { ...mockStory, id: "story-2" };
      seedPerStoryStorage([mockStory, story2]);

      await libraryStorage.moveStoriesToTab(
        ["test-story-1", "story-2"],
        "new-tab",
      );

      const storyWrites = (AsyncStorage.setItem as jest.Mock).mock.calls.filter(
        (c: string[]) => c[0].startsWith("wa_story_"),
      );
      expect(storyWrites).toHaveLength(2);
      expect(JSON.parse(storyWrites[0][1]).tabId).toBe("new-tab");
      expect(JSON.parse(storyWrites[1][1]).tabId).toBe("new-tab");
    });
  });

  // ── createArchivedStorySnapshot ───────────────────────────────────

  describe("createArchivedStorySnapshot", () => {
    it("should create archived copy with file copies", async () => {
      // Empty library for the addStory call inside createArchivedStorySnapshot
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });
      (fileSystem.copyChapterToNovel as jest.Mock).mockResolvedValue(
        "file://archived/c1.html",
      );

      const result = await libraryStorage.createArchivedStorySnapshot(
        mockStory,
        "source_chapters_removed",
      );

      expect(result.id).toMatch(/^test-story-1__archive_\d+_[a-z0-9]+$/);
      expect(result.isArchived).toBe(true);
      expect(result.archiveOfStoryId).toBe("test-story-1");
      expect(result.archiveReason).toBe("source_chapters_removed");
      expect(result.archivedAt).toBeGreaterThan(0);
      expect(fileSystem.copyChapterToNovel).toHaveBeenCalledWith(
        "file://c1.html",
        expect.any(String),
        0,
        "Chapter 1",
      );
    });

    it("should not copy files for non-downloaded chapters", async () => {
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });

      await libraryStorage.createArchivedStorySnapshot(
        mockStory,
        "source_chapters_removed",
      );

      // Only chapter 1 is downloaded, chapter 2 is not
      expect(fileSystem.copyChapterToNovel).toHaveBeenCalledTimes(1);
    });

    it("should clear epub-related fields on archive", async () => {
      const storyWithEpub: Story = {
        ...mockStory,
        epubPath: "file://book.epub",
        epubPaths: ["file://book1.epub", "file://book2.epub"],
        epubStale: true,
        pendingNewChapterIds: ["c3"],
      };
      (AsyncStorage.getItem as jest.Mock).mockImplementation(async (key: string) => {
        if (key === STORAGE_KEYS.LIBRARY_INDEX) return JSON.stringify([]);
        return null;
      });
      (fileSystem.copyChapterToNovel as jest.Mock).mockResolvedValue(
        "file://archived.html",
      );

      const result = await libraryStorage.createArchivedStorySnapshot(
        storyWithEpub,
        "source_chapters_removed",
      );

      expect(result.epubPath).toBeUndefined();
      expect(result.epubPaths).toBeUndefined();
      expect(result.epubStale).toBeUndefined();
      expect(result.pendingNewChapterIds).toBeUndefined();
    });
  });
});
