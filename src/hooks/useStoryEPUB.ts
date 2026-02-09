import { useState } from 'react';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';

import { storageService } from '../services/StorageService';
import { epubGenerator, EpubProgress, EpubResult } from '../services/EpubGenerator';
import { downloadService } from '../services/DownloadService';
import { useAppAlert } from '../context/AlertContext';
import {
    EPUB_MAX_CHAPTERS_FALLBACK,
    EPUB_MAX_CHAPTERS_MIN,
    EPUB_MAX_CHAPTERS_MAX,
} from '../constants/epub';
import { Chapter, Story } from '../types';
import { validateStory } from '../utils/storyValidation';

interface UseStoryEPUBParams {
    story: Story | null;
    onStoryUpdated: (updatedStory: Story) => void;
}

interface EpubGenerationProgress {
    current: number;
    total: number;
    percentage: number;
    stage: string;
    status: string;
    currentFile?: number;
    totalFiles?: number;
}

export const useStoryEPUB = ({ story, onStoryUpdated }: UseStoryEPUBParams) => {
    const { showAlert } = useAppAlert();
    const [generating, setGenerating] = useState(false);
    const [progress, setProgress] = useState<EpubGenerationProgress | null>(null);

    const readExistingEpub = async (epubPath: string, isFromMultiple: boolean = false): Promise<boolean> => {
        if (!validateStory(story)) return false;

        try {
            const fileInfo = await getInfoAsync(epubPath);
            if (!fileInfo.exists) {
                // If this is from multiple EPUBs, check which files still exist
                if (isFromMultiple && story.epubPaths && story.epubPaths.length > 1) {
                    const existingPaths: string[] = [];
                    for (const path of story.epubPaths) {
                        try {
                            const info = await getInfoAsync(path);
                            if (info.exists) {
                                existingPaths.push(path);
                            }
                        } catch {
                            // File doesn't exist, skip it
                        }
                    }

                    // Update story with only the existing paths
                    const updated: Story = {
                        ...story,
                        epubPaths: existingPaths.length > 0 ? existingPaths : undefined,
                        epubPath: existingPaths.length > 0 ? existingPaths[0] : undefined
                    };
                    await storageService.addStory(updated);
                    onStoryUpdated(updated);

                    if (existingPaths.length === 0) {
                        showAlert('Error', 'All EPUB files not found. Please regenerate.');
                    } else {
                        showAlert('Warning', `Some EPUB files were missing. ${existingPaths.length} file(s) remain.`);
                    }
                } else {
                    // Clear missing EPUB paths (single file or all files missing)
                    const updated: Story = {
                        ...story,
                        epubPath: undefined,
                        epubPaths: undefined,
                        epubStale: false
                    };
                    await storageService.addStory(updated);
                    onStoryUpdated(updated);
                    showAlert('Error', 'EPUB file not found. Please regenerate.');
                }
                return false;
            }

            let contentUri = epubPath;
            if (!contentUri.startsWith('content://')) {
                contentUri = await getContentUriAsync(epubPath);
            }

            await startActivityAsync('android.intent.action.VIEW', {
                data: contentUri,
                flags: 1,
                type: 'application/epub+zip',
            });

            return true;
        } catch (e: any) {
            showAlert('Read Error', 'Could not open EPUB: ' + e.message);
            return false;
        }
    };

    const readFirstEpub = async (): Promise<boolean> => {
        if (!validateStory(story)) return false;

        // Check for new epubPaths array first, fall back to old epubPath
        const epubPaths = story.epubPaths;
        const epubPath = story.epubPath;

        // Explicitly check for non-empty array
        const pathToRead = (epubPaths && epubPaths.length > 0) ? epubPaths[0] : epubPath;

        if (!pathToRead) {
            return false;
        }

        // Track if this is from multiple files for better error handling
        const isFromMultiple = epubPaths && epubPaths.length > 1;
        return await readExistingEpub(pathToRead, isFromMultiple);
    };

    const generateEpub = async (): Promise<string[] | null> => {
        if (!validateStory(story)) return null;

        try {
            setGenerating(true);
            const startTime = Date.now();

            const settings = await storageService.getSettings().catch(() => ({
                maxChaptersPerEpub: EPUB_MAX_CHAPTERS_FALLBACK,
            }));
            const totalChapters = story.chapters.length;
            const configuredMax = story.epubConfig?.maxChaptersPerEpub
                ?? settings.maxChaptersPerEpub
                ?? EPUB_MAX_CHAPTERS_FALLBACK;
            const maxChaptersPerEpub = Math.max(
                EPUB_MAX_CHAPTERS_MIN,
                Math.min(EPUB_MAX_CHAPTERS_MAX, configuredMax)
            );

            if (totalChapters === 0) {
                showAlert('No Chapters', 'No chapters available to generate an EPUB.');
                return null;
            }

            let rangeStart = Math.max(1, Math.min(totalChapters, story.epubConfig?.rangeStart ?? 1));
            const rangeEnd = Math.max(
                rangeStart,
                Math.min(totalChapters, story.epubConfig?.rangeEnd ?? totalChapters)
            );

            if (story.epubConfig?.startAfterBookmark && story.lastReadChapterId) {
                const bookmarkIndex = story.chapters.findIndex(ch => ch.id === story.lastReadChapterId);
                if (bookmarkIndex !== -1) {
                    rangeStart = Math.max(rangeStart, bookmarkIndex + 2);
                } else {
                    showAlert(
                        'Bookmark Not Found',
                        'Your saved bookmark is no longer in this chapter list. Using the configured chapter range instead.'
                    );
                }
            }

            const selectedEntries = story.chapters
                .map((chapter, index) => ({ chapter, originalChapterNumber: index + 1 }))
                .filter(({ originalChapterNumber }) => (
                    originalChapterNumber >= rangeStart && originalChapterNumber <= rangeEnd
                ))
                .filter(({ chapter }) => chapter.downloaded === true);

            if (selectedEntries.length === 0) {
                showAlert('No Downloaded Chapters', 'No downloaded chapters found in the selected EPUB range.');
                return null;
            }

            const selectedChapters: Chapter[] = selectedEntries.map(entry => entry.chapter);
            const originalChapterNumbers = selectedEntries.map(entry => entry.originalChapterNumber);
            const selectedCount = selectedChapters.length;

            setProgress({
                current: 0,
                total: selectedCount,
                percentage: 0,
                stage: 'Starting...',
                status: 'Initializing'
            });

            const results: EpubResult[] = await epubGenerator.generateEpubs(
                story,
                selectedChapters,
                maxChaptersPerEpub,
                (progressData: EpubProgress) => {
                    const elapsed = (Date.now() - startTime) / 1000;
                    const remaining = progressData.percentage > 0
                        ? (elapsed / progressData.percentage) * (100 - progressData.percentage)
                        : 0;
                    const timeString = remaining > 60
                        ? `${Math.ceil(remaining / 60)}m ${Math.ceil(remaining % 60)}s`
                        : `${Math.ceil(remaining)}s`;

                    let status = '';
                    const { currentFile, totalFiles } = progressData;

                    switch (progressData.stage) {
                        case 'filtering':
                            if (totalFiles && totalFiles > 1) {
                                status = `Filtering: File ${currentFile}/${totalFiles} - ${progressData.current}/${progressData.total} chapters`;
                            } else {
                                status = `Filtering chapters: ${progressData.current}/${progressData.total}`;
                            }
                            break;
                        case 'processing':
                            if (totalFiles && totalFiles > 1) {
                                status = `Processing: File ${currentFile}/${totalFiles} - ${progressData.current}/${progressData.total} chapters (${timeString} remaining)`;
                            } else {
                                status = `Processing: ${progressData.current}/${progressData.total} chapters (${timeString} remaining)`;
                            }
                            break;
                        case 'finalizing':
                            if (totalFiles && totalFiles > 1) {
                                status = `Finalizing file ${currentFile}/${totalFiles}...`;
                            } else {
                                status = 'Finalizing EPUB...';
                            }
                            break;
                    }

                    setProgress({
                        current: progressData.current,
                        total: progressData.total,
                        percentage: progressData.percentage,
                        stage: progressData.stage,
                        status,
                        currentFile: progressData.currentFile,
                        totalFiles: progressData.totalFiles
                    });
                },
                originalChapterNumbers
            );

            // Extract URIs and update story with epubPaths
            const epubUris = results.map(r => r.uri);

            const updatedStory: Story = {
                ...story,
                epubPaths: epubUris,
                // Keep epubPath for backward compatibility
                epubPath: epubUris[0],
                epubStale: false
            };
            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);

            if (results.length > 1) {
                showAlert(
                    'Success',
                    `Generated ${results.length} EPUB files:\n${results.map(r =>
                        `• ${r.filename} (Chapters ${r.chapterRange.start}-${r.chapterRange.end})`
                    ).join('\n')}`
                );
            } else {
                showAlert('Success', `EPUB exported to: ${results[0].uri}`);
            }
            return epubUris;
        } catch (error: any) {
            showAlert('Error', error.message);
            return null;
        } finally {
            setGenerating(false);
            setProgress(null);
        }
    };

    const generateOrRead = async (): Promise<void> => {
        if (!validateStory(story)) return;

        // Check for new epubPaths array first, fall back to old epubPath
        const epubPaths = story.epubPaths;
        const epubPath = story.epubPath;

        // Explicitly check for non-empty array before using it
        const hasEpubPaths = epubPaths && epubPaths.length > 0;
        const hasEpub = hasEpubPaths || !!epubPath;
        const pendingNewChapterIds = (story.pendingNewChapterIds || []).filter(id => {
            const chapter = story.chapters.find(ch => ch.id === id);
            return chapter && !chapter.downloaded;
        });

        if (pendingNewChapterIds.length > 0) {
            showAlert(
                'New Chapters Found',
                'Download the new chapters before reading?',
                [
                    {
                        text: 'Read',
                        style: 'cancel',
                        onPress: async () => {
                            await readFirstEpub();
                        }
                    },
                    {
                        text: 'Download',
                        onPress: async () => {
                            try {
                                const queuedStory = await downloadService.downloadChaptersByIds(story, pendingNewChapterIds);
                                await storageService.addStory(queuedStory);
                                onStoryUpdated(queuedStory);
                                showAlert('Download Started', 'New chapters have been queued for download.');
                            } catch (error: any) {
                                showAlert('Download Error', error.message || 'Failed to queue downloads.');
                            }
                        }
                    }
                ]
            );
            return;
        }

        if (!hasEpub || story.epubStale) {
            const epubUris = await generateEpub();
            if (epubUris && epubUris.length > 0) {
                await readExistingEpub(epubUris[0], epubUris.length > 1);
            }
            return;
        }

        await readFirstEpub();
    };

    return {
        generating,
        progress,
        generateOrRead,
    };
};
