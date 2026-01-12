import AsyncStorage from '@react-native-async-storage/async-storage';
import { Story, Chapter, DownloadStatus } from '../types';
import * as fileSystem from './storage/fileSystem';
import DEFAULT_SENTENCE_REMOVAL_LIST from '../constants/default_sentence_removal.json';

const STORAGE_KEYS = {
    LIBRARY: 'wa_library_v1',
    SETTINGS: 'wa_settings_v1',
    SENTENCE_REMOVAL: 'wa_sentence_removal_v1',
    TTS_SETTINGS: 'wa_tts_settings_v1',
    CHAPTER_FILTER_SETTINGS: 'wa_chapter_filter_settings_v1',
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

const DEFAULT_TTS_SETTINGS: TTSSettings = {
    pitch: 1.0,
    rate: 1.0,
    chunkSize: 500,
};

export type ChapterFilterMode = 'all' | 'hideNonDownloaded' | 'hideAboveBookmark';

export interface ChapterFilterSettings {
    filterMode: ChapterFilterMode;
}

const DEFAULT_CHAPTER_FILTER_SETTINGS: ChapterFilterSettings = {
    filterMode: 'all',
};

class StorageService {
    async getLibrary(): Promise<Story[]> {
        try {
            const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY);
            return jsonValue != null ? JSON.parse(jsonValue) : [];
        } catch (e) {
            console.error('Failed to load library', e);
            return [];
        }
    }

    async saveLibrary(library: Story[]): Promise<void> {
        try {
            const jsonValue = JSON.stringify(library);
            await AsyncStorage.setItem(STORAGE_KEYS.LIBRARY, jsonValue);
        } catch (e) {
            console.error('Failed to save library', e);
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
            console.warn('Failed to delete files for story', id, e);
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
            console.log('[StorageService] All data cleared.');
        } catch (e) {
            console.error('Failed to clear all data', e);
        }
    }

    async getSettings(): Promise<AppSettings> {
        try {
            const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.SETTINGS);
            return jsonValue != null ? { ...DEFAULT_SETTINGS, ...JSON.parse(jsonValue) } : DEFAULT_SETTINGS;
        } catch (e) {
            console.error('Failed to load settings', e);
            return DEFAULT_SETTINGS;
        }
    }

    async saveSettings(settings: AppSettings): Promise<void> {
        try {
            const jsonValue = JSON.stringify(settings);
            await AsyncStorage.setItem(STORAGE_KEYS.SETTINGS, jsonValue);
        } catch (e) {
            console.error('Failed to save settings', e);
        }
    }

    async getSentenceRemovalList(): Promise<string[]> {
        try {
            const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.SENTENCE_REMOVAL);
            return jsonValue != null ? JSON.parse(jsonValue) : DEFAULT_SENTENCE_REMOVAL_LIST;
        } catch (e) {
            console.error('Failed to load sentence removal list', e);
            return DEFAULT_SENTENCE_REMOVAL_LIST;
        }
    }

    async saveSentenceRemovalList(list: string[]): Promise<void> {
        try {
            const jsonValue = JSON.stringify(list);
            await AsyncStorage.setItem(STORAGE_KEYS.SENTENCE_REMOVAL, jsonValue);
        } catch (e) {
            console.error('Failed to save sentence removal list', e);
        }
    }

    async getTTSSettings(): Promise<TTSSettings> {
        try {
            const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.TTS_SETTINGS);
            return jsonValue != null ? { ...DEFAULT_TTS_SETTINGS, ...JSON.parse(jsonValue) } : DEFAULT_TTS_SETTINGS;
        } catch (e) {
            console.error('Failed to load TTS settings', e);
            return DEFAULT_TTS_SETTINGS;
        }
    }

    async saveTTSSettings(settings: TTSSettings): Promise<void> {
        try {
            const jsonValue = JSON.stringify(settings);
            await AsyncStorage.setItem(STORAGE_KEYS.TTS_SETTINGS, jsonValue);
        } catch (e) {
            console.error('Failed to save TTS settings', e);
        }
    }

    async getChapterFilterSettings(): Promise<ChapterFilterSettings> {
        try {
            const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.CHAPTER_FILTER_SETTINGS);
            return jsonValue != null ? { ...DEFAULT_CHAPTER_FILTER_SETTINGS, ...JSON.parse(jsonValue) } : DEFAULT_CHAPTER_FILTER_SETTINGS;
        } catch (e) {
            console.error('Failed to load chapter filter settings', e);
            return DEFAULT_CHAPTER_FILTER_SETTINGS;
        }
    }

    async saveChapterFilterSettings(settings: ChapterFilterSettings): Promise<void> {
        try {
            const jsonValue = JSON.stringify(settings);
            await AsyncStorage.setItem(STORAGE_KEYS.CHAPTER_FILTER_SETTINGS, jsonValue);
        } catch (e) {
            console.error('Failed to save chapter filter settings', e);
        }
    }
}

export const storageService = new StorageService();
