import { useState } from 'react';
import { getInfoAsync, getContentUriAsync } from 'expo-file-system/legacy';
import { startActivityAsync } from 'expo-intent-launcher';

import { storageService } from '../services/StorageService';
import { epubGenerator, EpubProgress } from '../services/EpubGenerator';
import { useAppAlert } from '../context/AlertContext';
import { Story } from '../types';
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
}

export const useStoryEPUB = ({ story, onStoryUpdated }: UseStoryEPUBParams) => {
    const { showAlert } = useAppAlert();
    const [generating, setGenerating] = useState(false);
    const [progress, setProgress] = useState<EpubGenerationProgress | null>(null);

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
            const startTime = Date.now();
            setProgress({ current: 0, total: story.chapters.length, percentage: 0, stage: 'Starting...', status: 'Initializing' });

            const uri = await epubGenerator.generateEpub(story, story.chapters, (progressData: EpubProgress) => {
                const elapsed = (Date.now() - startTime) / 1000;
                const remaining = progressData.percentage > 0 ? (elapsed / progressData.percentage) * (100 - progressData.percentage) : 0;
                const timeString = remaining > 60 ? `${Math.ceil(remaining / 60)}m ${Math.ceil(remaining % 60)}s` : `${Math.ceil(remaining)}s`;

                let status = '';
                switch (progressData.stage) {
                    case 'filtering':
                        status = `Filtering chapters: ${progressData.current}/${progressData.total}`;
                        break;
                    case 'processing':
                        status = `Processing: ${progressData.current}/${progressData.total} chapters (${timeString} remaining)`;
                        break;
                    case 'finalizing':
                        status = 'Finalizing EPUB...';
                        break;
                }

                setProgress({
                    current: progressData.current,
                    total: progressData.total,
                    percentage: progressData.percentage,
                    stage: progressData.stage,
                    status
                });
            });

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
            setProgress(null);
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
        progress,
        generateOrRead,
    };
};