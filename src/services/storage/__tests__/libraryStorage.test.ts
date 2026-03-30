import AsyncStorage from "@react-native-async-storage/async-storage";
import { LibraryStorage } from "../libraryStorage";
import { DownloadStatus, Story } from "../../../types";
import * as fileSystem from "../fileSystem";

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

  beforeEach(() => {
    jest.clearAllMocks();
    libraryStorage = new LibraryStorage();
  });

  describe("getLibrary", () => {
    it("should return parsed library from AsyncStorage", async () => {
      const library = [mockStory];
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify(library),
      );

      const result = await libraryStorage.getLibrary();

      expect(AsyncStorage.getItem).toHaveBeenCalledWith("wa_library_v1");
      expect(result).toEqual(library);
    });

    it("should return empty array when no library exists", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await libraryStorage.getLibrary();

      expect(result).toEqual([]);
    });

    it("should return empty array on parse error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue("invalid json");

      const result = await libraryStorage.getLibrary();

      expect(result).toEqual([]);
    });
  });

  describe("saveLibrary", () => {
    it("should save library to AsyncStorage", async () => {
      const library = [mockStory];
      await libraryStorage.saveLibrary(library);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        "wa_library_v1",
        JSON.stringify(library),
      );
    });
  });

  describe("addStory", () => {
    it("should add new story to library", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue("[]");

      await libraryStorage.addStory(mockStory);

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary).toHaveLength(1);
      expect(savedLibrary[0].id).toBe("test-story-1");
      expect(savedLibrary[0].dateAdded).toBeGreaterThan(0);
    });

    it("should update existing story while preserving dateAdded", async () => {
      const existingStory: Story = {
        ...mockStory,
        dateAdded: 1000,
        title: "Old Title",
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([existingStory]),
      );

      const updatedStory: Story = {
        ...mockStory,
        title: "New Title",
      };
      await libraryStorage.addStory(updatedStory);

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary).toHaveLength(1);
      expect(savedLibrary[0].title).toBe("New Title");
      expect(savedLibrary[0].dateAdded).toBe(1000);
    });

    it("should add story to end of library", async () => {
      const existingStory: Story = {
        ...mockStory,
        id: "existing-story",
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([existingStory]),
      );

      const newStory: Story = {
        ...mockStory,
        id: "new-story",
      };
      await libraryStorage.addStory(newStory);

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary).toHaveLength(2);
      expect(savedLibrary[1].id).toBe("new-story");
    });
  });

  describe("getStory", () => {
    it("should return story by id", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      const result = await libraryStorage.getStory("test-story-1");

      expect(result).toEqual(mockStory);
    });

    it("should return undefined for non-existent story", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      const result = await libraryStorage.getStory("non-existent");

      expect(result).toBeUndefined();
    });
  });

  describe("updateStory", () => {
    it("should update story via addStory", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      const updatedStory: Story = {
        ...mockStory,
        title: "Updated Title",
      };
      await libraryStorage.updateStory(updatedStory);

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].title).toBe("Updated Title");
    });
  });

  describe("deleteStory", () => {
    it("should delete story and its files", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );
      (fileSystem.deleteNovel as jest.Mock).mockResolvedValue(undefined);

      await libraryStorage.deleteStory("test-story-1");

      expect(fileSystem.deleteNovel).toHaveBeenCalledWith("test-story-1");
      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary).toHaveLength(0);
    });

    it("should continue deleting from library even if file deletion fails", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );
      (fileSystem.deleteNovel as jest.Mock).mockRejectedValue(
        new Error("File error"),
      );

      await libraryStorage.deleteStory("test-story-1");

      expect(AsyncStorage.setItem).toHaveBeenCalled();
      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary).toHaveLength(0);
    });
  });

  describe("updateStoryStatus", () => {
    it("should update story status", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      await libraryStorage.updateStoryStatus(
        "test-story-1",
        DownloadStatus.Completed,
      );

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].status).toBe(DownloadStatus.Completed);
    });

    it("should do nothing if story not found", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      await libraryStorage.updateStoryStatus(
        "non-existent",
        DownloadStatus.Completed,
      );

      expect(AsyncStorage.setItem).not.toHaveBeenCalled();
    });
  });

  describe("updateLastRead", () => {
    it("should update last read chapter", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      await libraryStorage.updateLastRead("test-story-1", "c2");

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].lastReadChapterId).toBe("c2");
      expect(savedLibrary[0].lastUpdated).toBeGreaterThan(0);
    });
  });

  describe("moveStoriesToTab", () => {
    it("should move stories to specified tab", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory]),
      );

      await libraryStorage.moveStoriesToTab(["test-story-1"], "tab-1");

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].tabId).toBe("tab-1");
    });

    it("should move stories to null tab (unassign)", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([{ ...mockStory, tabId: "old-tab" }]),
      );

      await libraryStorage.moveStoriesToTab(["test-story-1"], null);

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].tabId).toBeNull();
    });

    it("should handle multiple stories", async () => {
      const story2: Story = { ...mockStory, id: "story-2" };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify([mockStory, story2]),
      );

      await libraryStorage.moveStoriesToTab(
        ["test-story-1", "story-2"],
        "new-tab",
      );

      const savedData = (AsyncStorage.setItem as jest.Mock).mock.calls[0][1];
      const savedLibrary = JSON.parse(savedData);
      expect(savedLibrary[0].tabId).toBe("new-tab");
      expect(savedLibrary[1].tabId).toBe("new-tab");
    });
  });

  describe("createArchivedStorySnapshot", () => {
    it("should create archived copy with file copies", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue("[]");
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
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue("[]");

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
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue("[]");
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
