import AsyncStorage from "@react-native-async-storage/async-storage";
import DEFAULT_SENTENCE_REMOVAL_LIST from "../../constants/default_sentence_removal.json";
import type {
  AppSettings,
  ChapterFilterSettings,
  FoldLayoutMode,
  TTSSettings,
  TTSSession,
} from "../../types";
import type { Tab } from "../../types/tab";
import { STORAGE_KEYS } from "./storageKeys";

const DEFAULT_SETTINGS: AppSettings = {
  downloadConcurrency: 1,
  downloadDelay: 500,
  maxChaptersPerEpub: 150,
};

const DEFAULT_TTS_SETTINGS: TTSSettings = {
  pitch: 1.0,
  rate: 1.0,
  chunkSize: 500,
};

const DEFAULT_CHAPTER_FILTER_SETTINGS: ChapterFilterSettings = {
  filterMode: "all",
};

const DEFAULT_FOLD_LAYOUT_MODE: FoldLayoutMode = "auto";

export class PreferencesStorage {
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

  async getTabs(): Promise<Tab[]> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.TABS);
      return jsonValue != null ? JSON.parse(jsonValue) : [];
    } catch (e) {
      console.error("Failed to load tabs", e);
      return [];
    }
  }

  async saveTabs(tabs: Tab[]): Promise<void> {
    try {
      const jsonValue = JSON.stringify(tabs);
      await AsyncStorage.setItem(STORAGE_KEYS.TABS, jsonValue);
    } catch (e) {
      console.error("Failed to save tabs", e);
    }
  }

  async getFoldLayoutMode(): Promise<FoldLayoutMode> {
    try {
      const value = await AsyncStorage.getItem(STORAGE_KEYS.FOLD_LAYOUT_MODE);
      if (value === "auto" || value === "cover" || value === "inner") {
        return value;
      }
      return DEFAULT_FOLD_LAYOUT_MODE;
    } catch (e) {
      console.error("Failed to load fold layout mode", e);
      return DEFAULT_FOLD_LAYOUT_MODE;
    }
  }

  async saveFoldLayoutMode(mode: FoldLayoutMode): Promise<void> {
    try {
      await AsyncStorage.setItem(STORAGE_KEYS.FOLD_LAYOUT_MODE, mode);
    } catch (e) {
      console.error("Failed to save fold layout mode", e);
    }
  }
}
