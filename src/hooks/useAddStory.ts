import { useState } from 'react';
import { useRouter } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import { useAppAlert } from '../context/AlertContext';
import { fetchPage } from '../services/network/fetcher';
import { storageService } from '../services/StorageService';
import { Story, DownloadStatus } from '../types';
import { sourceRegistry } from '../services/source/SourceRegistry';
import { mergeChapters } from '../utils/mergeChapters';

export const useAddStory = () => {
    const router = useRouter();
    const { showAlert } = useAppAlert();
    const [url, setUrl] = useState('');
    const [loading, setLoading] = useState(false);
    const [statusMessage, setStatusMessage] = useState('');

    const handlePaste = async () => {
        const text = await Clipboard.getStringAsync();
        if (text) {
            setUrl(text);
        }
    };

    const handleAdd = async () => {
        if (!url) return;
        setLoading(true);
        setStatusMessage('Initializing...');
        try {
            console.log('[AddStory] Processing:', url);
            const provider = sourceRegistry.getProvider(url);

            if (!provider) {
                showAlert('Error', 'Unsupported source URL.');
                setLoading(false);
                return;
            }

            console.log('[AddStory] Fetching with provider:', provider.name);
            setStatusMessage(`Fetching from ${provider.name}...`);
            const html = await fetchPage(url);

            const metadata = provider.parseMetadata(html);

            setStatusMessage('Parsing chapters...');
            const chapters = await provider.getChapterList(html, url, (msg) => {
                setStatusMessage(msg);
            });

            if (chapters.length === 0) {
                showAlert('Error', 'No chapters found. Please check the URL.');
                setLoading(false);
                return;
            }

            setStatusMessage('Saving story...');
            const storyId = provider.getStoryId(url);
            const existingStory = await storageService.getStory(storyId);
            const canonicalUrl = metadata.canonicalUrl ?? url;

            const mergeResult = mergeChapters(
                existingStory?.chapters ?? [],
                chapters,
                provider,
                existingStory?.lastReadChapterId
            );
            const existingPending = existingStory?.pendingNewChapterIds ?? [];
            const pendingSet = new Set([...existingPending, ...mergeResult.newChapterIds]);
            const chapterMap = new Map(mergeResult.chapters.map(ch => [ch.id, ch]));
            const pendingNewChapterIds = Array.from(pendingSet)
                .filter(id => {
                    const chapter = chapterMap.get(id);
                    return chapter && !chapter.downloaded;
                });

            const status = existingStory
                ? (mergeResult.newChaptersCount > 0 ? DownloadStatus.Partial : existingStory.status)
                : DownloadStatus.Idle;

            const story: Story = {
                id: storyId,
                title: metadata.title,
                author: metadata.author,
                coverUrl: metadata.coverUrl,
                description: metadata.description,
                tags: metadata.tags,
                score: metadata.score,
                sourceUrl: canonicalUrl,
                status,
                totalChapters: mergeResult.chapters.length,
                downloadedChapters: mergeResult.downloadedCount,
                chapters: mergeResult.chapters,
                lastUpdated: Date.now()
            };

            if (existingStory) {
                story.lastReadChapterId = mergeResult.lastReadChapterId;
                story.epubPath = existingStory.epubPath;
                story.epubPaths = existingStory.epubPaths;
                story.epubStale = existingStory.epubStale;
                story.pendingNewChapterIds = pendingNewChapterIds.length > 0 ? pendingNewChapterIds : undefined;
            }

            await storageService.addStory(story);
            setLoading(false);
            setStatusMessage('');
            showAlert('Success', `Added "${metadata.title}" to library.`, [
                { text: 'OK', onPress: () => router.back() }
            ]);

        } catch (e) {
            console.error(e);
            showAlert('Error', 'Failed to fetch the novel. ' + (e as Error).message);
            setLoading(false);
            setStatusMessage('');
        }
    };

    return {
        url,
        setUrl,
        loading,
        statusMessage,
        handlePaste,
        handleAdd,
    };
};
