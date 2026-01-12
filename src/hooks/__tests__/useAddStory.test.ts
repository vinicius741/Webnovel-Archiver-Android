
import { renderHook, act, waitFor } from '@testing-library/react-native';
import { useAddStory } from '../useAddStory';
import { storageService } from '../../services/StorageService';
import { fetchPage } from '../../services/network/fetcher';
import { sourceRegistry } from '../../services/source/SourceRegistry';
import * as Clipboard from 'expo-clipboard';

jest.mock('../../services/StorageService');
jest.mock('../../services/network/fetcher');
jest.mock('../../services/source/SourceRegistry');
jest.mock('expo-clipboard');

const mockShowAlert = jest.fn();
const mockRouter = { back: jest.fn() };
jest.mock('../../context/AlertContext', () => ({
    useAppAlert: () => ({ showAlert: mockShowAlert }),
}));
jest.mock('expo-router', () => ({
    useRouter: () => mockRouter,
}));

describe('useAddStory', () => {
    const mockUrl = 'http://test.com/novel';
    const mockHtml = '<html><body>Novel Content</body></html>';
    const mockProvider = {
        name: 'Test Provider',
        getStoryId: jest.fn().mockReturnValue('story1'),
        parseMetadata: jest.fn().mockReturnValue({
            title: 'Test Novel',
            author: 'Test Author',
            coverUrl: 'http://cover.jpg',
            description: 'Test description',
            tags: ['fiction', 'action'],
            score: 4.5,
        }),
        getChapterList: jest.fn().mockResolvedValue([
            { url: 'http://test.com/c1', title: 'Chapter 1' },
            { url: 'http://test.com/c2', title: 'Chapter 2' },
        ]),
    };

    beforeEach(() => {
        jest.clearAllMocks();
        (sourceRegistry.getProvider as jest.Mock).mockReturnValue(mockProvider);
        (fetchPage as jest.Mock).mockResolvedValue(mockHtml);
        (storageService.addStory as jest.Mock).mockResolvedValue(undefined);
    });

    it('should paste URL from clipboard', async () => {
        (Clipboard.getStringAsync as jest.Mock).mockResolvedValue(mockUrl);

        const { result } = renderHook(() => useAddStory());

        await act(async () => {
            await result.current.handlePaste();
        });

        expect(result.current.url).toBe(mockUrl);
    });

    it('should add story successfully', async () => {
        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(fetchPage).toHaveBeenCalledWith(mockUrl);
        expect(mockProvider.parseMetadata).toHaveBeenCalledWith(mockHtml);
        expect(mockProvider.getChapterList).toHaveBeenCalled();
        expect(storageService.addStory).toHaveBeenCalledWith(
            expect.objectContaining({
                id: 'story1',
                title: 'Test Novel',
                author: 'Test Author',
                sourceUrl: mockUrl,
                status: 'idle',
                totalChapters: 2,
                downloadedChapters: 0,
            })
        );
        expect(mockShowAlert).toHaveBeenCalledWith('Success', 'Added "Test Novel" to library.', [
            { text: 'OK', onPress: expect.any(Function) }
        ]);
        expect(result.current.loading).toBe(false);
        expect(result.current.statusMessage).toBe('');
    });

    it('should show error for unsupported URL', async () => {
        (sourceRegistry.getProvider as jest.Mock).mockReturnValue(null);

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Error', 'Unsupported source URL.');
        expect(fetchPage).not.toHaveBeenCalled();
        expect(result.current.loading).toBe(false);
    });

    it('should show error when no chapters found', async () => {
        mockProvider.getChapterList = jest.fn().mockResolvedValue([]);

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Error', 'No chapters found. Please check the URL.');
        expect(storageService.addStory).not.toHaveBeenCalled();
        expect(result.current.loading).toBe(false);
    });

    it('should handle fetch error', async () => {
        (fetchPage as jest.Mock).mockRejectedValue(new Error('Network error'));

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(mockShowAlert).toHaveBeenCalledWith('Error', expect.stringContaining('Failed to fetch the novel'));
        expect(result.current.loading).toBe(false);
        expect(result.current.statusMessage).toBe('');
    });

    it('should update status message during add process', async () => {
        mockProvider.getChapterList = jest.fn().mockImplementation(async (_html, _url, callback) => {
            setTimeout(() => callback('Parsing chapters...'), 10);
            return [{ url: 'http://test.com/c1', title: 'Chapter 1' }];
        });

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        await waitFor(() => {
            expect(result.current.statusMessage).toBe('Parsing chapters...');
        });
    });

    it('should not add story when URL is empty', async () => {
        const { result } = renderHook(() => useAddStory());

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(fetchPage).not.toHaveBeenCalled();
        expect(result.current.loading).toBe(false);
    });

    it('should sanitize chapter titles', async () => {
        mockProvider.getChapterList = jest.fn().mockResolvedValue([
            { url: 'http://test.com/c1', title: 'Chapter 1...' },
            { url: 'http://test.com/c2', title: '  Chapter 2  ' },
        ]);

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        const savedStory = (storageService.addStory as jest.Mock).mock.calls[0][0];
        expect(savedStory.chapters[0].title).toBe('Chapter 1');
        expect(savedStory.chapters[1].title).toBe('Chapter 2');
    });

    it('should handle OK button press after successful add', async () => {
        let alertButtons: any;
        mockShowAlert.mockImplementation((title, message, buttons) => {
            alertButtons = buttons;
        });

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(alertButtons).toBeDefined();
        const okButton = alertButtons.find((b: any) => b.text === 'OK');
        expect(okButton).toBeDefined();

        await act(async () => {
            await okButton.onPress();
        });

        expect(mockRouter.back).toHaveBeenCalled();
    });

    it('should clear status message on error', async () => {
        (fetchPage as jest.Mock).mockRejectedValue(new Error('Error'));

        const { result } = renderHook(() => useAddStory());

        act(() => {
            result.current.setUrl(mockUrl);
        });

        await act(async () => {
            await result.current.handleAdd();
        });

        expect(result.current.statusMessage).toBe('');
    });

    it('should not do anything when pasting empty clipboard', async () => {
        (Clipboard.getStringAsync as jest.Mock).mockResolvedValue('');

        const { result } = renderHook(() => useAddStory());

        await act(async () => {
            await result.current.handlePaste();
        });

        expect(result.current.url).toBe('');
    });
});
