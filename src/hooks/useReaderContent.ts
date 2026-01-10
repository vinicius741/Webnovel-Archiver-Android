import { useState, useMemo, useCallback } from 'react';
import { Story, Chapter } from '../types';
import { storageService } from '../services/StorageService';
import { readChapterFile } from '../services/storage/fileSystem';
import { removeUnwantedSentences, extractPlainText } from '../utils/htmlUtils';

export const useReaderContent = (storyId: string, chapterId: string) => {
    const [story, setStory] = useState<Story | null>(null);
    const [chapter, setChapter] = useState<Chapter | null>(null);
    const [content, setContent] = useState<string>('');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);

    const loadData = async () => {
        setLoading(true);
        setError(null);
        try {
            const s = await storageService.getStory(storyId);
            if (s) {
                setStory(s);
                const c = s.chapters.find((chap: Chapter) => chap.id === decodeURIComponent(chapterId));
                if (c) {
                    setChapter(c);
                    if (c.filePath) {
                        const html = await readChapterFile(c.filePath);
                        const removalList = await storageService.getSentenceRemovalList();
                        const cleanHtml = removeUnwantedSentences(html, removalList);
                        setContent(cleanHtml);
                    } else {
                        setContent('Chapter not downloaded yet.');
                    }
                } else {
                    setError('Chapter not found.');
                }
            } else {
                setError('Story not found.');
            }
        } catch (e) {
            console.error('Failed to load chapter content', e);
            setError(e instanceof Error ? e.message : 'Failed to load chapter content.');
        } finally {
            setLoading(false);
        }
    };

    const markAsRead = useCallback(async () => {
        if (!story || !chapter) return;
        
        await storageService.updateLastRead(story.id, chapter.id);
        const updatedStory = { ...story, lastReadChapterId: chapter.id };
        setStory(updatedStory);
    }, [story, chapter]);

    const currentIndex = useMemo(() => {
        if (!story || !chapter) return -1;
        return story.chapters.findIndex((c: Chapter) => c.id === chapter.id);
    }, [story, chapter]);

    const isLastRead = story?.lastReadChapterId === chapter?.id;

    return {
        story,
        chapter,
        content,
        loading,
        error,
        loadData,
        markAsRead,
        currentIndex,
        isLastRead,
    };
};
