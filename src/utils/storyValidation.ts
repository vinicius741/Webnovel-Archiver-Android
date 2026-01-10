import { Story, Chapter } from '../types';

export const validateStory = (story: Story | null): story is Story => {
    return story !== null && typeof story.id === 'string' && story.id.length > 0;
};

export const validateChapter = (story: Story, chapter: Chapter): boolean => {
    return story.chapters.some(c => c.id === chapter.id);
};

export const validateDownloadRange = (start: number, end: number, totalChapters: number): { valid: boolean; error?: string } => {
    if (start < 1 || end > totalChapters) {
        return { valid: false, error: 'Range exceeds available chapters' };
    }
    if (start > end) {
        return { valid: false, error: 'Start chapter must be before end chapter' };
    }
    return { valid: true };
};

export const hasAllChaptersDownloaded = (story: Story): boolean => {
    return story.downloadedChapters === story.totalChapters;
};

export const hasUpdatesAvailable = (story: Story, newChaptersCount: number, tagsChanged: boolean): boolean => {
    return newChaptersCount > 0 || tagsChanged;
};