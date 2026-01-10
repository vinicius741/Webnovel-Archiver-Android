import { useState } from 'react';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../services/StorageService';
import { downloadService } from '../services/DownloadService';
import { fetchPage } from '../services/network/fetcher';
import { sourceRegistry } from '../services/source/SourceRegistry';
import { useAppAlert } from '../context/AlertContext';
import { Story, Chapter, DownloadStatus } from '../types';
import { sanitizeTitle } from '../utils/stringUtils';
import { validateStory, validateDownloadRange, hasAllChaptersDownloaded, hasUpdatesAvailable } from '../utils/storyValidation';

interface UseStoryDownloadParams {
    story: Story | null;
    onStoryUpdated: (updatedStory: Story) => void;
}

interface UpdateCheckResult {
    updatedStory: Story;
    hasNewChapters: number;
    tagsChanged: boolean;
}

export const useStoryDownload = ({ story, onStoryUpdated }: UseStoryDownloadParams) => {
    const { showAlert } = useAppAlert();
    const [checkingUpdates, setCheckingUpdates] = useState(false);
    const [updateStatus, setUpdateStatus] = useState('');
    const [queueing, setQueueing] = useState(false);

    const checkForUpdates = async (): Promise<UpdateCheckResult | null> => {
        if (!validateStory(story)) return null;

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

            const updatedChapters: Chapter[] = newChapters.map(newChap => {
                const existing = story.chapters.find(c => c.url === newChap.url);
                if (existing) {
                    return existing;
                }
                return {
                    id: newChap.url,
                    title: sanitizeTitle(newChap.title),
                    url: newChap.url,
                    downloaded: false,
                };
            });

            const tagsChanged = JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);
            const hasUpdates = updatedChapters.length > story.chapters.length || tagsChanged;
            const newChapterCount = Math.max(0, updatedChapters.length - story.chapters.length);

            const updatedStory: Story = {
                ...story,
                chapters: updatedChapters,
                totalChapters: updatedChapters.length,
                status: newChapterCount > 0 ? DownloadStatus.Partial : story.status,
                lastUpdated: Date.now(),
                tags: metadata.tags,
                title: metadata.title || story.title,
                author: metadata.author || story.author,
                coverUrl: metadata.coverUrl || story.coverUrl,
                description: metadata.description || story.description,
                score: metadata.score || story.score,
                epubPath: newChapterCount > 0 ? undefined : story.epubPath,
            };

            return {
                updatedStory,
                hasNewChapters: newChapterCount,
                tagsChanged,
            };
        } catch (error: any) {
            throw error;
        }
    };

    const downloadOrUpdate = async () => {
        if (!validateStory(story)) return;

        if (hasAllChaptersDownloaded(story)) {
            try {
                const result = await checkForUpdates();
                if (!result) return;

                const { updatedStory, hasNewChapters, tagsChanged } = result;

                await storageService.addStory(updatedStory);
                onStoryUpdated(updatedStory);

                if (hasUpdatesAvailable(story, hasNewChapters, tagsChanged)) {
                    if (hasNewChapters > 0) {
                        showAlert('Update Found', `Found ${hasNewChapters} new chapters!`);
                    } else if (tagsChanged) {
                        showAlert('Metadata Updated', 'Tags and details updated.');
                    }
                } else {
                    showAlert('No Updates', 'No new chapters found. Updated last checked time.');
                }

            } catch (error: any) {
                showAlert('Update Error', error.message);
            } finally {
                setCheckingUpdates(false);
                setUpdateStatus('');
            }
            return;
        }

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
        downloadOrUpdate,
        downloadRange,
        applySentenceRemoval,
    };
};
