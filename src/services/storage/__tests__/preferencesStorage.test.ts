import AsyncStorage from "@react-native-async-storage/async-storage";
import { PreferencesStorage } from "../preferencesStorage";
import { STORAGE_KEYS } from "../storageKeys";

describe("PreferencesStorage", () => {
  let preferencesStorage: PreferencesStorage;

  beforeEach(() => {
    jest.clearAllMocks();
    preferencesStorage = new PreferencesStorage();
  });

  describe("getSettings", () => {
    it("should return default settings when none stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getSettings();

      expect(result).toEqual({
        downloadConcurrency: 1,
        downloadDelay: 500,
        maxChaptersPerEpub: 150,
      });
    });

    it("should return stored settings merged with defaults", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify({ downloadConcurrency: 3 }),
      );

      const result = await preferencesStorage.getSettings();

      expect(result).toEqual({
        downloadConcurrency: 3,
        downloadDelay: 500,
        maxChaptersPerEpub: 150,
      });
    });

    it("should return defaults on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getSettings();

      expect(result).toEqual({
        downloadConcurrency: 1,
        downloadDelay: 500,
        maxChaptersPerEpub: 150,
      });
    });
  });

  describe("saveSettings", () => {
    it("should save settings to AsyncStorage", async () => {
      const settings = {
        downloadConcurrency: 5,
        downloadDelay: 1000,
        maxChaptersPerEpub: 200,
      };

      await preferencesStorage.saveSettings(settings);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.SETTINGS,
        JSON.stringify(settings),
      );
    });
  });

  describe("getSentenceRemovalList", () => {
    it("should return stored list", async () => {
      const list = ["sentence 1", "sentence 2"];
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify(list),
      );

      const result = await preferencesStorage.getSentenceRemovalList();

      expect(result).toEqual(list);
    });

    it("should return default list when none stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getSentenceRemovalList();

      expect(Array.isArray(result)).toBe(true);
      expect(result.length).toBeGreaterThan(0);
    });

    it("should return default list on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getSentenceRemovalList();

      expect(Array.isArray(result)).toBe(true);
    });
  });

  describe("saveSentenceRemovalList", () => {
    it("should save list to AsyncStorage", async () => {
      const list = ["sentence 1", "sentence 2"];

      await preferencesStorage.saveSentenceRemovalList(list);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.SENTENCE_REMOVAL,
        JSON.stringify(list),
      );
    });
  });

  describe("getTTSSettings", () => {
    it("should return default TTS settings when none stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getTTSSettings();

      expect(result).toEqual({
        pitch: 1.0,
        rate: 1.0,
        chunkSize: 500,
      });
    });

    it("should return stored TTS settings merged with defaults", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify({ pitch: 0.8, rate: 1.2 }),
      );

      const result = await preferencesStorage.getTTSSettings();

      expect(result).toEqual({
        pitch: 0.8,
        rate: 1.2,
        chunkSize: 500,
      });
    });

    it("should return defaults on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getTTSSettings();

      expect(result).toEqual({
        pitch: 1.0,
        rate: 1.0,
        chunkSize: 500,
      });
    });
  });

  describe("saveTTSSettings", () => {
    it("should save TTS settings to AsyncStorage", async () => {
      const settings = { pitch: 0.9, rate: 1.1, chunkSize: 400 };

      await preferencesStorage.saveTTSSettings(settings);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.TTS_SETTINGS,
        JSON.stringify(settings),
      );
    });
  });

  describe("getTTSSession", () => {
    it("should return stored TTS session", async () => {
      const session = {
        storyId: "story-1",
        chapterIndex: 0,
        paragraphIndex: 5,
        timestamp: Date.now(),
      };
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify(session),
      );

      const result = await preferencesStorage.getTTSSession();

      expect(result).toEqual(session);
    });

    it("should return null when no session stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getTTSSession();

      expect(result).toBeNull();
    });

    it("should return null on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getTTSSession();

      expect(result).toBeNull();
    });
  });

  describe("saveTTSSession", () => {
    it("should save TTS session to AsyncStorage", async () => {
      const session = {
        storyId: "story-1",
        chapterIndex: 0,
        paragraphIndex: 5,
        timestamp: Date.now(),
      };

      await preferencesStorage.saveTTSSession(session);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.TTS_SESSION,
        JSON.stringify(session),
      );
    });
  });

  describe("clearTTSSession", () => {
    it("should remove TTS session from AsyncStorage", async () => {
      await preferencesStorage.clearTTSSession();

      expect(AsyncStorage.removeItem).toHaveBeenCalledWith(
        STORAGE_KEYS.TTS_SESSION,
      );
    });
  });

  describe("getChapterFilterSettings", () => {
    it("should return default settings when none stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getChapterFilterSettings();

      expect(result).toEqual({ filterMode: "all" });
    });

    it("should return stored settings", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify({ filterMode: "hideNonDownloaded" }),
      );

      const result = await preferencesStorage.getChapterFilterSettings();

      expect(result).toEqual({ filterMode: "hideNonDownloaded" });
    });

    it("should return defaults on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getChapterFilterSettings();

      expect(result).toEqual({ filterMode: "all" });
    });
  });

  describe("saveChapterFilterSettings", () => {
    it("should save chapter filter settings", async () => {
      const settings = { filterMode: "hideAboveBookmark" as const };

      await preferencesStorage.saveChapterFilterSettings(settings);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.CHAPTER_FILTER_SETTINGS,
        JSON.stringify(settings),
      );
    });
  });

  describe("getTabs", () => {
    it("should return stored tabs", async () => {
      const tabs = [
        { id: "tab-1", name: "Reading", order: 0 },
        { id: "tab-2", name: "Completed", order: 1 },
      ];
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(
        JSON.stringify(tabs),
      );

      const result = await preferencesStorage.getTabs();

      expect(result).toEqual(tabs);
    });

    it("should return empty array when no tabs stored", async () => {
      (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);

      const result = await preferencesStorage.getTabs();

      expect(result).toEqual([]);
    });

    it("should return empty array on error", async () => {
      (AsyncStorage.getItem as jest.Mock).mockRejectedValue(
        new Error("Storage error"),
      );

      const result = await preferencesStorage.getTabs();

      expect(result).toEqual([]);
    });
  });

  describe("saveTabs", () => {
    it("should save tabs to AsyncStorage", async () => {
      const tabs = [
        { id: "tab-1", name: "Reading", order: 0 },
      ];

      await preferencesStorage.saveTabs(tabs);

      expect(AsyncStorage.setItem).toHaveBeenCalledWith(
        STORAGE_KEYS.TABS,
        JSON.stringify(tabs),
      );
    });
  });
});
