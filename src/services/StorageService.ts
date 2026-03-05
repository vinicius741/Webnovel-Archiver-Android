import AsyncStorage from "@react-native-async-storage/async-storage";
import { Story, DownloadStatus, RegexCleanupRule } from "../types";
import * as fileSystem from "./storage/fileSystem";
import DEFAULT_SENTENCE_REMOVAL_LIST from "../constants/default_sentence_removal.json";
import { validateRegexCleanupRule } from "../utils/textCleanup";

const STORAGE_KEYS = {
  LIBRARY: "wa_library_v1",
  SETTINGS: "wa_settings_v1",
  SENTENCE_REMOVAL: "wa_sentence_removal_v1",
  REGEX_CLEANUP_RULES: "wa_regex_cleanup_rules_v1",
  TTS_SETTINGS: "wa_tts_settings_v1",
  TTS_SESSION: "wa_tts_session_v1",
  CHAPTER_FILTER_SETTINGS: "wa_chapter_filter_settings_v1",
};

export interface AppSettings {
  downloadConcurrency: number;
  downloadDelay: number; // in milliseconds
  maxChaptersPerEpub: number; // Maximum chapters per EPUB before splitting
}

const DEFAULT_SETTINGS: AppSettings = {
  downloadConcurrency: 1,
  downloadDelay: 500,
  maxChaptersPerEpub: 150,
};

export interface TTSSettings {
  pitch: number;
  rate: number;
  voiceIdentifier?: string;
  chunkSize: number;
}

export interface TTSSession {
  storyId: string;
  chapterId: string;
  chapterTitle: string;
  currentChunkIndex: number;
  isPaused: boolean;
  wasPlaying: boolean;
  chunkSize: number;
  voiceIdentifier?: string;
  rate: number;
  pitch: number;
  updatedAt: number;
  sessionVersion: number;
}

const DEFAULT_TTS_SETTINGS: TTSSettings = {
  pitch: 1.0,
  rate: 1.0,
  chunkSize: 500,
};

const DEFAULT_REGEX_CLEANUP_RULES: RegexCleanupRule[] = [];

export type ChapterFilterMode =
  | "all"
  | "hideNonDownloaded"
  | "hideAboveBookmark";

export interface ChapterFilterSettings {
  filterMode: ChapterFilterMode;
}

const DEFAULT_CHAPTER_FILTER_SETTINGS: ChapterFilterSettings = {
  filterMode: "all",
};

interface RegexCleanupRuleRejection {
  id?: string;
  name?: string;
  reason: string;
}

interface RegexCleanupRulesSanitizeResult {
  rules: RegexCleanupRule[];
  rejected: RegexCleanupRuleRejection[];
}

class StorageService {
  private sanitizeRegexCleanupRules(
    input: unknown,
  ): RegexCleanupRulesSanitizeResult {
    if (!Array.isArray(input)) return { rules: [], rejected: [] };

    const sanitized: RegexCleanupRule[] = [];
    const rejected: RegexCleanupRuleRejection[] = [];
    for (const item of input) {
      if (!item || typeof item !== "object") {
        rejected.push({ reason: "Entry is not a valid object." });
        continue;
      }

      const id =
        typeof (item).id === "string" ? (item).id.trim() : "";
      const name =
        typeof (item).name === "string" ? (item).name.trim() : "";
      const pattern =
        typeof (item).pattern === "string" ? (item).pattern : "";
      const flags =
        typeof (item).flags === "string" ? (item).flags : "";
      const enabled =
        typeof (item).enabled === "boolean"
          ? (item).enabled
          : true;
      const appliesToRaw = (item).appliesTo;
      const appliesTo =
        appliesToRaw === "download" ||
        appliesToRaw === "tts" ||
        appliesToRaw === "both"
          ? appliesToRaw
          : "both";

      if (!id) {
        rejected.push({ name, reason: "Missing rule id." });
        continue;
      }
      const validation = validateRegexCleanupRule({ name, pattern, flags });
      if (!validation.valid) {
        rejected.push({
          id,
          name,
          reason: validation.error || "Validation failed.",
        });
        continue;
      }

      sanitized.push({
        id,
        name,
        pattern: validation.normalizedPattern || pattern.trim(),
        flags: validation.normalizedFlags || "",
        enabled,
        appliesTo,
      });
    }

    const unique = new Map<string, RegexCleanupRule>();
    sanitized.forEach((rule) => unique.set(rule.id, rule));
    return {
      rules: Array.from(unique.values()),
      rejected,
    };
  }

