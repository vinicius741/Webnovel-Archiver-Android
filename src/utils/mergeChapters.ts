import { Chapter } from '../types';
import { ChapterInfo, SourceProvider } from '../services/source/types';
import { sanitizeTitle } from './stringUtils';

export interface MergeChaptersResult {
    chapters: Chapter[];
    downloadedCount: number;
    newChaptersCount: number;
    newChapterIds: string[];
    lastReadChapterId?: string;
}

/**
 * Builds a stable chapter ID using a priority strategy.
 *
 * Priority order (highest to lowest):
 * 1. Provider ID - The ID returned by the provider's getChapterId() method.
 *    This is the most stable ID as it's derived from the source website's
 *    canonical chapter identifier.
 * 2. Fallback ID - An existing chapter ID from storage. Used when the provider
 *    doesn't implement getChapterId() or returns undefined. Maintains continuity
 *    for existing chapters when provider behavior changes.
 * 3. URL - The chapter URL as the last resort. Provides uniqueness but is less
 *    stable as URLs may change.
 *
 * @param provider - The source provider (optional, may not have getChapterId)
 * @param url - The chapter URL (optional)
 * @param fallbackId - An existing ID to use as fallback (optional)
 * @returns A stable chapter ID, or undefined if no ID can be determined
 */
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

/**
 * Merges new chapter info with existing chapters, preserving download status
 * and remapping IDs to stable identifiers.
 *
 * This function:
 * 1. Builds stable IDs for existing chapters using buildStableId()
 * 2. Creates an alias map to remap old IDs to new stable IDs
 * 3. Merges new chapters, preserving downloaded status for existing chapters
 * 4. Remaps lastReadChapterId to the new stable ID if applicable
 *
 * @param existingChapters - Chapters currently in storage
 * @param newChapters - Fresh chapter info from the source website
 * @param provider - Optional source provider for ID generation
 * @param lastReadChapterId - Optional last read chapter ID to remap
 * @returns Merged chapters with counts and remapped last read ID
 */
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
    const newChapterIds: string[] = [];

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
        newChapterIds.push(stableId);
        return {
            id: stableId,
            title,
            url: chapter.url,
            downloaded: false,
        };
    });

    let remappedLastRead = lastReadChapterId;
    if (remappedLastRead && aliasToStable.has(remappedLastRead)) {
        remappedLastRead = aliasToStable.get(remappedLastRead)!;
    }

    const downloadedCount = mergedChapters.filter((chapter) => chapter.downloaded).length;

    return {
        chapters: mergedChapters,
        downloadedCount,
        newChaptersCount,
        newChapterIds,
        lastReadChapterId: remappedLastRead,
    };
};
