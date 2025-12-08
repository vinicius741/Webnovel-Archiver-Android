import AsyncStorage from '@react-native-async-storage/async-storage';
import { Story, Chapter, DownloadStatus } from '../types';

const STORAGE_KEYS = {
    LIBRARY: 'wa_library_v1',
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
            library[index] = story;
        } else {
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
        const library = await this.getLibrary();
        const newLibrary = library.filter((s) => s.id !== id);
        await this.saveLibrary(newLibrary);
    }

    // Helper to quickly update status
    async updateStoryStatus(id: string, status: DownloadStatus): Promise<void> {
        const library = await this.getLibrary();
        const story = library.find((s) => s.id === id);
        if (story) {
            story.status = status;
            await this.saveLibrary(library);
        }
    }
}

export const storageService = new StorageService();
