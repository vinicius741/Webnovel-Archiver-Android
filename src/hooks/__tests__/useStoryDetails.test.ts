import { renderHook, waitFor } from '@testing-library/react-native';
import { useStoryDetails } from '../useStoryDetails';
import { storageService } from '../../services/StorageService';
import { Story, Chapter, DownloadStatus } from '../../types';

jest.mock('../../services/StorageService');
jest.mock('../useDownloadProgress', () => ({
    useDownloadProgress: jest.fn(),
}));
jest.mock('../useStoryActions', () => ({
    useStoryActions: jest.fn(),
}));
jest.mock('../useStoryDownload', () => ({
    useStoryDownload: jest.fn(),
}));
jest.mock('../useStoryEPUB', () => ({
    useStoryEPUB: jest.fn(),
}));
jest.mock('expo-router', () => ({
    useRouter: jest.fn(() => ({
        push: jest.fn(),
        replace: jest.fn(),
        back: jest.fn(),
    })),
}));

describe('useStoryDetails', () => {
    const mockStory: Story = {
        id: '123',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com',
        coverUrl: 'http://cover.com',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true },
        ],
        status: DownloadStatus.Completed,
        totalChapters: 1,
        downloadedChapters: 1,
        dateAdded: 1000,
        lastUpdated: 2000,
    };

    beforeEach(() => {
        jest.clearAllMocks();
        (storageService.getStory as jest.Mock).mockResolvedValue(mockStory);
        (storageService.getSettings as jest.Mock).mockResolvedValue({
            downloadConcurrency: 3,
        } as any);

        const useDownloadProgress = require('../useDownloadProgress').useDownloadProgress as jest.Mock;
        useDownloadProgress.mockReturnValue({
            progress: jest.fn(),
            status: jest.fn(),
            isDownloading: jest.fn(),
        });

        const useStoryActions = require('../useStoryActions').useStoryActions as jest.Mock;
        useStoryActions.mockReturnValue({
            deleteStory: jest.fn(),
            markChapterAsRead: jest.fn(),
        });

        const useStoryDownload = require('../useStoryDownload').useStoryDownload as jest.Mock;
        useStoryDownload.mockReturnValue({
            checkingUpdates: jest.fn(),
            updateStatus: jest.fn(),
            queueing: jest.fn(),
            downloadOrUpdate: jest.fn(),
        });

        const useStoryEPUB = require('../useStoryEPUB').useStoryEPUB as jest.Mock;
        useStoryEPUB.mockReturnValue({
            generating: jest.fn(),
            progress: jest.fn(),
            generateOrRead: jest.fn(),
        });
    });

    it('should load story on mount', async () => {
        const { result } = renderHook(() => useStoryDetails('123'));

        expect(result.current.loading).toBe(true);

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(storageService.getStory).toHaveBeenCalledWith('123');
        expect(result.current.story).toEqual(mockStory);
    });

    it('should handle string id parameter', async () => {
        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(storageService.getStory).toHaveBeenCalledWith('123');
    });

    it('should handle array id parameter by using first element', async () => {
        const { result } = renderHook(() => useStoryDetails(['456']));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(storageService.getStory).toHaveBeenCalledWith('456');
    });

    it('should handle undefined id', async () => {
        const { result } = renderHook(() => useStoryDetails(undefined));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(storageService.getStory).not.toHaveBeenCalled();
        expect(result.current.story).toBeNull();
    });

    it('should set story to null if not found', async () => {
        (storageService.getStory as jest.Mock).mockResolvedValue(null);

        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.story).toBeNull();
    });

    it('should reload story after download completes', async () => {
        let downloadState = true;

        const { useDownloadProgress } = require('../useDownloadProgress');
        useDownloadProgress.mockReturnValue({
            progress: 50,
            status: 'downloading',
            isDownloading: downloadState,
        });

        const { result, rerender } = renderHook(({ id }: { id: string | undefined }) => useStoryDetails(id), {
            initialProps: { id: '123' },
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(storageService.getStory).toHaveBeenCalledTimes(1);

        downloadState = false;
        useDownloadProgress.mockReturnValue({
            progress: 100,
            status: 'completed',
            isDownloading: downloadState,
        });

        rerender({ id: '123' });

        await waitFor(() => {
            expect(storageService.getStory).toHaveBeenCalledTimes(2);
        });
    });

    it('should handle error when reloading story', async () => {
        let downloadState = true;

        const { useDownloadProgress } = require('../useDownloadProgress');
        useDownloadProgress.mockReturnValue({
            progress: 50,
            status: 'downloading',
            isDownloading: downloadState,
        });

        const { result, rerender } = renderHook(({ id }: { id: string | undefined }) => useStoryDetails(id), {
            initialProps: { id: '123' },
        });

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        downloadState = false;
        useDownloadProgress.mockReturnValue({
            progress: 100,
            status: 'completed',
            isDownloading: downloadState,
        });

        (storageService.getStory as jest.Mock).mockRejectedValueOnce(new Error('Fetch failed'));

        rerender({ id: '123' });

        await waitFor(() => {
            const current = result.current as any;
            expect(current.story).toBeNull();
        });
    });

    it('should expose story actions', async () => {
        const { useStoryActions } = require('../useStoryActions');
        const mockDeleteStory = jest.fn();
        const mockMarkChapterAsRead = jest.fn();
        useStoryActions.mockReturnValue({
            deleteStory: mockDeleteStory,
            markChapterAsRead: mockMarkChapterAsRead,
        });

        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(typeof result.current.deleteStory).toBe('function');
        expect(typeof result.current.markChapterAsRead).toBe('function');
    });

    it('should expose story download functions', async () => {
        const { useStoryDownload } = require('../useStoryDownload');
        const mockDownloadOrUpdate = jest.fn();
        const mockDownloadRange = jest.fn();
        const mockApplySentenceRemoval = jest.fn();
        useStoryDownload.mockReturnValue({
            checkingUpdates: false,
            updateStatus: '',
            queueing: false,
            downloadOrUpdate: mockDownloadOrUpdate,
            downloadRange: mockDownloadRange,
            applySentenceRemoval: mockApplySentenceRemoval,
        });

        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(typeof result.current.downloadOrUpdate).toBe('function');
        expect(typeof result.current.downloadRange).toBe('function');
        expect(typeof result.current.applySentenceRemoval).toBe('function');
    });

    it('should expose epub generation functions', async () => {
        const { useStoryEPUB } = require('../useStoryEPUB');
        const mockGenerateOrRead = jest.fn();
        useStoryEPUB.mockReturnValue({
            generating: false,
            progress: null,
            generateOrRead: mockGenerateOrRead,
        });

        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(typeof result.current.generateOrRead).toBe('function');
    });

    it('should calculate downloading state correctly', async () => {
        const { useStoryDownload } = require('../useStoryDownload');
        const { useStoryEPUB } = require('../useStoryEPUB');
        const { useDownloadProgress } = require('../useDownloadProgress');

        useStoryDownload.mockReturnValue({
            checkingUpdates: false,
            updateStatus: '',
            queueing: true,
            downloadOrUpdate: jest.fn(),
            downloadRange: jest.fn(),
            applySentenceRemoval: jest.fn(),
        });

        useStoryEPUB.mockReturnValue({
            generating: false,
            progress: null,
            generateOrRead: jest.fn(),
        });

        useDownloadProgress.mockReturnValue({
            progress: 50,
            status: 'downloading',
            isDownloading: true,
        });

        const { result } = renderHook(() => useStoryDetails('123'));

        await waitFor(() => {
            expect(result.current.loading).toBe(false);
        });

        expect(result.current.downloading).toBe(true);
    });
});
