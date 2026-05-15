import AsyncStorage from "@react-native-async-storage/async-storage";

import type {
  AppSettings,
  ArchiveReason,
  ChapterFilterSettings,
  DownloadStatus,
  FoldLayoutMode,
  RegexCleanupRule,
  SourceDownloadSettingsMap,
  Story,
  TTSSettings,
  TTSSession,
} from "../types";
import type { Tab } from "../types/tab";
import * as fileSystem from "./storage/fileSystem";
import { downloadQueue } from "./download/DownloadQueue";
import { LibraryStorage } from "./storage/libraryStorage";
import { PreferencesStorage } from "./storage/preferencesStorage";
import {
  RegexCleanupRulesStorage,
  type RegexCleanupRulesWithDiagnostics,
} from "./storage/regexCleanupRulesStorage";

export type {
  AppSettings,
  ChapterFilterMode,
  ChapterFilterSettings,
  FoldLayoutMode,
  SourceDownloadSettings,
  SourceDownloadSettingsMap,
  TTSSettings,
  TTSSession,
} from "../types";
export type {
  RegexCleanupRuleRejection,
  RegexCleanupRulesWithDiagnostics,
} from "./storage/regexCleanupRulesStorage";

class StorageService {
  private libraryStorage = new LibraryStorage();
  private preferencesStorage = new PreferencesStorage();
  private regexCleanupRulesStorage = new RegexCleanupRulesStorage();

  async getLibrary(): Promise<Story[]> {
    return this.libraryStorage.getLibrary();
  }

  async saveLibrary(library: Story[]): Promise<void> {
    await this.libraryStorage.saveLibrary(library);
  }

  async addStory(story: Story): Promise<void> {
    await this.libraryStorage.addStory(story);
  }

  async getStory(id: string): Promise<Story | undefined> {
    return this.libraryStorage.getStory(id);
  }

  async updateStory(story: Story): Promise<void> {
    await this.libraryStorage.updateStory(story);
  }

  async createArchivedStorySnapshot(
    story: Story,
    reason: ArchiveReason,
  ): Promise<Story> {
    return this.libraryStorage.createArchivedStorySnapshot(story, reason);
  }

  async deleteStory(id: string): Promise<void> {
    await this.libraryStorage.deleteStory(id);
  }

  async updateStoryStatus(id: string, status: DownloadStatus): Promise<void> {
    await this.libraryStorage.updateStoryStatus(id, status);
  }

  async updateLastRead(storyId: string, chapterId: string): Promise<void> {
    await this.libraryStorage.updateLastRead(storyId, chapterId);
  }

  async clearAll(): Promise<void> {
    try {
      downloadQueue.clearAll();
      await AsyncStorage.clear();
      await fileSystem.clearAllFiles();
      console.log("[StorageService] All data cleared.");
    } catch (error) {
      console.error("Failed to clear all data", error);
    }
  }

  async getSettings(): Promise<AppSettings> {
    return this.preferencesStorage.getSettings();
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    await this.preferencesStorage.saveSettings(settings);
  }

  async getSentenceRemovalList(): Promise<string[]> {
    return this.preferencesStorage.getSentenceRemovalList();
  }

  async saveSentenceRemovalList(list: string[]): Promise<void> {
    await this.preferencesStorage.saveSentenceRemovalList(list);
  }

  async getRegexCleanupRules(): Promise<RegexCleanupRule[]> {
    return this.regexCleanupRulesStorage.getRegexCleanupRules();
  }

  async getRegexCleanupRulesWithDiagnostics(): Promise<RegexCleanupRulesWithDiagnostics> {
    return this.regexCleanupRulesStorage.getRegexCleanupRulesWithDiagnostics();
  }

  async saveRegexCleanupRules(rules: RegexCleanupRule[]): Promise<void> {
    await this.regexCleanupRulesStorage.saveRegexCleanupRules(rules);
  }

  async getTTSSettings(): Promise<TTSSettings> {
    return this.preferencesStorage.getTTSSettings();
  }

  async saveTTSSettings(settings: TTSSettings): Promise<void> {
    await this.preferencesStorage.saveTTSSettings(settings);
  }

  async getTTSSession(): Promise<TTSSession | null> {
    return this.preferencesStorage.getTTSSession();
  }

  async saveTTSSession(session: TTSSession): Promise<void> {
    await this.preferencesStorage.saveTTSSession(session);
  }

  async clearTTSSession(): Promise<void> {
    await this.preferencesStorage.clearTTSSession();
  }

  async getChapterFilterSettings(): Promise<ChapterFilterSettings> {
    return this.preferencesStorage.getChapterFilterSettings();
  }

  async saveChapterFilterSettings(
    settings: ChapterFilterSettings,
  ): Promise<void> {
    await this.preferencesStorage.saveChapterFilterSettings(settings);
  }

  async getTabs(): Promise<Tab[]> {
    return this.preferencesStorage.getTabs();
  }

  async saveTabs(tabs: Tab[]): Promise<void> {
    await this.preferencesStorage.saveTabs(tabs);
  }

  async getFoldLayoutMode(): Promise<FoldLayoutMode> {
    return this.preferencesStorage.getFoldLayoutMode();
  }

  async saveFoldLayoutMode(mode: FoldLayoutMode): Promise<void> {
    await this.preferencesStorage.saveFoldLayoutMode(mode);
  }

  async moveStoriesToTab(
    storyIds: string[],
    tabId: string | null,
  ): Promise<void> {
    await this.libraryStorage.moveStoriesToTab(storyIds, tabId);
  }

  async getSourceDownloadSettings(): Promise<SourceDownloadSettingsMap> {
    return this.preferencesStorage.getSourceDownloadSettings();
  }

  async saveSourceDownloadSettings(
    settings: SourceDownloadSettingsMap,
  ): Promise<void> {
    await this.preferencesStorage.saveSourceDownloadSettings(settings);
  }
}

export const storageService = new StorageService();
