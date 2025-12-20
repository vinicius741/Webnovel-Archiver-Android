import { useState, useEffect } from 'react';
import { Alert } from 'react-native';
import { useRouter } from 'expo-router';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';
import { activateKeepAwakeAsync, deactivateKeepAwake } from 'expo-keep-awake';

import { storageService } from '../services/StorageService';
import { epubGenerator } from '../services/EpubGenerator';
import { downloadService } from '../services/DownloadService';
import { fetchPage } from '../services/network/fetcher';
import { sourceRegistry } from '../services/source/SourceRegistry';
import { useAppAlert } from '../context/AlertContext';
import { Story, Chapter, DownloadStatus } from '../types';
import { sanitizeTitle } from '../utils/stringUtils';

import { useDownloadProgress } from './useDownloadProgress';

export const useStoryDetails = (id: string | string[] | undefined) => {
    const router = useRouter();
    const { showAlert } = useAppAlert();
    const [story, setStory] = useState<Story | null>(null);
    const [loading, setLoading] = useState(true);
    // Remove local downloading state as it's now managed by the hook (mostly)
    // Actually, keep it for simple loading spinners if needed, but the hook provides isDownloading.
    // Let's replace the local state with the hook's state.

    // We need a stable ID string
    const storyId = typeof id === 'string' ? id : '';
    const { progress: downloadProgress, status: downloadStatus, isDownloading: isDownloadingHook } = useDownloadProgress(storyId);

    const [downloading, setDownloading] = useState(false); // keep for "adding to queue" spinner if needed
    const [checkingUpdates, setCheckingUpdates] = useState(false);
    const [updateStatus, setUpdateStatus] = useState('');
    // const [downloadProgress, setDownloadProgress] = useState(0); // Removing
    // const [downloadStatus, setDownloadStatus] = useState(''); // Removing

    useEffect(() => {
        const loadStory = async () => {
            if (typeof id === 'string') {
                const data = await storageService.getStory(id);
                if (data) {
                    setStory(data);
                }
            }
            setLoading(false);
        };
        loadStory();
    }, [id]);

    const deleteStory = () => {
        if (!story) return;
        Alert.alert(
            'Delete Novel',
            `Are you sure you want to delete "${story.title}"? This action cannot be undone.`, // Corrected: escaped double quotes within template literal
            [
                {
                    text: 'Cancel',
                    style: 'cancel',
                },
                {
                    text: 'Delete',
                    style: 'destructive',
                    onPress: async () => {
                        await storageService.deleteStory(story.id);
                        if (router.canDismiss()) {
                            router.dismiss();
                        } else {
                            router.replace('/');
                        }
                    },
                },
            ]
        );
    };

    const markChapterAsRead = async (chapter: Chapter) => {
        if (!story) return;

        const newLastReadId = story.lastReadChapterId === chapter.id ? undefined : chapter.id;

        // Optimistic update
        const updatedStory = { ...story, lastReadChapterId: newLastReadId };
        setStory(updatedStory);

        if (newLastReadId) {
            await storageService.updateLastRead(story.id, newLastReadId);
            const cleanTitle = chapter.title.replace(/\n/g, ' ').trim(); // Corrected: escaped backslash in regex
            showAlert('Marked as Read', `Marked "${cleanTitle}" as your last read location.`); // Corrected: escaped double quotes within template literal
        } else {
            await storageService.addStory(updatedStory);
            showAlert('Cleared', 'Reading progress cleared.');
        }
    };

    const downloadOrUpdate = async () => {
        if (!story) return;

        // If already downloaded, check for updates
        if (story.downloadedChapters === story.totalChapters) {
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
                // Merge logic
                let hasUpdates = false;
                const updatedChapters: Chapter[] = newChapters.map(newChap => {
                    const existing = story.chapters.find(c => c.url === newChap.url);
                    if (existing) {
                        return existing; // Keep existing state (downloaded, filePath)
                    }
                    hasUpdates = true;
                    return {
                        id: newChap.url,
                        title: sanitizeTitle(newChap.title),
                        url: newChap.url,
                        downloaded: false,
                    };
                });

                // Check if tags changed
                const tagsChanged = JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);
                const meaningfulChange = hasUpdates || updatedChapters.length > story.chapters.length || tagsChanged;

                const updatedStory: Story = {
                    ...story,
                    chapters: updatedChapters,
                    totalChapters: updatedChapters.length,
                    status: hasUpdates ? DownloadStatus.Partial : story.status, // Only reset status if new chapters
                    lastUpdated: Date.now(),
                    tags: metadata.tags, // Update tags
                    // Update other metadata if desirable
                    title: metadata.title || story.title,
                    author: metadata.author || story.author,
                    coverUrl: metadata.coverUrl || story.coverUrl,
                    description: metadata.description || story.description,
                    score: metadata.score || story.score,
                    epubPath: hasUpdates ? undefined : story.epubPath,
                };

                await storageService.addStory(updatedStory);
                setStory(updatedStory);

                if (meaningfulChange) {
                    if (hasUpdates) {
                        showAlert('Update Found', `Found ${updatedChapters.length - story.chapters.length} new chapters!`);
                    } else if (tagsChanged) {
                        showAlert('Metadata Updated', 'Tags and details updated.');
                    }
                } else {
                    // Still update timestamp even if no content changes
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
            setDownloading(true);
            await activateKeepAwakeAsync();

            // Use the DownloadService
            const updatedStory = await downloadService.downloadAllChapters(story);

            const finalStory = {
                ...updatedStory,
                epubPath: undefined
            };

            await storageService.addStory(finalStory);
            setStory(finalStory); // Update UI with new state
            showAlert('Download Started', 'Chapters have been added to the download queue.');

        } catch (error) {
            console.error('Download error', error);
            showAlert('Download Error', 'Failed to download chapters. Check logs.');
        } finally {
            setDownloading(false);
            await deactivateKeepAwake();
        }
    };

    const downloadRange = async (start: number, end: number) => {
        if (!story) return;

        // start and end are 1-based indices from the UI (probably), or 0-based?
        // Let's assume the UI passes 1-based chapter numbers, as that's what users see.
        // We will convert to 0-based array indices.

        const startIndex = start - 1;
        const endIndex = end - 1;

        if (startIndex < 0 || endIndex >= story.totalChapters || startIndex > endIndex) {
            showAlert('Invalid Range', 'Please enter a valid range of chapters.');
            return;
        }

        try {
            setDownloading(true);
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
            setStory(finalStory);
            showAlert('Download Started', 'Selected chapters have been queued.');

        } catch (error) {
            console.error('Download error', error);
            showAlert('Download Error', 'Failed to download chapters. Check logs.');
        } finally {
            setDownloading(false);
            await deactivateKeepAwake();
        }
    };

    const generateOrRead = async () => {
        if (!story) return;

        if (story.epubPath) {
            try {
                const fileInfo = await getInfoAsync(story.epubPath);
                if (!fileInfo.exists) {
                    const updated = { ...story, epubPath: undefined };
                    await storageService.addStory(updated);
                    setStory(updated);
                    showAlert('Error', 'EPUB file not found. Please regenerate.');
                    return;
                }

                let contentUri = story.epubPath;
                if (!contentUri.startsWith('content://')) {
                    contentUri = await getContentUriAsync(story.epubPath);
                }

                await startActivityAsync('android.intent.action.VIEW', {
                    data: contentUri,
                    flags: 1, // FLAG_GRANT_READ_URI_PERMISSION
                    type: 'application/epub+zip',
                });

            } catch (e: any) {
                showAlert('Read Error', 'Could not open EPUB: ' + e.message);
            }
            return;
        }

        try {
            setLoading(true);
            const uri = await epubGenerator.generateEpub(story, story.chapters);

            const updatedStory = { ...story, epubPath: uri };
            await storageService.addStory(updatedStory);
            setStory(updatedStory);

            showAlert('Success', `EPUB exported to: ${uri}`);
            setLoading(false);
        } catch (error: any) {
            showAlert('Error', error.message);
            setLoading(false);
        }
    };

    return {
        story,
        loading,
        downloading: downloading || isDownloadingHook, // Show busy if queueing OR downloading
        checkingUpdates,
        updateStatus,
        downloadProgress,
        downloadStatus,
        deleteStory,
        markChapterAsRead,
        downloadOrUpdate,
        downloadRange,
        generateOrRead,
    };
};
