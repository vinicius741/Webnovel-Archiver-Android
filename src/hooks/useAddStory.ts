import { useState } from 'react';
import { useRouter } from 'expo-router';
import * as Clipboard from 'expo-clipboard';
import { useAppAlert } from '../context/AlertContext';
import { fetchPage } from '../services/network/fetcher';
import { storageService } from '../services/StorageService';
import { Story } from '../types';
import { sourceRegistry } from '../services/source/SourceRegistry';

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

            const story: Story = {
                id: storyId,
                title: metadata.title,
                author: metadata.author,
                coverUrl: metadata.coverUrl,
                description: metadata.description,
                tags: metadata.tags,
                score: metadata.score,
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
