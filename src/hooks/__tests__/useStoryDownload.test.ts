
import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useStoryDownload } from '../useStoryDownload';
import { storageService } from '../../services/StorageService';
import { downloadService } from '../../services/DownloadService';
import { sourceRegistry } from '../../services/source/SourceRegistry';
import * as fetcher from '../../services/network/fetcher';
import * as keepAwake from 'expo-keep-awake';
import { Story, DownloadStatus } from '../../types';

// Mock dependencies
jest.mock('../../services/DownloadService');
jest.mock('../../services/source/SourceRegistry');
jest.mock('../../services/network/fetcher');
jest.mock('../../services/StorageService', () => ({
    storageService: {
        getSettings: jest.fn().mockResolvedValue({ downloadConcurrency: 3, downloadDelay: 500 }),
        getLibrary: jest.fn(),
        addStory: jest.fn(),
        getStory: jest.fn(),
        saveLibrary: jest.fn(),
        saveSettings: jest.fn(),
    }
}));
jest.mock('expo-keep-awake', () => ({
    activateKeepAwakeAsync: jest.fn(),
    deactivateKeepAwake: jest.fn(),
}));

const mockShowAlert = jest.fn();
jest.mock('../../context/AlertContext', () => ({
    useAppAlert: () => ({ showAlert: mockShowAlert }),
}));

describe('useStoryDownload', () => {
    const mockStory: Story = {
        id: '1',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com/story',
        coverUrl: 'http://cover',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://test.com/c1', downloaded: true, filePath: 'path/c1' }
        ],
        status: DownloadStatus.Completed,
        totalChapters: 1,
        downloadedChapters: 1,
        dateAdded: 1000,
        lastUpdated: 2000,
        tags: ['tag1']
    };

    const mockOnStoryUpdated = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        (storageService.getSettings as jest.Mock).mockResolvedValue({
            downloadConcurrency: 3,
            downloadDelay: 500
        });
    });

    it('should handle update check when all chapters downloaded', async () => {
        // Setup Mocks for update check
        const mockProvider = {
            getChapterList: jest.fn().mockResolvedValue([
                { url: 'http://test.com/c1', title: 'Chapter 1' },
                { url: 'http://test.com/c2', title: 'Chapter 2' } // New chapter
            ]),
            parseMetadata: jest.fn().mockReturnValue({ tags: ['tag1'] })
        };
        (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
        (fetcher.fetchPage as jest.Mock).mockResolvedValue('<html></html>');

        const { result } = renderHook(() => useStoryDownload({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.downloadOrUpdate();
        });

        expect(result.current.checkingUpdates).toBe(false);
        expect(storageService.addStory).toHaveBeenCalled();
        expect(mockOnStoryUpdated).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith('Update Found', expect.stringContaining('1 new chapters'));
    });

    it('should start download if chapters missing', async () => {
        const uncompletedStory = {
            ...mockStory,
            status: DownloadStatus.Partial,
            totalChapters: 2,
            downloadedChapters: 1,
            chapters: [
                { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true },
                { id: 'c2', title: 'Chapter 2', url: 'http://c2', downloaded: false }
            ]
        };

        const { result } = renderHook(() => useStoryDownload({ story: uncompletedStory, onStoryUpdated: mockOnStoryUpdated }));

        (downloadService.downloadAllChapters as jest.Mock).mockResolvedValue(uncompletedStory);

        await act(async () => {
            await result.current.downloadOrUpdate();
        });

        expect(downloadService.downloadAllChapters).toHaveBeenCalledWith(uncompletedStory);
        expect(keepAwake.activateKeepAwakeAsync).toHaveBeenCalled();
        expect(keepAwake.deactivateKeepAwake).toHaveBeenCalled();
        expect(mockShowAlert).toHaveBeenCalledWith('Download Started', expect.anything());
    });

    it('should handle download error', async () => {
        const uncompletedStory = {
            ...mockStory,
            status: DownloadStatus.Partial,
            totalChapters: 2,
            downloadedChapters: 1,
            chapters: [{ id: 'c1', title: 'C1', url: 'u1', downloaded: false }]
        };
        (downloadService.downloadAllChapters as jest.Mock).mockRejectedValue(new Error('Download failed'));

        const { result } = renderHook(() => useStoryDownload({ story: uncompletedStory, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.downloadOrUpdate();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Download Error', expect.anything());
        expect(result.current.queueing).toBe(false);
    });

    it('should validate download range', async () => {
        const { result } = renderHook(() => useStoryDownload({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        await act(async () => {
            await result.current.downloadRange(5, 1); // Invalid range
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Invalid Range', expect.anything());
        expect(downloadService.downloadRange).not.toHaveBeenCalled();
    });

    it('should apply sentence removal', async () => {
        const { result } = renderHook(() => useStoryDownload({ story: mockStory, onStoryUpdated: mockOnStoryUpdated }));

        // applySentenceRemoval calls showAlert with buttons.
        // We need to simulate the 'Apply' button press.
        // mockShowAlert receives (title, msg, buttons).

        await act(async () => {
            await result.current.applySentenceRemoval();
        });

        expect(mockShowAlert).toHaveBeenCalled();
        const buttons = mockShowAlert.mock.calls[0][2];
        const applyButton = buttons.find((b: any) => b.text === 'Apply');

        expect(applyButton).toBeDefined();

        // Simulate press
        (downloadService.applySentenceRemovalToStory as jest.Mock).mockResolvedValue({ processed: 1, errors: 0 });

        await act(async () => {
            await applyButton.onPress();
        });

        expect(downloadService.applySentenceRemovalToStory).toHaveBeenCalled();
        expect(storageService.getStory).toHaveBeenCalled();
    });
});
