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
    const [syncing, setSyncing] = useState(false);
    const [syncStatus, setSyncStatus] = useState('');
    const [queueing, setQueueing] = useState(false);

    const downloadAll = async () => {
        if (!validateStory(story)) return;

        try {
            setQueueing(true);
            await activateKeepAwakeAsync();

            const updatedStory = await downloadService.downloadAllChapters(story);
            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);
            showAlert('Download Started', 'Chapters have been added to the download queue.');

        } catch (error) {
            console.error('Download error', error);
            showAlert('Download Error', 'Failed to download chapters. Check logs.');
        } finally {
            setQueueing(false);
            await deactivateKeepAwake();
        }
    };

    const syncChapters = async () => {
        if (!validateStory(story)) return;

        try {
            setSyncing(true);
            setSyncStatus('Initializing...');
            const provider = sourceRegistry.getProvider(story.sourceUrl);
            if (!provider) {
                throw new Error('Unsupported source URL.');
            }

            const html = await fetchPage(story.sourceUrl);
            const newChapters = await provider.getChapterList(html, story.sourceUrl, (msg) => {
                setSyncStatus(msg);
            });
            const metadata = provider.parseMetadata(html);

            setSyncStatus('Merging...');

            const mergeResult = mergeChapters(
                story.chapters,
                newChapters,
                provider,
                story.lastReadChapterId
            );

            const tagsChanged = JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);
            const hasUpdates = mergeResult.newChapterIds.length > 0 || tagsChanged;
            const newChapterCount = mergeResult.newChapterIds.length;

            const existingPending = story.pendingNewChapterIds ?? [];
            const pendingSet = new Set([...existingPending, ...mergeResult.newChapterIds]);
            const chapterMap = new Map(mergeResult.chapters.map(ch => [ch.id, ch]));
            const pendingNewChapterIds = Array.from(pendingSet)
                .filter(id => {
                    const chapter = chapterMap.get(id);
                    return chapter && !chapter.downloaded;
                });

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
                pendingNewChapterIds: pendingNewChapterIds.length > 0 ? pendingNewChapterIds : undefined,
            };

            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);

            if (hasUpdates) {
                if (newChapterCount > 0) {
                    setQueueing(true);
                    const queuedStory = await downloadService.downloadChaptersByIds(updatedStory, mergeResult.newChapterIds);
                    await storageService.addStory(queuedStory);
                    onStoryUpdated(queuedStory);
                    showAlert('Update Found', `Found ${newChapterCount} new chapters. Download queued.`);
                } else if (tagsChanged) {
                    showAlert('Metadata Updated', 'Tags and details updated.');
                }
            } else {
                showAlert('No Updates', 'No new chapters found. Updated last checked time.');
            }

        } catch (error: any) {
            console.error('Update error', error);
            showAlert('Sync Error', error.message || 'Failed to sync chapters. Check logs.');
        } finally {
            setQueueing(false);
            setSyncing(false);
            setSyncStatus('');
        }
    };

    // `startChapter`/`endChapter` are 1-based inclusive values from UI inputs.
    const downloadRange = async (startChapter: number, endChapter: number) => {
        if (!validateStory(story)) return;

        const validation = validateDownloadRange(startChapter, endChapter, story.totalChapters);
        if (!validation.valid) {
            showAlert('Invalid Range', validation.error || 'Please enter a valid range of chapters.');
            return;
        }

        const startIndex = startChapter - 1;
        const endIndex = endChapter - 1;

        try {
            setQueueing(true);
            await activateKeepAwakeAsync();

            const updatedStory = await downloadService.downloadRange(
                story,
                startIndex,
                endIndex
            );
            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);
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
                            setSyncing(true);
                            setSyncStatus('Processing...');

                            let currentChapterTitle = '';

                            const { processed, errors } = await downloadService.applySentenceRemovalToStory(
                                story,
                                (current, total, title) => {
                                    currentChapterTitle = title;
                                    setSyncStatus(`Processing ${current}/${total}: ${title}`);
                                }
                            );

                            setSyncing(false);
                            setSyncStatus('');

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
                            setSyncing(false);
                            setSyncStatus('');
                            showAlert('Error', error.message || 'Failed to apply sentence removal.');
                        }
                    }
                }
            ]
        );
    };

    return {
        syncing,
        syncStatus,
        queueing,
        downloadAll,
        syncChapters,
        downloadRange,
        applySentenceRemoval,
    };
};
