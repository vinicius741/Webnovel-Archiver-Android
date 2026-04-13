import { DownloadStatus, Story } from "../../types";
import { MergeChaptersResult, mergeChapters } from "../../utils/mergeChapters";
import { fetchPage } from "../network/fetcher";
import { sourceRegistry } from "../source/SourceRegistry";
import { NovelMetadata, SourceProvider } from "../source/types";

export class UnsupportedSourceError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "UnsupportedSourceError";
  }
}

export class EmptyChapterListError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "EmptyChapterListError";
  }
}

export interface PreparedStorySyncData {
  provider: SourceProvider;
  storyId: string;
  canonicalUrl: string;
  metadata: NovelMetadata;
  existingStory?: Story;
  mergeResult: MergeChaptersResult;
  pendingNewChapterIds?: string[];
}

interface PrepareStorySyncOptions {
  sourceUrl: string;
  existingStory?: Story;
  loadExistingStory?: (storyId: string) => Promise<Story | undefined>;
  onStatus?: (message: string) => void;
  onProgress?: (message: string) => void;
}

interface BuildStoryForSyncParams {
  currentStory: Story;
  prepared: PreparedStorySyncData;
  updatedEpubConfig: Story["epubConfig"];
}

export const buildPendingNewChapterIds = (
  existingPending: string[] | undefined,
  chapterIdsToAdd: string[],
  mergedChapters: Story["chapters"],
): string[] | undefined => {
  const pendingSet = new Set([...(existingPending ?? []), ...chapterIdsToAdd]);
  const chapterMap = new Map(mergedChapters.map((chapter) => [chapter.id, chapter]));
  const pendingIds = Array.from(pendingSet).filter((id) => {
    const chapter = chapterMap.get(id);
    return chapter && !chapter.downloaded;
  });

  return pendingIds.length > 0 ? pendingIds : undefined;
};

export const prepareStorySyncData = async ({
  sourceUrl,
  existingStory,
  loadExistingStory,
  onStatus,
  onProgress,
}: PrepareStorySyncOptions): Promise<PreparedStorySyncData> => {
  const provider = sourceRegistry.getProvider(sourceUrl);
  if (!provider) {
    throw new UnsupportedSourceError("Unsupported source URL.");
  }

  let storyId = existingStory?.id;
  if (!storyId) {
    storyId = provider.getStoryId(sourceUrl);
  }
  const resolvedExistingStory = existingStory ??
    (loadExistingStory ? await loadExistingStory(storyId) : undefined);

  onStatus?.(`Fetching from ${provider.name}...`);
  const html = await fetchPage(sourceUrl);
  const metadata = provider.parseMetadata(html);

  onStatus?.("Parsing chapters...");
  const chapters = await provider.getChapterList(html, sourceUrl, onProgress);
  if (chapters.length === 0) {
    throw new EmptyChapterListError(
      "Source returned no chapters. Sync canceled to avoid overwriting this story.",
    );
  }

  const mergeResult = mergeChapters(
    resolvedExistingStory?.chapters ?? [],
    chapters,
    provider,
    resolvedExistingStory?.lastReadChapterId,
  );

  const pendingNewChapterIds = buildPendingNewChapterIds(
    resolvedExistingStory?.pendingNewChapterIds,
    mergeResult.newChapterIds,
    mergeResult.chapters,
  );

  return {
    provider,
    storyId,
    canonicalUrl: metadata.canonicalUrl ?? sourceUrl,
    metadata,
    existingStory: resolvedExistingStory,
    mergeResult,
    pendingNewChapterIds,
  };
};

export const buildStoryForAdd = (
  prepared: PreparedStorySyncData,
  tabId?: string,
): Story => {
  const existingStory = prepared.existingStory;
  const { metadata, mergeResult } = prepared;

  const status = existingStory
    ? mergeResult.newChaptersCount > 0
      ? DownloadStatus.Partial
      : existingStory.status
    : DownloadStatus.Idle;

  const story: Story = {
    id: prepared.storyId,
    title: metadata.title,
    author: metadata.author,
    coverUrl: metadata.coverUrl || existingStory?.coverUrl || undefined,
    description: metadata.description,
    tags: metadata.tags,
    score: metadata.score,
    sourceUrl: prepared.canonicalUrl,
    status,
    totalChapters: mergeResult.chapters.length,
    downloadedChapters: mergeResult.downloadedCount,
    chapters: mergeResult.chapters,
    lastUpdated: Date.now(),
    tabId: tabId ?? undefined,
  };

  if (!existingStory) {
    return story;
  }

  story.lastReadChapterId = mergeResult.lastReadChapterId;
  story.epubPath = existingStory.epubPath;
  story.epubPaths = existingStory.epubPaths;
  story.epubStale = existingStory.epubStale;
  story.pendingNewChapterIds = prepared.pendingNewChapterIds;

  return story;
};

export const buildStoryForSync = ({
  currentStory,
  prepared,
  updatedEpubConfig,
}: BuildStoryForSyncParams): Story => {
  const { mergeResult, metadata } = prepared;
  const chapterListChanged = currentStory.chapters.length !== mergeResult.chapters.length;
  const hasEpub =
    !!currentStory.epubPath ||
    !!(currentStory.epubPaths && currentStory.epubPaths.length > 0);

  return {
    ...currentStory,
    chapters: mergeResult.chapters,
    totalChapters: mergeResult.chapters.length,
    downloadedChapters: mergeResult.downloadedCount,
    status:
      mergeResult.newChapterIds.length > 0
        ? DownloadStatus.Partial
        : currentStory.status,
    lastUpdated: Date.now(),
    tags: metadata.tags,
    title: metadata.title || currentStory.title,
    author: metadata.author || currentStory.author,
    coverUrl: metadata.coverUrl || currentStory.coverUrl || undefined,
    description: metadata.description || currentStory.description,
    score: metadata.score || currentStory.score,
    sourceUrl: prepared.canonicalUrl,
    lastReadChapterId: mergeResult.lastReadChapterId,
    pendingNewChapterIds: prepared.pendingNewChapterIds,
    epubConfig: updatedEpubConfig,
    epubStale: chapterListChanged && hasEpub ? true : currentStory.epubStale,
    isArchived: false,
    archiveOfStoryId: undefined,
    archivedAt: undefined,
    archiveReason: undefined,
  };
};
