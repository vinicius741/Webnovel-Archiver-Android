import { DownloadStatus, Story } from "../../types";
import { storageService } from "../StorageService";

const FLUSH_THRESHOLD = 3;

interface PendingUpdate {
  chapterIndex: number;
  filePath: string;
}

export class DownloadStoryCache {
  private storyCache = new Map<string, Story>();
  private pendingUpdates = new Map<string, PendingUpdate[]>();
  private storyLocks = new Map<string, Promise<void>>();
  private flushPromise = Promise.resolve();

  async getCachedStory(storyId: string): Promise<Story | undefined> {
    await this.flushPromise;

    if (this.storyCache.has(storyId)) {
      return this.storyCache.get(storyId);
    }
    const story = await storageService.getStory(storyId);
    if (story) {
      this.storyCache.set(storyId, story);
    }
    return story;
  }

  updateCachedStory(storyId: string, story: Story): void {
    this.storyCache.set(storyId, story);
  }

  queuePendingUpdate(
    storyId: string,
    chapterIndex: number,
    filePath: string,
  ): void {
    let updates = this.pendingUpdates.get(storyId);
    if (!updates) {
      updates = [];
      this.pendingUpdates.set(storyId, updates);
    }
    updates.push({ chapterIndex, filePath });
  }

  shouldFlush(storyId: string): boolean {
    const updates = this.pendingUpdates.get(storyId);
    return updates ? updates.length >= FLUSH_THRESHOLD : false;
  }

  async flushStoryToStorage(storyId: string): Promise<void> {
    const updates = this.pendingUpdates.get(storyId);
    if (!updates || updates.length === 0) return;

    const story = this.storyCache.get(storyId);
    if (!story) {
      console.warn(
        `[DownloadStoryCache] No cached story for ${storyId}, skipping ${updates.length} pending updates`,
      );
      return;
    }

    this.pendingUpdates.set(storyId, []);

    const currentPromise = this.flushPromise;
    const nextPromise = currentPromise.then(async () => {
      await this.acquireStoryLock(storyId, async () => {
        const freshStory = await storageService.getStory(storyId);
        if (!freshStory) return;

        const chapters = [...freshStory.chapters];
        for (const update of updates) {
          if (chapters[update.chapterIndex]) {
            chapters[update.chapterIndex] = {
              ...chapters[update.chapterIndex],
              filePath: update.filePath,
              downloaded: true,
            };
          }
        }

        const downloadedCount = chapters.filter((c) => c.downloaded).length;
        const status =
          downloadedCount === chapters.length
            ? DownloadStatus.Completed
            : DownloadStatus.Partial;
        const hasEpub =
          !!(freshStory.epubPaths && freshStory.epubPaths.length > 0) ||
          !!freshStory.epubPath;
        const allPendingIds = new Set(
          updates.map((u) => chapters[u.chapterIndex]?.id).filter(Boolean),
        );
        const pendingNewChapterIds = freshStory.pendingNewChapterIds?.filter(
          (id) => !allPendingIds.has(id),
        );

        await storageService.updateStory({
          ...freshStory,
          chapters,
          downloadedChapters: downloadedCount,
          status,
          lastUpdated: Date.now(),
          epubStale: hasEpub ? true : freshStory.epubStale,
          pendingNewChapterIds:
            pendingNewChapterIds && pendingNewChapterIds.length > 0
              ? pendingNewChapterIds
              : undefined,
        });

        this.storyCache.set(storyId, {
          ...freshStory,
          chapters,
          downloadedChapters: downloadedCount,
          status,
        });
      });
    });
    this.flushPromise = nextPromise;
    await nextPromise;
  }

  async flushAllPending(): Promise<void> {
    const currentPromise = this.flushPromise;
    const nextPromise = currentPromise.then(async () => {
      const storyIds = Array.from(this.pendingUpdates.keys());
      await Promise.all(storyIds.map((id) => this.flushStoryToStorage(id)));
    });
    await nextPromise;
    this.flushPromise = nextPromise;
  }

  clearStoryCache(storyId: string): void {
    this.storyCache.delete(storyId);
    this.pendingUpdates.delete(storyId);
  }

  getFlushPromise(): Promise<void> {
    return this.flushPromise;
  }

  private async acquireStoryLock(storyId: string, action: () => Promise<void>) {
    const previousLock = this.storyLocks.get(storyId) || Promise.resolve();

    const myLock = previousLock
      .catch(() => {})
      .then(async () => {
        try {
          await action();
        } catch (e) {
          console.error(`[DownloadStoryCache] Story lock error for ${storyId}`, e);
        }
      });

    this.storyLocks.set(storyId, myLock);

    return myLock;
  }
}
