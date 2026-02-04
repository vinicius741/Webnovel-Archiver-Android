import { Chapter } from '../types';
import { ChapterInfo, SourceProvider } from '../services/source/types';
import { sanitizeTitle } from './stringUtils';

export interface MergeChaptersResult {
    chapters: Chapter[];
    downloadedCount: number;
    newChaptersCount: number;
    lastReadChapterId?: string;
}

const buildStableId = (
    provider: SourceProvider | undefined,
    url?: string,
    fallbackId?: string
): string | undefined => {
    if (url && provider?.getChapterId) {
        const providerId = provider.getChapterId(url);
        if (providerId) return providerId;
    }
    return fallbackId ?? url;
};

export const mergeChapters = (
    existingChapters: Chapter[],
    newChapters: ChapterInfo[],
    provider?: SourceProvider,
    lastReadChapterId?: string
): MergeChaptersResult => {
    const existingById = new Map<string, Chapter>();
    const aliasToStable = new Map<string, string>();

    existingChapters.forEach((chapter) => {
        const stableId = buildStableId(provider, chapter.url, chapter.id);
        if (!stableId) return;

        if (!existingById.has(stableId)) {
            existingById.set(stableId, chapter);
        }

        if (chapter.id) aliasToStable.set(chapter.id, stableId);
        if (chapter.url) aliasToStable.set(chapter.url, stableId);
    });

    let newChaptersCount = 0;

    const mergedChapters: Chapter[] = newChapters.map((chapter) => {
        const stableId = buildStableId(provider, chapter.url, chapter.id) ?? chapter.url;
        const title = sanitizeTitle(chapter.title);

        const existing = stableId ? existingById.get(stableId) : undefined;
        if (existing) {
            return {
                ...existing,
                id: stableId,
                title,
                url: chapter.url,
            };
        }

        newChaptersCount++;
        return {
            id: stableId,
            title,
            url: chapter.url,
            downloaded: false,
        };
    });

    let remappedLastRead = lastReadChapterId;
    if (remappedLastRead && aliasToStable.has(remappedLastRead)) {
        remappedLastRead = aliasToStable.get(remappedLastRead);
    }

    const downloadedCount = mergedChapters.filter((chapter) => chapter.downloaded).length;

    return {
        chapters: mergedChapters,
        downloadedCount,
        newChaptersCount,
        lastReadChapterId: remappedLastRead,
    };
};
