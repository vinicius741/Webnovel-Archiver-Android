import AsyncStorage from "@react-native-async-storage/async-storage";
import * as DocumentPicker from "expo-document-picker";
import { Directory, File, Paths } from "expo-file-system";
import * as Sharing from "expo-sharing";
import JSZip from "jszip";
import { storageService } from "./StorageService";
import { readChapterFile, saveChapter } from "./fileSystem";
import { STORAGE_KEYS } from "./storageKeys";
import {
  AppSettings,
  ChapterFilterSettings,
  FoldLayoutMode,
  RegexCleanupRule,
  SourceDownloadSettingsMap,
  Story,
  TTSSettings,
  TTSSession,
} from "../../types";
import { Tab } from "../../types/tab";

export interface BackupData {
  version: number;
  exportDate: string;
  library: Story[];
  tabs?: Tab[];
}

export interface FullBackupConfig {
  settings: AppSettings;
  sentenceRemovalList: string[];
  regexCleanupRules: RegexCleanupRule[];
  ttsSettings: TTSSettings;
  ttsSession: TTSSession | null;
  chapterFilterSettings: ChapterFilterSettings;
  tabs: Tab[];
  foldLayoutMode: FoldLayoutMode;
  sourceDownloadSettings: SourceDownloadSettingsMap;
  themeStorage: Record<string, string>;
}

interface FullBackupChapterFile {
  storyId: string;
  chapterId: string;
  chapterIndex: number;
  title: string;
  path: string;
}

interface FullBackupManifest {
  format: "webnovel-archiver-full-backup";
  version: number;
  exportDate: string;
  library: Story[];
  config: FullBackupConfig;
  chapterFiles: FullBackupChapterFile[];
}

export interface FullBackupExportResult {
  success: boolean;
  message: string;
  uri?: string;
  filename?: string;
  chapterCount?: number;
}

const BACKUP_VERSION = 2;
const FULL_BACKUP_VERSION = 1;
const FULL_BACKUP_FORMAT = "webnovel-archiver-full-backup";
const FULL_BACKUP_DIR_NAME = "backups";
const THEME_STORAGE_KEYS = [
  STORAGE_KEYS.THEME_DARK_VARIANT,
  STORAGE_KEYS.THEME_LIGHT_VARIANT,
  STORAGE_KEYS.THEME_FOLLOW_SYSTEM,
  STORAGE_KEYS.THEME_MODE,
  STORAGE_KEYS.THEME_ACTIVE,
];

class BackupService {
  async exportBackup(): Promise<{ success: boolean; message: string }> {
    try {
      const [library, tabs] = await Promise.all([
        storageService.getLibrary(),
        storageService.getTabs(),
      ]);

      if (library.length === 0) {
        return { success: false, message: "Your library is empty" };
      }

      const backupData: BackupData = {
        version: BACKUP_VERSION,
        exportDate: new Date().toISOString(),
        library: library.map((story) => ({
          ...story,
          content: undefined,
          filePath: undefined,
          epubPath: undefined,
          chapters: story.chapters.map((chapter) => ({
            ...chapter,
            content: undefined,
            filePath: undefined,
            downloaded: false,
          })),
        })),
        tabs,
      };

      const json = JSON.stringify(backupData, null, 2);

      const jsonSizeInMB = json.length / (1024 * 1024);
      if (jsonSizeInMB > 50) {
        return {
          success: false,
          message: `Backup is too large (${jsonSizeInMB.toFixed(1)} MB). Consider exporting fewer novels.`,
        };
      }

      const timestamp = new Date()
        .toISOString()
        .replace(/[:.]/g, "-")
        .slice(0, -5);
      const filename = `webnovel_backup_${timestamp}.json`;

      const file = new File(Paths.cache, filename);
      if (!file.exists) {
        file.create();
      }
      file.write(json);

      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(file.uri, {
          mimeType: "application/json",
          dialogTitle: "Export Backup",
          UTI: "public.json",
        });
        return { success: true, message: "Backup exported successfully" };
      } else {
        return {
          success: false,
          message: "Sharing is not available on this device",
        };
      }
    } catch (error) {
      console.error("Backup export failed", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error";
      return {
        success: false,
        message: `Failed to export backup: ${errorMessage}`,
      };
    }
  }

