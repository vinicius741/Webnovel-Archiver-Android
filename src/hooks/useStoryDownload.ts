import { useState } from 'react';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../services/StorageService';
import { downloadService } from '../services/DownloadService';
import { fetchPage } from '../services/network/fetcher';
import { sourceRegistry } from '../services/source/SourceRegistry';
import { useAppAlert } from '../context/AlertContext';
import { Story, DownloadStatus } from '../types';
import { mergeChapters } from '../utils/mergeChapters';
import { validateStory, validateDownloadRange } from '../utils/storyValidation';

interface UseStoryDownloadParams {
    story: Story | null;
    onStoryUpdated: (updatedStory: Story) => void;
}

export const useStoryDownload = ({ story, onStoryUpdated }: UseStoryDownloadParams) => {
    const { showAlert } = useAppAlert();
    const [checkingUpdates, setCheckingUpdates] = useState(false);
    const [updateStatus, setUpdateStatus] = useState('');
    const [queueing, setQueueing] = useState(false);

    const downloadAll = async () => {
        if (!validateStory(story)) return;

        try {
            setQueueing(true);
            await activateKeepAwakeAsync();

            const updatedStory = await downloadService.downloadAllChapters(story);
            const finalStory = {
                ...updatedStory,
                epubPath: undefined
            };

            await storageService.addStory(finalStory);
            onStoryUpdated(finalStory);
            showAlert('Download Started', 'Chapters have been added to the download queue.');

        } catch (error) {
            console.error('Download error', error);
            showAlert('Download Error', 'Failed to download chapters. Check logs.');
        } finally {
            setQueueing(false);
            await deactivateKeepAwake();
        }
    };

    const updateNovel = async () => {
        if (!validateStory(story)) return;

        try {
            setCheckingUpdates(true);
            setUpdateStatus('Initializing...');
            const provider = sourceRegistry.getProvider(story.sourceUrl);
            if (!provider) {
                throw new Error('Unsupported source URL.');
            }

            const html = await fetchPage(story.sourceUrl);
            const newChapters = await provider.getChapterList(html, story.sourceUrl, (msg) => {
                setUpdateStatus(msg);
            });
            const metadata = provider.parseMetadata(html);

            setUpdateStatus('Merging...');

            const mergeResult = mergeChapters(
                story.chapters,
                newChapters,
                provider,
                story.lastReadChapterId
            );

            const tagsChanged = JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);
            const hasUpdates = mergeResult.newChaptersCount > 0 || tagsChanged;
            const newChapterCount = mergeResult.newChaptersCount;

            const updatedStory: Story = {
                ...story,
                chapters: mergeResult.chapters,
                totalChapters: mergeResult.chapters.length,
                downloadedChapters: mergeResult.downloadedCount,
                status: newChapterCount > 0 ? DownloadStatus.Partial : story.status,
                lastUpdated: Date.now(),
                tags: metadata.tags,
                title: metadata.title || story.title,
                author: metadata.author || story.author,
                coverUrl: metadata.coverUrl || story.coverUrl,
                description: metadata.description || story.description,
                score: metadata.score || story.score,
                sourceUrl: metadata.canonicalUrl || story.sourceUrl,
                lastReadChapterId: mergeResult.lastReadChapterId,
                epubPath: newChapterCount > 0 ? undefined : story.epubPath,
                epubPaths: newChapterCount > 0 ? undefined : story.epubPaths,
            };

            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);

            if (hasUpdates) {
                if (newChapterCount > 0) {
                    showAlert('Update Found', `Found ${newChapterCount} new chapters!`);
                } else if (tagsChanged) {
                    showAlert('Metadata Updated', 'Tags and details updated.');
                }
            } else {
                showAlert('No Updates', 'No new chapters found. Updated last checked time.');
            }

        } catch (error: any) {
            console.error('Update error', error);
            showAlert('Update Error', error.message || 'Failed to check for updates. Check logs.');
        } finally {
            setCheckingUpdates(false);
            setUpdateStatus('');
        }
    };

    const downloadRange = async (start: number, end: number) => {
        if (!validateStory(story)) return;

        const validation = validateDownloadRange(start, end, story.totalChapters);
        if (!validation.valid) {
            showAlert('Invalid Range', validation.error || 'Please enter a valid range of chapters.');
            return;
        }

        const startIndex = start - 1;
        const endIndex = end - 1;

        try {
            setQueueing(true);
            await activateKeepAwakeAsync();

            const updatedStory = await downloadService.downloadRange(
                story,
                startIndex,
                endIndex
            );

            const finalStory = {
                ...updatedStory,
                epubPath: undefined
            };

            await storageService.addStory(finalStory);
            onStoryUpdated(finalStory);
            showAlert('Download Started', 'Selected chapters have been queued.');

        } catch (error) {
            console.error('Download error', error);
            showAlert('Download Error', 'Failed to download chapters. Check logs.');
        } finally {
            setQueueing(false);
            await deactivateKeepAwake();
        }
    };

    const applySentenceRemoval = async () => {
        if (!story) return;

        const downloadedChapters = story.chapters.filter(c => c.downloaded);
        if (downloadedChapters.length === 0) {
            showAlert('No Chapters', 'No downloaded chapters to process.');
            return;
        }

        showAlert(
            'Apply Sentence Removal',
            `This will apply the current sentence removal list to ${downloadedChapters.length} downloaded chapters. The EPUB will need to be regenerated after.`,
            [
                { text: 'Cancel', style: 'cancel' },
                {
                    text: 'Apply',
                    onPress: async () => {
                        try {
                            setCheckingUpdates(true);
                            setUpdateStatus('Processing...');

                            let currentChapterTitle = '';

                            const { processed, errors } = await downloadService.applySentenceRemovalToStory(
                                story,
                                (current, total, title) => {
                                    currentChapterTitle = title;
                                    setUpdateStatus(`Processing ${current}/${total}: ${title}`);
                                }
                            );

                            setCheckingUpdates(false);
                            setUpdateStatus('');

                            if (errors > 0) {
                                showAlert(
                                    'Processing Complete with Errors',
                                    `Processed ${processed} chapters, ${errors} had errors.`
                                );
                            } else {
                                showAlert(
                                    'Processing Complete',
                                    `Successfully applied sentence removal to ${processed} chapters. Please regenerate the EPUB.`
                                );
                            }

                            const reloadedStory = await storageService.getStory(story.id);
                            if (reloadedStory) {
                                onStoryUpdated(reloadedStory);
                            }
                        } catch (error: any) {
                            setCheckingUpdates(false);
                            setUpdateStatus('');
                            showAlert('Error', error.message || 'Failed to apply sentence removal.');
                        }
                    }
                }
            ]
        );
    };

    return {
        checkingUpdates,
        updateStatus,
        queueing,
        downloadAll,
        updateNovel,
        downloadRange,
        applySentenceRemoval,
    };
};
