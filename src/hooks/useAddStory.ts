import { useState } from 'react';
import { useRouter } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import { useAppAlert } from '../context/AlertContext';
import { fetchPage } from '../services/network/fetcher';
import { parseMetadata } from '../services/parser/metadata';
import { parseChapterList } from '../services/parser/chapterList';
import { storageService } from '../services/StorageService';
import { Story } from '../types';

export const useAddStory = () => {
    const router = useRouter();
    const { showAlert } = useAppAlert();
    const [url, setUrl] = useState('');
    const [loading, setLoading] = useState(false);

    const handlePaste = async () => {
        const text = await Clipboard.getStringAsync();
        if (text) {
            setUrl(text);
        }
    };

    const handleAdd = async () => {
        if (!url) return;
        setLoading(true);
        try {
            console.log('[AddStory] Fetching:', url);
            const html = await fetchPage(url);

            const metadata = parseMetadata(html);
            const chapters = parseChapterList(html, url);

            if (chapters.length === 0) {
                showAlert('Error', 'No chapters found. Please check the URL.');
                setLoading(false);
                return;
            }

            // Generate a simple ID logic (in real app, use uuid or hash)
            // For RoyalRoad: https://www.royalroad.com/fiction/12345/name -> 12345
            let storyId = 'custom_' + Date.now();
            const rrMatch = url.match(/fiction\/(\d+)/);
            if (rrMatch) {
                storyId = 'rr_' + rrMatch[1];
            }

            const story: Story = {
                id: storyId,
                title: metadata.title,
                author: metadata.author,
                coverUrl: metadata.coverUrl,
                description: metadata.description,
                tags: metadata.tags,
                sourceUrl: url,
                status: 'idle', // Ready to download
                totalChapters: chapters.length,
                downloadedChapters: 0,
                chapters: chapters.map(c => ({
                    id: c.url, // Using URL as ID for chapters for now
                    title: c.title,
                    url: c.url,
                })),
                lastUpdated: Date.now()
            };

            await storageService.addStory(story);
            setLoading(false);
            showAlert('Success', `Added "${metadata.title}" to library.`, [
                { text: 'OK', onPress: () => router.back() }
            ]);

        } catch (e) {
            console.error(e);
            showAlert('Error', 'Failed to fetch the novel. ' + (e as Error).message);
            setLoading(false);
        }
    };

    return {
        url,
        setUrl,
        loading,
        handlePaste,
        handleAdd,
    };
};
