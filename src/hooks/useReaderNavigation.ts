import { useRouter } from 'expo-router';
import { useMemo } from 'react';
import { Story, Chapter } from '../types';

export const useReaderNavigation = (
    story: Story | null,
    chapter: Chapter | null,
    currentIndex?: number
) => {
    const router = useRouter();

    const calculatedIndex = useMemo(() => {
        if (typeof currentIndex !== 'undefined') return currentIndex;
        if (!story || !chapter) return -1;
        return story.chapters.findIndex((c: Chapter) => c.id === chapter.id);
    }, [story, chapter, currentIndex]);

    const hasNext = calculatedIndex < (story?.chapters.length || 0) - 1;
    const hasPrevious = calculatedIndex > 0;

    const navigateToChapter = (index: number, params?: { autoplay?: string }) => {
        if (!story) return;
        if (index < 0 || index >= story.chapters.length) return;
        const target = story.chapters[index];
        router.setParams({ 
            chapterId: encodeURIComponent(target.id),
            ...(params || {})
        });
    };

    return {
        currentIndex: calculatedIndex,
        hasNext,
        hasPrevious,
        navigateToChapter,
    };
};
