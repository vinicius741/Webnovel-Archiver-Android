import { useState, useEffect, useRef } from 'react';
import { useRouter } from 'expo-router';

import { storageService } from '../services/StorageService';
import { Story } from '../types';

import { useDownloadProgress } from './useDownloadProgress';
import { useStoryActions } from './useStoryActions';
import { useStoryDownload } from './useStoryDownload';
import { useStoryEPUB } from './useStoryEPUB';

export const useStoryDetails = (id: string | string[] | undefined) => {
    const router = useRouter();
    const [story, setStory] = useState<Story | null>(null);
    const [loading, setLoading] = useState(true);

    const storyId = Array.isArray(id) ? id[0] : (typeof id === 'string' ? id : '');
    const { progress: downloadProgress, status: downloadStatus, isDownloading: isDownloadingHook } = useDownloadProgress(storyId);

    const prevDownloading = useRef(isDownloadingHook);

    useEffect(() => {
        const loadStory = async () => {
            if (storyId) {
                const data = await storageService.getStory(storyId);
                if (data) {
                    setStory(data);
                }
            }
            setLoading(false);
        };
        loadStory();
    }, [storyId]);

    useEffect(() => {
        if (prevDownloading.current && !isDownloadingHook && storyId) {
            const reloadStory = async () => {
                try {
                    const data = await storageService.getStory(storyId);
                    if (data) {
                        setStory(data);
                    }
                } catch (error) {
                    console.error('Failed to reload story', error);
                    setStory(null);
                }
            };
            reloadStory();
        }
        prevDownloading.current = isDownloadingHook;
    }, [isDownloadingHook, storyId]);

    const { 
        deleteStory,
        markChapterAsRead,
    } = useStoryActions({
        story,
        onStoryUpdated: setStory,
        onStoryDeleted: () => {
            if (router.canDismiss()) {
                router.dismiss();
            } else {
                router.replace('/');
            }
        },
    });

    const {
        syncing,
        syncStatus,
        queueing,
        syncChapters,
        downloadRange,
        applySentenceRemoval,
    } = useStoryDownload({
        story,
        onStoryUpdated: setStory,
    });

    const {
        generating,
        progress: epubProgress,
        generateOrRead,
    } = useStoryEPUB({
        story,
        onStoryUpdated: setStory,
    });

    const updateStory = async (updatedStory: Story) => {
        await storageService.addStory(updatedStory);
        setStory(updatedStory);
    };

    return {
        story,
        loading,
        downloading: queueing || isDownloadingHook,
        syncing,
        syncStatus,
        downloadProgress,
        downloadStatus,
        generating,
        epubProgress,
        deleteStory,
        markChapterAsRead,
        syncChapters,
        downloadRange,
        generateOrRead,
        applySentenceRemoval,
        updateStory,
    };
};
