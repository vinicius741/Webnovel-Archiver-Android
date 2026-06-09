import { backupService } from "../BackupService";
import { storageService } from "../StorageService";
import * as DocumentPicker from "expo-document-picker";
import { File } from "expo-file-system";
import * as Sharing from "expo-sharing";
import JSZip from "jszip";
import { Story, DownloadStatus } from "../../../types";
import { readChapterFile, saveChapter } from "../fileSystem";

// Mock dependencies
jest.mock("expo-document-picker");
jest.mock("expo-sharing");
jest.mock("../StorageService");
jest.mock("../fileSystem");

// Define mock factory for expo-file-system
jest.mock("expo-file-system", () => {
  const Directory = jest.fn().mockImplementation(() => ({
    exists: true,
    create: jest.fn(),
    uri: "file://backups",
  }));

  return {
    Directory,
    File: jest.fn(),
    Paths: { cache: "cache://", document: "document://" },
  };
});

describe("BackupService", () => {
  const mockStory: Story = {
    id: "1",
    title: "Test Story",
    author: "Author",
    sourceUrl: "http://test",
    coverUrl: "http://cover",
    chapters: [
      {
        id: "c1",
        title: "Chapter 1",
        url: "http://c1",
        downloaded: true,
        filePath: "path/to/c1",
      },
    ],
    status: DownloadStatus.Completed,
    totalChapters: 1,
    downloadedChapters: 1,
    dateAdded: 1000,
    lastUpdated: 2000,
    lastReadChapterId: "c1",
    epubPath: "path/to/epub",
  };

  // Helper to setup File mock for a test
  const setupFileMock = (instanceOverrides: any = {}) => {
    const defaultInstance = {
      exists: false,
      create: jest.fn(),
      write: jest.fn(),
      text: jest.fn().mockResolvedValue(""),
      uri: "file://test",
      ...instanceOverrides,
    };
    (File as unknown as jest.Mock).mockImplementation(() => defaultInstance);
    return defaultInstance;
  };

  beforeEach(() => {
    jest.clearAllMocks();
    (storageService.getTabs as jest.Mock).mockResolvedValue([]);
    (storageService.getSettings as jest.Mock).mockResolvedValue({
      downloadConcurrency: 1,
      downloadDelay: 500,
      maxChaptersPerEpub: 150,
    });
    (storageService.getSentenceRemovalList as jest.Mock).mockResolvedValue([]);
    (storageService.getRegexCleanupRules as jest.Mock).mockResolvedValue([]);
    (storageService.getTTSSettings as jest.Mock).mockResolvedValue({
      pitch: 1,
      rate: 1,
      chunkSize: 500,
    });
    (storageService.getTTSSession as jest.Mock).mockResolvedValue(null);
    (storageService.getChapterFilterSettings as jest.Mock).mockResolvedValue({
      filterMode: "all",
    });
    (storageService.getFoldLayoutMode as jest.Mock).mockResolvedValue("auto");
    (storageService.getSourceDownloadSettings as jest.Mock).mockResolvedValue(
      {},
    );
  });

  describe("exportBackup", () => {
    it("should return error if library is empty", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([]);

      const result = await backupService.exportBackup();

      expect(result.success).toBe(false);
      expect(result.message).toBe("Your library is empty");
    });

    it("should successfully export backup", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
      (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(true);

      const mockFile = setupFileMock({ exists: false });

      const result = await backupService.exportBackup();

      expect(result.success).toBe(true);
      expect(storageService.getLibrary).toHaveBeenCalled();
      expect(mockFile.create).toHaveBeenCalled();
      expect(mockFile.write).toHaveBeenCalled();

      // Verify sensitive/local paths are cleared
      const writeArg = JSON.parse(mockFile.write.mock.calls[0][0]);
      expect(writeArg.library[0].epubPath).toBeUndefined();
      expect(writeArg.library[0].chapters[0].filePath).toBeUndefined();
      expect(writeArg.library[0].chapters[0].downloaded).toBe(false);
      expect(writeArg.tabs).toEqual([]);

      expect(Sharing.shareAsync).toHaveBeenCalledWith(
        "file://test",
        expect.anything(),
      );
    });

    it("should include tabs in JSON backup", async () => {
      const tabs = [{ id: "tab-1", name: "Reading", order: 0, createdAt: 1 }];
      (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
      (storageService.getTabs as jest.Mock).mockResolvedValue(tabs);
      (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(true);

      const mockFile = setupFileMock({ exists: false });

      const result = await backupService.exportBackup();

      expect(result.success).toBe(true);
      const writeArg = JSON.parse(mockFile.write.mock.calls[0][0]);
      expect(writeArg.version).toBe(2);
      expect(writeArg.tabs).toEqual(tabs);
    });

    it("should return error if sharing is not available", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
      (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(false);
      setupFileMock({ exists: false });

      const result = await backupService.exportBackup();

      expect(result.success).toBe(false);
      expect(result.message).toContain("Sharing is not available");
    });
  });

  describe("importBackup", () => {
    it("should handle user cancellation", async () => {
      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: true,
      });

      const result = await backupService.importBackup();

      expect(result.success).toBe(false);
      expect(result.message).toBe("No file selected");
    });

    it("should return error if file not found", async () => {
      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://test.json" }],
      });
      setupFileMock({ exists: false });

      const result = await backupService.importBackup();

      expect(result.success).toBe(false);
      expect(result.message).toBe("File not found");
    });

    it("should handle invalid JSON", async () => {
      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://test.json" }],
      });
      setupFileMock({
        exists: true,
        text: jest.fn().mockResolvedValue("invalid json"),
      });

      const result = await backupService.importBackup();

      expect(result.success).toBe(false);
      expect(result.message).toContain("not valid JSON");
    });

    it("should validate backup version and schema", async () => {
      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://test.json" }],
      });

      // Missing version
      setupFileMock({
        exists: true,
        text: jest.fn().mockResolvedValue(JSON.stringify({ library: [] })),
      });
      let result = await backupService.importBackup();
      expect(result.success).toBe(false);
      expect(result.message).toContain("missing version");

      // Missing library
      setupFileMock({
        exists: true,
        text: jest.fn().mockResolvedValue(JSON.stringify({ version: 1 })),
      });
      result = await backupService.importBackup();
      expect(result.success).toBe(false);
      expect(result.message).toContain("missing library");
    });

    it("should successfully import and merge library", async () => {
      const backupLibrary = [
        { ...mockStory, id: "1", title: "Updated Title" }, // Update existing
        { ...mockStory, id: "2", title: "New Story" }, // New story
      ];

      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://test.json" }],
      });

      setupFileMock({
        exists: true,
        text: jest.fn().mockResolvedValue(
          JSON.stringify({
            version: 1,
            library: backupLibrary,
          }),
        ),
      });

      // Existing library has only story 1
      const existingLibrary = [mockStory];
      (storageService.getLibrary as jest.Mock).mockResolvedValue(
        existingLibrary,
      );

      const result = await backupService.importBackup();

      expect(result.success).toBe(true);
      expect(result.stats).toEqual({ added: 1, updated: 1 });

      expect(storageService.saveLibrary).toHaveBeenCalled();
      const savedLibrary = (storageService.saveLibrary as jest.Mock).mock
        .calls[0][0];
      expect(savedLibrary.length).toBe(2);
      expect(savedLibrary.find((s: Story) => s.id === "1").title).toBe(
        "Updated Title",
      );
      expect(
        savedLibrary.find((s: Story) => s.id === "1").downloadedChapters,
      ).toBe(mockStory.downloadedChapters);
    });

    it("should merge tabs from JSON backup", async () => {
      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://test.json" }],
      });

      setupFileMock({
        exists: true,
        text: jest.fn().mockResolvedValue(
          JSON.stringify({
            version: 2,
            library: [mockStory],
            tabs: [
              { id: "tab-1", name: "Reading", order: 0, createdAt: 1 },
              { id: "tab-2", name: "Done", order: 1, createdAt: 2 },
            ],
          }),
        ),
      });

      (storageService.getLibrary as jest.Mock).mockResolvedValue([]);
      (storageService.getTabs as jest.Mock).mockResolvedValue([
        { id: "tab-1", name: "Existing", order: 0, createdAt: 1 },
      ]);

      const result = await backupService.importBackup();

      expect(result.success).toBe(true);
      expect(storageService.saveTabs).toHaveBeenCalledWith([
        { id: "tab-1", name: "Existing", order: 0, createdAt: 1 },
        { id: "tab-2", name: "Done", order: 1, createdAt: 2 },
      ]);
    });
  });

  describe("full backup", () => {
    it("should create a local ZIP backup with manifest and chapter files", async () => {
      (storageService.getLibrary as jest.Mock).mockResolvedValue([mockStory]);
      (readChapterFile as jest.Mock).mockResolvedValue("<p>Chapter</p>");

      const mockFile = setupFileMock({ exists: false });

      const result = await backupService.exportFullBackup();

      expect(result.success).toBe(true);
      expect(result.uri).toBe("file://test");
      expect(result.filename).toMatch(/^webnovel_full_backup_.*\.zip$/);
      expect(mockFile.write).toHaveBeenCalledWith(expect.any(Uint8Array));
      expect(Sharing.shareAsync).not.toHaveBeenCalled();

      const zip = await JSZip.loadAsync(mockFile.write.mock.calls[0][0]);
      const manifest = JSON.parse(
        await zip.file("manifest.json")!.async("string"),
      );
      expect(manifest.format).toBe("webnovel-archiver-full-backup");
      expect(manifest.library[0].chapters[0].filePath).toBeUndefined();
      expect(manifest.config.tabs).toEqual([]);
      expect(manifest.chapterFiles).toHaveLength(1);
      expect(await zip.file(manifest.chapterFiles[0].path)!.async("string")).toBe(
        "<p>Chapter</p>",
      );
    });

    it("should share a created full backup on request", async () => {
      (Sharing.isAvailableAsync as jest.Mock).mockResolvedValue(true);

      const result = await backupService.shareFullBackup("file://backup.zip");

      expect(result.success).toBe(true);
      expect(Sharing.shareAsync).toHaveBeenCalledWith("file://backup.zip", {
        mimeType: "application/zip",
        dialogTitle: "Share Full Backup",
        UTI: "public.zip-archive",
      });
    });

    it("should restore a full ZIP backup and downloaded chapter files", async () => {
      const zip = new JSZip();
      const manifest = {
        format: "webnovel-archiver-full-backup",
        version: 1,
        exportDate: "2026-06-09T00:00:00.000Z",
        library: [
          {
            ...mockStory,
            downloadedChapters: 0,
            chapters: [
              {
                ...mockStory.chapters[0],
                downloaded: false,
                filePath: undefined,
              },
            ],
          },
        ],
        config: {
          settings: {
            downloadConcurrency: 2,
            downloadDelay: 100,
            maxChaptersPerEpub: 150,
          },
          sentenceRemovalList: ["remove me"],
          regexCleanupRules: [],
          ttsSettings: { pitch: 1, rate: 1, chunkSize: 500 },
          ttsSession: null,
          chapterFilterSettings: { filterMode: "all" },
          tabs: [{ id: "tab-1", name: "Reading", order: 0, createdAt: 1 }],
          foldLayoutMode: "auto",
          sourceDownloadSettings: {},
          themeStorage: {},
        },
        chapterFiles: [
          {
            storyId: "1",
            chapterId: "c1",
            chapterIndex: 0,
            title: "Chapter 1",
            path: "novels/1/0000_c1.html",
          },
        ],
      };
      zip.file("manifest.json", JSON.stringify(manifest));
      zip.file("novels/1/0000_c1.html", "<p>Restored</p>");
      const zipBytes = await zip.generateAsync({ type: "uint8array" });

      (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({
        canceled: false,
        assets: [{ uri: "file://full.zip" }],
      });
      setupFileMock({
        exists: true,
        bytes: jest.fn().mockResolvedValue(zipBytes),
      });
      (saveChapter as jest.Mock).mockResolvedValue("file://restored.html");

      const result = await backupService.importFullBackup();

      expect(result.success).toBe(true);
      expect(storageService.clearAll).toHaveBeenCalled();
      expect(storageService.saveTabs).toHaveBeenCalledWith(
        manifest.config.tabs,
      );
      expect(saveChapter).toHaveBeenCalledWith(
        "1",
        0,
        "Chapter 1",
        "<p>Restored</p>",
      );
      expect(storageService.saveLibrary).toHaveBeenCalledWith([
        {
          ...manifest.library[0],
          downloadedChapters: 1,
          epubPath: undefined,
          epubPaths: undefined,
          chapters: [
            {
              ...manifest.library[0].chapters[0],
              downloaded: true,
              filePath: "file://restored.html",
            },
          ],
        },
      ]);
    });
  });
});
