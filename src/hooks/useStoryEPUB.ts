import { useState } from 'react';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';

import { storageService } from '../services/StorageService';
import { epubGenerator } from '../services/EpubGenerator';
import { useAppAlert } from '../context/AlertContext';
import { Story } from '../types';
import { validateStory } from '../utils/storyValidation';

interface UseStoryEPUBParams {
    story: Story | null;
    onStoryUpdated: (updatedStory: Story) => void;
}

export const useStoryEPUB = ({ story, onStoryUpdated }: UseStoryEPUBParams) => {
    const { showAlert } = useAppAlert();
    const [generating, setGenerating] = useState(false);

    const readExistingEpub = async (epubPath: string): Promise<boolean> => {
        if (!validateStory(story)) return false;

        try {
            const fileInfo = await getInfoAsync(epubPath);
            if (!fileInfo.exists) {
                const updated: Story = { ...story, epubPath: undefined };
                await storageService.addStory(updated);
                onStoryUpdated(updated);
                showAlert('Error', 'EPUB file not found. Please regenerate.');
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

    const generateEpub = async (): Promise<boolean> => {
        if (!validateStory(story)) return false;

        try {
            setGenerating(true);
            const uri = await epubGenerator.generateEpub(story, story.chapters);

            const updatedStory: Story = { ...story, epubPath: uri };
            await storageService.addStory(updatedStory);
            onStoryUpdated(updatedStory);

            showAlert('Success', `EPUB exported to: ${uri}`);
            return true;
        } catch (error: any) {
            showAlert('Error', error.message);
            return false;
        } finally {
            setGenerating(false);
        }
    };

    const generateOrRead = async (): Promise<void> => {
        if (!validateStory(story)) return;

        if (story.epubPath) {
            await readExistingEpub(story.epubPath);
        } else {
            await generateEpub();
        }
    };

    return {
        generating,
        generateOrRead,
    };
};