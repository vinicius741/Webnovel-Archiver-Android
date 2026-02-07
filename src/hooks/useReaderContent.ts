import { useState, useMemo, useCallback, useEffect } from 'react';
import { Story, Chapter } from '../types';
import { storageService } from '../services/StorageService';
import { readChapterFile } from '../services/storage/fileSystem';

export const useReaderContent = (storyId: string, chapterId: string) => {
    const [story, setStory] = useState<Story | null>(null);
    const [chapter, setChapter] = useState<Chapter | null>(null);
    const [content, setContent] = useState<string>('');
    const [loading, setLoading] = useState(true);
    const [error, setError] = useState<string | null>(null);
    const [redirectPath, setRedirectPath] = useState<string | null>(null);

    const loadData = useCallback(async () => {
        setLoading(true);
        setError(null);
        setRedirectPath(null);

        const decodeChapterId = (value: string): string => {
            try {
                return decodeURIComponent(value);
            } catch {
                return value;
            }
        };

        try {
            const s = await storageService.getStory(storyId);
            if (s) {
                setStory(s);
                const decodedChapterId = decodeChapterId(chapterId);
                const c = s.chapters.find((chap: Chapter) => chap.id === decodedChapterId)
                    || s.chapters.find((chap: Chapter) => chap.id === chapterId);

                if (c) {
                    setChapter(c);
                    if (c.filePath) {
                        const html = await readChapterFile(c.filePath);
                        setContent(html);
                    } else {
                        setContent('Chapter not downloaded yet.');
                    }
                } else {
                    setError('Chapter not found.');
                    setRedirectPath(`/details/${s.id}`);
                }
            } else {
                setError('Story not found.');
                setRedirectPath('/');
            }
        } catch (e) {
            console.error('Failed to load chapter content', e);
            setError(e instanceof Error ? e.message : 'Failed to load chapter content.');
            setRedirectPath('/');
        } finally {
            setLoading(false);
        }
    }, [storyId, chapterId, storageService, readChapterFile]);

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

    const isLastRead = Boolean(story && chapter && story.lastReadChapterId === chapter.id);

    useEffect(() => {
        loadData();
    }, [loadData]);

    return {
        story,
        chapter,
        content,
        loading,
        error,
        redirectPath,
        loadData,
        markAsRead,
        currentIndex,
        isLastRead,
    };
};