  async getLibrary(): Promise<Story[]> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY);
      return jsonValue != null ? JSON.parse(jsonValue) : [];
    } catch (e) {
      console.error("Failed to load library", e);
      return [];
    }
  }

  async saveLibrary(library: Story[]): Promise<void> {
    try {
      const jsonValue = JSON.stringify(library);
      await AsyncStorage.setItem(STORAGE_KEYS.LIBRARY, jsonValue);
    } catch (e) {
      console.error("Failed to save library", e);
    }
  }

  async addStory(story: Story): Promise<void> {
    const library = await this.getLibrary();
    // Check if distinct
    const index = library.findIndex((s) => s.id === story.id);
    if (index >= 0) {
      // Preserve original dateAdded if it exists on the old one, unless the new one already has it?
      // Usually updates don't change dateAdded.
      // If the incoming object doesn't have dateAdded, we should copy it from the old one to be safe.
      const existing = library[index];
      story.dateAdded = existing.dateAdded || story.dateAdded;

      library[index] = story;
    } else {
      // New story
      story.dateAdded = Date.now();
      library.push(story);
    }
    await this.saveLibrary(library);
  }

  async getStory(id: string): Promise<Story | undefined> {
    const library = await this.getLibrary();
    return library.find((s) => s.id === id);
  }

  async updateStory(story: Story): Promise<void> {
    await this.addStory(story); // addStory handles update if ID matches
  }

  async deleteStory(id: string): Promise<void> {
    try {
      await fileSystem.deleteNovel(id);
    } catch (e) {
      console.warn("Failed to delete files for story", id, e);
    }

    const library = await this.getLibrary();
    const newLibrary = library.filter((s) => s.id !== id);
    await this.saveLibrary(newLibrary);
  }

  async updateStoryStatus(id: string, status: DownloadStatus): Promise<void> {
    const library = await this.getLibrary();
    const story = library.find((s) => s.id === id);
    if (story) {
      story.status = status;
      await this.saveLibrary(library);
    }
  }

  async updateLastRead(storyId: string, chapterId: string): Promise<void> {
    const library = await this.getLibrary();
    const story = library.find((s) => s.id === storyId);
    if (story) {
      story.lastReadChapterId = chapterId;
      // Also update timestamp to bubble it up in sort
      story.lastUpdated = Date.now();
      await this.saveLibrary(library);
    }
  }

  async clearAll(): Promise<void> {
    try {
      await AsyncStorage.clear();
      await fileSystem.clearAllFiles();
      console.log("[StorageService] All data cleared.");
    } catch (e) {
      console.error("Failed to clear all data", e);
    }
  }

  async getSettings(): Promise<AppSettings> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.SETTINGS);
      return jsonValue != null
        ? { ...DEFAULT_SETTINGS, ...JSON.parse(jsonValue) }
        : DEFAULT_SETTINGS;
    } catch (e) {
      console.error("Failed to load settings", e);
      return DEFAULT_SETTINGS;
    }
  }

  async saveSettings(settings: AppSettings): Promise<void> {
    try {
      const jsonValue = JSON.stringify(settings);
      await AsyncStorage.setItem(STORAGE_KEYS.SETTINGS, jsonValue);
    } catch (e) {
      console.error("Failed to save settings", e);
    }
  }

  async getSentenceRemovalList(): Promise<string[]> {
    try {
      const jsonValue = await AsyncStorage.getItem(
        STORAGE_KEYS.SENTENCE_REMOVAL,
      );
      return jsonValue != null
        ? JSON.parse(jsonValue)
        : DEFAULT_SENTENCE_REMOVAL_LIST;
    } catch (e) {
      console.error("Failed to load sentence removal list", e);
      return DEFAULT_SENTENCE_REMOVAL_LIST;
    }
  }

  async saveSentenceRemovalList(list: string[]): Promise<void> {
    try {
      const jsonValue = JSON.stringify(list);
      await AsyncStorage.setItem(STORAGE_KEYS.SENTENCE_REMOVAL, jsonValue);
    } catch (e) {
      console.error("Failed to save sentence removal list", e);
    }
  }

  async getRegexCleanupRules(): Promise<RegexCleanupRule[]> {
    const result = await this.getRegexCleanupRulesWithDiagnostics();
    return result.rules;
  }

  async getRegexCleanupRulesWithDiagnostics(): Promise<{
    rules: RegexCleanupRule[];
    rejected: RegexCleanupRuleRejection[];
  }> {
    try {
      const jsonValue = await AsyncStorage.getItem(
        STORAGE_KEYS.REGEX_CLEANUP_RULES,
      );
      if (!jsonValue)
        return { rules: DEFAULT_REGEX_CLEANUP_RULES, rejected: [] };

      const parsed = JSON.parse(jsonValue);
      const sanitized = this.sanitizeRegexCleanupRules(parsed);

      if (sanitized.rejected.length > 0) {
        console.warn(
          `[StorageService] Skipped ${sanitized.rejected.length} invalid regex cleanup rule(s) while loading.`,
        );
      }

      return sanitized;
    } catch (e) {
      console.error("Failed to load regex cleanup rules", e);
      return { rules: DEFAULT_REGEX_CLEANUP_RULES, rejected: [] };
    }
  }

  async saveRegexCleanupRules(rules: RegexCleanupRule[]): Promise<void> {
    try {
      const sanitized = this.sanitizeRegexCleanupRules(rules);
      const jsonValue = JSON.stringify(sanitized.rules);
      await AsyncStorage.setItem(STORAGE_KEYS.REGEX_CLEANUP_RULES, jsonValue);

      if (sanitized.rejected.length > 0) {
        console.warn(
          `[StorageService] Skipped ${sanitized.rejected.length} invalid regex cleanup rule(s) while saving.`,
        );
      }
    } catch (e) {
      console.error("Failed to save regex cleanup rules", e);
    }
  }

  async getTTSSettings(): Promise<TTSSettings> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.TTS_SETTINGS);
      return jsonValue != null
        ? { ...DEFAULT_TTS_SETTINGS, ...JSON.parse(jsonValue) }
        : DEFAULT_TTS_SETTINGS;
    } catch (e) {
      console.error("Failed to load TTS settings", e);
      return DEFAULT_TTS_SETTINGS;
    }
  }

  async saveTTSSettings(settings: TTSSettings): Promise<void> {
    try {
      const jsonValue = JSON.stringify(settings);
      await AsyncStorage.setItem(STORAGE_KEYS.TTS_SETTINGS, jsonValue);
    } catch (e) {
      console.error("Failed to save TTS settings", e);
    }
  }

  async getTTSSession(): Promise<TTSSession | null> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.TTS_SESSION);
      if (!jsonValue) return null;
      return JSON.parse(jsonValue) as TTSSession;
    } catch (e) {
      console.error("Failed to load TTS session", e);
      return null;
    }
  }

  async saveTTSSession(session: TTSSession): Promise<void> {
    try {
      const jsonValue = JSON.stringify(session);
      await AsyncStorage.setItem(STORAGE_KEYS.TTS_SESSION, jsonValue);
    } catch (e) {
      console.error("Failed to save TTS session", e);
    }
  }

  async clearTTSSession(): Promise<void> {
    try {
      await AsyncStorage.removeItem(STORAGE_KEYS.TTS_SESSION);
    } catch (e) {
      console.error("Failed to clear TTS session", e);
    }
  }

  async getChapterFilterSettings(): Promise<ChapterFilterSettings> {
    try {
      const jsonValue = await AsyncStorage.getItem(
        STORAGE_KEYS.CHAPTER_FILTER_SETTINGS,
      );
      return jsonValue != null
        ? { ...DEFAULT_CHAPTER_FILTER_SETTINGS, ...JSON.parse(jsonValue) }
        : DEFAULT_CHAPTER_FILTER_SETTINGS;
    } catch (e) {
      console.error("Failed to load chapter filter settings", e);
      return DEFAULT_CHAPTER_FILTER_SETTINGS;
    }
  }

  async saveChapterFilterSettings(
    settings: ChapterFilterSettings,
  ): Promise<void> {
    try {
      const jsonValue = JSON.stringify(settings);
      await AsyncStorage.setItem(
        STORAGE_KEYS.CHAPTER_FILTER_SETTINGS,
        jsonValue,
      );
    } catch (e) {
      console.error("Failed to save chapter filter settings", e);
    }
  }
}

export const storageService = new StorageService();
