import { renderHook } from '@testing-library/react-native';
import { useStoryActions } from '../useStoryActions';
import { storageService } from '../../services/StorageService';
import { useAppAlert } from '../../context/AlertContext';
import { Story, Chapter, DownloadStatus } from '../../types';

jest.mock('../../services/StorageService');
jest.mock('../../context/AlertContext');
jest.mock('../../utils/storyValidation');

describe('useStoryActions', () => {
    const mockStory: Story = {
        id: '123',
        title: 'Test Story',
        author: 'Author',
        sourceUrl: 'http://test.com',
        coverUrl: 'http://cover.com',
        chapters: [
            { id: 'c1', title: 'Chapter 1', url: 'http://c1', downloaded: true },
            { id: 'c2', title: 'Chapter 2', url: 'http://c2', downloaded: false },
        ],
        status: DownloadStatus.Completed,
        totalChapters: 2,
        downloadedChapters: 1,
        dateAdded: 1000,
        lastUpdated: 2000,
    };

    const mockOnStoryUpdated = jest.fn();
    const mockOnStoryDeleted = jest.fn();
    const mockShowAlert = jest.fn();

    beforeEach(() => {
        jest.clearAllMocks();
        (useAppAlert as jest.Mock).mockReturnValue({ showAlert: mockShowAlert });
        (storageService.deleteStory as jest.Mock).mockResolvedValue(undefined);
        (storageService.updateLastRead as jest.Mock).mockResolvedValue(undefined);
        (storageService.addStory as jest.Mock).mockResolvedValue(undefined);
    });

    describe('deleteStory', () => {
        it('should show confirmation dialog', () => {
            const { validateStory } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
                onStoryDeleted: mockOnStoryDeleted,
            }));

            result.current.deleteStory();

            expect(mockShowAlert).toHaveBeenCalledWith(
                'Delete Novel',
                expect.stringContaining(mockStory.title),
                expect.arrayContaining([
                    expect.objectContaining({ text: 'Cancel' }),
                    expect.objectContaining({
                        text: 'Delete',
                        style: 'destructive',
                        onPress: expect.any(Function),
                    }),
                ])
            );
        });

        it('should not delete if validation fails', () => {
            const { validateStory } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(false);

            const { result } = renderHook(() => useStoryActions({
                story: null,
                onStoryUpdated: mockOnStoryUpdated,
                onStoryDeleted: mockOnStoryDeleted,
            }));

            result.current.deleteStory();

            expect(mockShowAlert).not.toHaveBeenCalled();
            expect(storageService.deleteStory).not.toHaveBeenCalled();
        });

        it('should call deleteStory and onStoryDeleted on confirmation', async () => {
            const { validateStory } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
                onStoryDeleted: mockOnStoryDeleted,
            }));

            result.current.deleteStory();

            const deleteCall = mockShowAlert.mock.calls.find(
                call => call[0] === 'Delete Novel'
            );
            const deleteButton = deleteCall![2].find((b: any) => b.text === 'Delete');
            await deleteButton.onPress();

            expect(storageService.deleteStory).toHaveBeenCalledWith(mockStory.id);
            expect(mockOnStoryDeleted).toHaveBeenCalled();
        });
    });

    describe('markChapterAsRead', () => {
        it('should not mark as read if story validation fails', async () => {
            const { validateStory } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(false);

            const { result } = renderHook(() => useStoryActions({
                story: null,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead(mockStory.chapters[0]);

            expect(storageService.updateLastRead).not.toHaveBeenCalled();
        });

        it('should not mark as read if chapter validation fails', async () => {
            const { validateStory, validateChapter } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);
            validateChapter.mockReturnValue(false);

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead({ id: 'invalid', title: 'Invalid', url: 'http://invalid' });

            expect(storageService.updateLastRead).not.toHaveBeenCalled();
        });

        it('should set lastReadChapterId when marking chapter as read', async () => {
            const { validateStory, validateChapter } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);
            validateChapter.mockReturnValue(true);

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead(mockStory.chapters[0]);

            expect(storageService.updateLastRead).toHaveBeenCalledWith(mockStory.id, 'c1');
            expect(mockOnStoryUpdated).toHaveBeenCalledWith(
                expect.objectContaining({
                    lastReadChapterId: 'c1',
                })
            );
            expect(mockShowAlert).toHaveBeenCalledWith('Marked as Read', expect.stringContaining('Chapter 1'));
        });

        it('should clear lastReadChapterId when marking same chapter as read again', async () => {
            const storyWithLastRead = { ...mockStory, lastReadChapterId: 'c1' };

            const { validateStory, validateChapter } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);
            validateChapter.mockReturnValue(true);

            const { result } = renderHook(() => useStoryActions({
                story: storyWithLastRead,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead(mockStory.chapters[0]);

            expect(storageService.addStory).toHaveBeenCalledWith(
                expect.objectContaining({
                    lastReadChapterId: undefined,
                })
            );
            expect(mockShowAlert).toHaveBeenCalledWith('Cleared', 'Reading progress cleared.');
        });

        it('should clean chapter title in alert message', async () => {
            const chapterWithNewlines = { ...mockStory.chapters[0], title: 'Chapter 1\n\n' };

            const { validateStory, validateChapter } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);
            validateChapter.mockReturnValue(true);

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead(chapterWithNewlines);

            expect(mockShowAlert).toHaveBeenCalledWith('Marked as Read', expect.stringContaining('Chapter 1'));
            expect(mockShowAlert).not.toHaveBeenCalledWith('Marked as Read', expect.stringContaining('\n'));
        });

        it('should handle error during updateLastRead', async () => {
            const { validateStory, validateChapter } = require('../../utils/storyValidation');
            validateStory.mockReturnValue(true);
            validateChapter.mockReturnValue(true);

            (storageService.updateLastRead as jest.Mock).mockRejectedValue(new Error('Update failed'));

            const { result } = renderHook(() => useStoryActions({
                story: mockStory,
                onStoryUpdated: mockOnStoryUpdated,
            }));

            await result.current.markChapterAsRead(mockStory.chapters[0]);

            expect(mockOnStoryUpdated).not.toHaveBeenCalled();
        });
    });
});