  async importBackup(): Promise<{
    success: boolean;
    message: string;
    stats?: { added: number; updated: number };
  }> {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: "application/json",
        copyToCacheDirectory: true,
      });

      if (result.canceled || !result.assets || result.assets.length === 0) {
        return { success: false, message: "No file selected" };
      }

      const fileUri = result.assets[0].uri;

      const file = new File(fileUri);
      if (!file.exists) {
        return { success: false, message: "File not found" };
      }

      const content = await file.text();

      let backupData: BackupData;
      try {
        backupData = JSON.parse(content);
      } catch {
        return {
          success: false,
          message: "Invalid backup file: not valid JSON",
        };
      }

      if (!backupData.version) {
        return {
          success: false,
          message: "Invalid backup file: missing version",
        };
      }

      if (!Array.isArray(backupData.library)) {
        return {
          success: false,
          message: "Invalid backup file: missing library",
        };
      }

      if (
        !backupData.library.every(
          (story) => story && typeof story.id === "string",
        )
      ) {
        return {
          success: false,
          message: "Invalid backup file: malformed story data",
        };
      }

      const existingLibrary = await storageService.getLibrary();

      let addedCount = 0;
      let updatedCount = 0;

      backupData.library.forEach((story) => {
        const existingIndex = existingLibrary.findIndex(
          (s) => s.id === story.id,
        );
        if (existingIndex !== -1) {
          const existing = existingLibrary[existingIndex];
          existingLibrary[existingIndex] = {
            ...story,
            downloadedChapters: existing.downloadedChapters,
            lastUpdated: existing.lastUpdated,
            lastReadChapterId: existing.lastReadChapterId,
            dateAdded: existing.dateAdded,
          };
          updatedCount++;
        } else {
          existingLibrary.push(story);
          addedCount++;
        }
      });

      await storageService.saveLibrary(existingLibrary);

      // Import tabs if present in backup (version 2+)
      if (backupData.tabs && Array.isArray(backupData.tabs)) {
        const existingTabs = await storageService.getTabs();
        // Merge tabs: add new tabs, don't overwrite existing ones with same ID
        const existingTabIds = new Set(existingTabs.map((t) => t.id));
        const newTabs = backupData.tabs.filter((t) => !existingTabIds.has(t.id));
        if (newTabs.length > 0) {
          await storageService.saveTabs([...existingTabs, ...newTabs]);
        }
      }

      return {
        success: true,
        message: `Imported ${addedCount + updatedCount} novels (${addedCount} new, ${updatedCount} updated)`,
        stats: { added: addedCount, updated: updatedCount },
      };
    } catch (error) {
      console.error("Backup import failed", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error";
      return {
        success: false,
        message: `Failed to import backup: ${errorMessage}`,
      };
    }
  }

  async exportFullBackup(): Promise<FullBackupExportResult> {
    try {
      const [library, config] = await Promise.all([
        storageService.getLibrary(),
        this.collectFullBackupConfig(),
      ]);

      if (library.length === 0) {
        return { success: false, message: "Your library is empty" };
      }

      const zip = new JSZip();
      const chapterFiles: FullBackupChapterFile[] = [];
      const libraryForManifest = library.map((story) => ({
        ...story,
        content: undefined,
        epubPath: undefined,
        epubPaths: undefined,
        chapters: story.chapters.map((chapter) => ({
          ...chapter,
          content: undefined,
          filePath: undefined,
        })),
      }));

      await Promise.all(
        library.map(async (story) => {
          await Promise.all(
            story.chapters.map(async (chapter, chapterIndex) => {
              if (!chapter.filePath || !chapter.downloaded) return;

              const content = await readChapterFile(chapter.filePath);
              if (!content) return;

              const path = `novels/${encodeURIComponent(story.id)}/${chapterIndex
                .toString()
                .padStart(4, "0")}_${encodeURIComponent(chapter.id)}.html`;
              zip.file(path, content);
              chapterFiles.push({
                storyId: story.id,
                chapterId: chapter.id,
                chapterIndex,
                title: chapter.title,
                path,
              });
            }),
          );
        }),
      );

      const manifest: FullBackupManifest = {
        format: FULL_BACKUP_FORMAT,
        version: FULL_BACKUP_VERSION,
        exportDate: new Date().toISOString(),
        library: libraryForManifest,
        config,
        chapterFiles,
      };

      zip.file("manifest.json", JSON.stringify(manifest, null, 2));

      const bytes = await zip.generateAsync({ type: "uint8array" });
      const timestamp = new Date()
        .toISOString()
        .replace(/[:.]/g, "-")
        .slice(0, -5);
      const filename = `webnovel_full_backup_${timestamp}.zip`;
      const backupDir = this.ensureFullBackupDirExists();
      const file = new File(backupDir, filename);
      if (!file.exists) {
        file.create();
      }
      file.write(bytes);

      return {
        success: true,
        message: `Full backup created successfully (${chapterFiles.length} downloaded chapters included)`,
        uri: file.uri,
        filename,
        chapterCount: chapterFiles.length,
      };
    } catch (error) {
      console.error("Full backup export failed", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error";
      return {
        success: false,
        message: `Failed to create full backup: ${errorMessage}`,
      };
    }
  }

  async shareFullBackup(
    uri: string,
  ): Promise<{ success: boolean; message: string }> {
    try {
      if (await Sharing.isAvailableAsync()) {
        await Sharing.shareAsync(uri, {
          mimeType: "application/zip",
          dialogTitle: "Share Full Backup",
          UTI: "public.zip-archive",
        });
        return {
          success: true,
          message: "Full backup shared successfully",
        };
      }

      return {
        success: false,
        message: "Sharing is not available on this device",
      };
    } catch (error) {
      console.error("Full backup share failed", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error";
      return {
        success: false,
        message: `Failed to share full backup: ${errorMessage}`,
      };
    }
  }

  async importFullBackup(): Promise<{
    success: boolean;
    message: string;
    stats?: { restored: number; chapters: number };
  }> {
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ["application/zip", "application/x-zip-compressed"],
        copyToCacheDirectory: true,
      });

      if (result.canceled || !result.assets || result.assets.length === 0) {
        return { success: false, message: "No file selected" };
      }

      const file = new File(result.assets[0].uri);
      if (!file.exists) {
        return { success: false, message: "File not found" };
      }

      const zip = await JSZip.loadAsync(await file.bytes());
      const manifestFile = zip.file("manifest.json");
      if (!manifestFile) {
        return {
          success: false,
          message: "Invalid full backup: missing manifest",
        };
      }

      const manifest = JSON.parse(
        await manifestFile.async("string"),
      ) as FullBackupManifest;
      const validationError = this.validateFullBackupManifest(manifest);
      if (validationError) {
        return { success: false, message: validationError };
      }

      await storageService.clearAll();
      await this.restoreFullBackupConfig(manifest.config);

      const restoredLibrary: Story[] = manifest.library.map((story) => ({
        ...story,
        chapters: story.chapters.map((chapter) => ({
          ...chapter,
          filePath: undefined,
          downloaded: false,
        })),
        downloadedChapters: 0,
        epubPath: undefined,
        epubPaths: undefined,
      }));

      let restoredChapterCount = 0;
      for (const chapterFile of manifest.chapterFiles) {
        const story = restoredLibrary.find(
          (item) => item.id === chapterFile.storyId,
        );
        const chapter = story?.chapters.find(
          (item) => item.id === chapterFile.chapterId,
        );
        const zipEntry = zip.file(chapterFile.path);
        if (!story || !chapter || !zipEntry) continue;

        const content = await zipEntry.async("string");
        const filePath = await saveChapter(
          story.id,
          chapterFile.chapterIndex,
          chapterFile.title || chapter.title,
          content,
        );
        chapter.filePath = filePath;
        chapter.downloaded = true;
        restoredChapterCount++;
      }

      restoredLibrary.forEach((story) => {
        story.downloadedChapters = story.chapters.filter(
          (chapter) => chapter.downloaded,
        ).length;
      });

      await storageService.saveLibrary(restoredLibrary);

      return {
        success: true,
        message: `Restored ${restoredLibrary.length} novels and ${restoredChapterCount} downloaded chapters`,
        stats: {
          restored: restoredLibrary.length,
          chapters: restoredChapterCount,
        },
      };
    } catch (error) {
      console.error("Full backup import failed", error);
      const errorMessage =
        error instanceof Error ? error.message : "Unknown error";
      return {
        success: false,
        message: `Failed to import full backup: ${errorMessage}`,
      };
    }
  }

  private async collectFullBackupConfig(): Promise<FullBackupConfig> {
    const [
      settings,
      sentenceRemovalList,
      regexCleanupRules,
      ttsSettings,
      ttsSession,
      chapterFilterSettings,
      tabs,
      foldLayoutMode,
      sourceDownloadSettings,
      themeStorage,
    ] = await Promise.all([
      storageService.getSettings(),
      storageService.getSentenceRemovalList(),
      storageService.getRegexCleanupRules(),
      storageService.getTTSSettings(),
      storageService.getTTSSession(),
      storageService.getChapterFilterSettings(),
      storageService.getTabs(),
      storageService.getFoldLayoutMode(),
      storageService.getSourceDownloadSettings(),
      this.getThemeStorage(),
    ]);

    return {
      settings,
      sentenceRemovalList,
      regexCleanupRules,
      ttsSettings,
      ttsSession,
      chapterFilterSettings,
      tabs,
      foldLayoutMode,
      sourceDownloadSettings,
      themeStorage,
    };
  }

  private ensureFullBackupDirExists(): Directory {
    const backupDir = new Directory(Paths.document, FULL_BACKUP_DIR_NAME);
    if (!backupDir.exists) {
      backupDir.create();
    }
    return backupDir;
  }

  private async getThemeStorage(): Promise<Record<string, string>> {
    const entries = await Promise.all(
      THEME_STORAGE_KEYS.map(
        async (key) => [key, await AsyncStorage.getItem(key)] as const,
      ),
    );
    return entries.reduce<Record<string, string>>((acc, [key, value]) => {
      if (value !== null) {
        acc[key] = value;
      }
      return acc;
    }, {});
  }

  private async restoreFullBackupConfig(config: FullBackupConfig): Promise<void> {
    const themeEntries = Object.entries(config.themeStorage ?? {});
    await Promise.all([
      storageService.saveSettings(config.settings),
      storageService.saveSentenceRemovalList(config.sentenceRemovalList),
      storageService.saveRegexCleanupRules(config.regexCleanupRules),
      storageService.saveTTSSettings(config.ttsSettings),
      config.ttsSession
        ? storageService.saveTTSSession(config.ttsSession)
        : storageService.clearTTSSession(),
      storageService.saveChapterFilterSettings(config.chapterFilterSettings),
      storageService.saveTabs(config.tabs),
      storageService.saveFoldLayoutMode(config.foldLayoutMode),
      storageService.saveSourceDownloadSettings(config.sourceDownloadSettings),
      themeEntries.length > 0
        ? AsyncStorage.multiSet(themeEntries)
        : Promise.resolve(),
    ]);
  }

  private validateFullBackupManifest(manifest: FullBackupManifest): string | null {
    if (!manifest || manifest.format !== FULL_BACKUP_FORMAT) {
      return "Invalid full backup: unsupported format";
    }
    if (!manifest.version) {
      return "Invalid full backup: missing version";
    }
    if (!Array.isArray(manifest.library)) {
      return "Invalid full backup: missing library";
    }
    if (!Array.isArray(manifest.chapterFiles)) {
      return "Invalid full backup: missing chapter file index";
    }
    if (!manifest.config || !Array.isArray(manifest.config.tabs)) {
      return "Invalid full backup: missing configuration";
    }
    if (
      !manifest.library.every(
        (story) => story && typeof story.id === "string",
      )
    ) {
      return "Invalid full backup: malformed story data";
    }
    return null;
  }
}

export const backupService = new BackupService();
