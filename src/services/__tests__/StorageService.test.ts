import AsyncStorage from '@react-native-async-storage/async-storage';
import { storageService } from '../StorageService';
import * as fileSystem from '../storage/fileSystem';
import { Story, DownloadStatus } from '../../types';

jest.mock('@react-native-async-storage/async-storage', () => ({
    getItem: jest.fn(),
    setItem: jest.fn(),
    clear: jest.fn(),
}));

jest.mock('../storage/fileSystem', () => ({
    deleteNovel: jest.fn(),
    clearAllFiles: jest.fn(),
}));

describe('StorageService', () => {
    beforeEach(() => {
        jest.clearAllMocks();
    });

    describe('getLibrary', () => {
        it('should return empty array if storage is null', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
            const result = await storageService.getLibrary();
            expect(result).toEqual([]);
        });

        it('should return parsed library', async () => {
            const mockLibrary = [{ id: '1', title: 'Test Story' }];
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify(mockLibrary));
            const result = await storageService.getLibrary();
            expect(result).toEqual(mockLibrary);
        });

        it('should handle errors gracefully', async () => {
            (AsyncStorage.getItem as jest.Mock).mockRejectedValue(new Error('Storage error'));
            const result = await storageService.getLibrary();
            expect(result).toEqual([]);
        });
    });

    describe('addStory', () => {
        it('should add a new story', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([]));
            const newStory: Story = { id: '1', title: 'New Story', author: 'Me', sourceUrl: 'http://test', coverUrl: 'http://cover', chapters: [], status: DownloadStatus.Idle, totalChapters: 0, downloadedChapters: 0 };

            await storageService.addStory(newStory);

            expect(AsyncStorage.setItem).toHaveBeenCalledWith(
                'wa_library_v1',
                expect.stringContaining(JSON.stringify(newStory.title))
            );
        });

        it('should update existing story', async () => {
            const existingStory: Story = { id: '1', title: 'Old Title', author: 'Me', sourceUrl: 'http://test', coverUrl: 'http://cover', chapters: [], dateAdded: 12345, status: DownloadStatus.Idle, totalChapters: 0, downloadedChapters: 0 };
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([existingStory]));

            const updatedStory: Story = { ...existingStory, title: 'New Title' };
            await storageService.addStory(updatedStory);

            expect(AsyncStorage.setItem).toHaveBeenCalled();
            const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
            const savedLibrary = JSON.parse(saveCall[1]);
            expect(savedLibrary[0].title).toBe('New Title');
            expect(savedLibrary[0].dateAdded).toBe(12345); // Should preserve dateAdded
        });
    });

    describe('deleteStory', () => {
        it('should delete story from library and file system', async () => {
            const story: Story = { id: '1', title: 'Delete Me', author: 'Me', sourceUrl: 'http://test', coverUrl: 'http://cover', chapters: [], status: DownloadStatus.Idle, totalChapters: 0, downloadedChapters: 0 };
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([story]));

            await storageService.deleteStory('1');

            expect(fileSystem.deleteNovel).toHaveBeenCalledWith('1');
            expect(AsyncStorage.setItem).toHaveBeenCalledWith('wa_library_v1', '[]');
        });
    });

    describe('updateStoryStatus', () => {
        it('should update status', async () => {
            const story: Story = { id: '1', title: 'Status Test', author: 'Me', sourceUrl: 'http://test', coverUrl: 'http://cover', chapters: [], status: DownloadStatus.Idle, totalChapters: 0, downloadedChapters: 0 };
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([story]));

            await storageService.updateStoryStatus('1', DownloadStatus.Downloading);

            const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
            const savedLibrary = JSON.parse(saveCall[1]);
            expect(savedLibrary[0].status).toBe(DownloadStatus.Downloading);
        });
    });

    describe('updateLastRead', () => {
        it('should update last read chapter and timestamp', async () => {
            const story: Story = { id: '1', title: 'Read Test', author: 'Me', sourceUrl: 'http://test', coverUrl: 'http://cover', chapters: [], status: DownloadStatus.Idle, totalChapters: 0, downloadedChapters: 0 };
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(JSON.stringify([story]));

            await storageService.updateLastRead('1', 'chap1');

            const saveCall = (AsyncStorage.setItem as jest.Mock).mock.calls[0];
            const savedLibrary = JSON.parse(saveCall[1]);
            expect(savedLibrary[0].lastReadChapterId).toBe('chap1');
            expect(savedLibrary[0].lastUpdated).toBeDefined();
        });
    });

    describe('Settings', () => {
        it('should get default settings if none saved', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
            const settings = await storageService.getSettings();
            expect(settings.downloadConcurrency).toBeDefined();
        });

        it('should save settings', async () => {
            const settings = { downloadConcurrency: 2, downloadDelay: 1000 };
            await storageService.saveSettings(settings);
            expect(AsyncStorage.setItem).toHaveBeenCalledWith('wa_settings_v1', JSON.stringify(settings));
        });
    });

    describe('Sentence Removal List', () => {
        it('should get default list if none saved', async () => {
            (AsyncStorage.getItem as jest.Mock).mockResolvedValue(null);
            const list = await storageService.getSentenceRemovalList();
            expect(list.length).toBeGreaterThan(0);
        });

        it('should save list', async () => {
            const list = ['remove me'];
            await storageService.saveSentenceRemovalList(list);
            expect(AsyncStorage.setItem).toHaveBeenCalledWith('wa_sentence_removal_v1', JSON.stringify(list));
        });
    });

    describe('clearAll', () => {
        it('should clear storage and files', async () => {
            await storageService.clearAll();
            expect(AsyncStorage.clear).toHaveBeenCalled();
            expect(fileSystem.clearAllFiles).toHaveBeenCalled();
        });
    });
});
