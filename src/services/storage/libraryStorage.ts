import AsyncStorage from "@react-native-async-storage/async-storage";
import type { ArchiveReason, DownloadStatus, Story } from "../../types";
import { STORAGE_KEYS, STORY_KEY_PREFIX, storyKey } from "./storageKeys";
import * as fileSystem from "./fileSystem";

export class LibraryStorage {
  private lock: Promise<void> = Promise.resolve();
  private migrationDone = false;

  private async withLock<T>(operation: () => Promise<T>): Promise<T> {
    const currentLock = this.lock;
    let releaseLock: () => void;
    this.lock = new Promise((resolve) => {
      releaseLock = resolve;
    });

    await currentLock;
    try {
      return await operation();
    } finally {
      releaseLock!();
    }
  }

  private generateArchiveStoryId(storyId: string, archivedAt: number): string {
    const randomSuffix = Math.random().toString(36).slice(2, 8);
    return `${storyId}__archive_${archivedAt}_${randomSuffix}`;
  }

  // ── Migration ──────────────────────────────────────────────────────

  private parseLegacyLibrary(json: string | null): Story[] | null {
    if (json === null) return null;

    try {
      const library = JSON.parse(json);
      return Array.isArray(library) ? library : null;
    } catch {
      return null;
    }
  }

  private async persistStories(stories: Story[]): Promise<string[]> {
    const storyIds: string[] = [];
    const writes: [string, string][] = [];

    for (const story of stories) {
      if (story && typeof story.id === "string") {
        storyIds.push(story.id);
        writes.push([storyKey(story.id), JSON.stringify(story)]);
      }
    }

    await Promise.all(
      writes.map(([key, value]) => AsyncStorage.setItem(key, value)),
    );

    return storyIds;
  }

  private async recoverIndexFromStoryKeys(): Promise<string[]> {
    try {
      const keys = await AsyncStorage.getAllKeys();
      if (!Array.isArray(keys)) return [];

      const storyIds = keys
        .filter((key) => key.startsWith(STORY_KEY_PREFIX))
        .map((key) => key.slice(STORY_KEY_PREFIX.length));

      if (storyIds.length > 0) {
        await this.saveIndex(storyIds);
        console.warn(
          `[LibraryStorage] Rebuilt missing library index from ${storyIds.length} story keys.`,
        );
      }

      return storyIds;
    } catch (e) {
      console.error("[LibraryStorage] Failed to rebuild library index", e);
      return [];
    }
  }

  /**
   * Migrates the legacy monolithic `wa_library_v1` blob into per-story keys
   * + an index. It intentionally keeps the legacy blob as a recovery source
   * until a later cleanup migration can prove users have upgraded safely.
   */
  private async migrateIfNeeded(): Promise<void> {
    if (this.migrationDone) return;

    const indexExists = await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY_INDEX);
    if (indexExists !== null) {
      const migrationComplete = await AsyncStorage.getItem(
        STORAGE_KEYS.LIBRARY_MIGRATION_COMPLETE,
      );
      const currentIndex = this.parseIndex(indexExists) ?? [];
      const legacyLibrary = migrationComplete
        ? null
        : this.parseLegacyLibrary(
          await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY_LEGACY),
        );

      if (
        legacyLibrary &&
        legacyLibrary.length > 0 &&
        currentIndex.length < legacyLibrary.length
      ) {
        const storyIds = await this.persistStories(legacyLibrary);
        await this.saveIndex(storyIds);
        console.warn(
          `[LibraryStorage] Recovered ${storyIds.length} stories from legacy library storage.`,
        );
        await AsyncStorage.setItem(
          STORAGE_KEYS.LIBRARY_MIGRATION_COMPLETE,
          "true",
        );
      }
      this.migrationDone = true;
      return;
    }

    // Load legacy blob
    const library = this.parseLegacyLibrary(
      await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY_LEGACY),
    );
    if (library === null) {
      // No legacy data at all — nothing to migrate.
      this.migrationDone = true;
      return;
    }

    try {
      // 1. Write all individual story keys first (parallel is safe — independent)
      const storyIds = await this.persistStories(library);

      // 2. Only persist the index after all story writes succeed
      await this.saveIndex(storyIds);
      await AsyncStorage.setItem(
        STORAGE_KEYS.LIBRARY_MIGRATION_COMPLETE,
        "true",
      );

      this.migrationDone = true;
      console.log(
        `[LibraryStorage] Migrated ${storyIds.length} stories to per-story storage.`,
      );
    } catch (e) {
      console.error("[LibraryStorage] Migration failed", e);
      // migrationDone stays false → will retry on next access
    }
  }

  // ── Index helpers ──────────────────────────────────────────────────

  private parseIndex(json: string | null): string[] | null {
    if (json === null) return null;

    try {
      const ids = JSON.parse(json);
      return Array.isArray(ids)
        ? ids.filter((id): id is string => typeof id === "string")
        : null;
    } catch {
      return null;
    }
  }

  private async getIndex(): Promise<string[]> {
    const json = await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY_INDEX);
    const ids = this.parseIndex(json);
    return ids ?? this.recoverIndexFromStoryKeys();
  }

  private async saveIndex(ids: string[]): Promise<void> {
    await AsyncStorage.setItem(
      STORAGE_KEYS.LIBRARY_INDEX,
      JSON.stringify(ids),
    );
  }

  // ── Public API ─────────────────────────────────────────────────────

  async getLibrary(): Promise<Story[]> {
    await this.migrateIfNeeded();
    const ids = await this.getIndex();
    if (ids.length === 0) return [];

    const results = await Promise.allSettled(
      ids.map((id) => AsyncStorage.getItem(storyKey(id))),
    );

    const stories: Story[] = [];
    for (let i = 0; i < results.length; i++) {
      const result = results[i];
      if (result.status === "fulfilled" && result.value !== null) {
        try {
          stories.push(JSON.parse(result.value));
        } catch {
          // Skip corrupted individual story entry
          console.warn(`[LibraryStorage] Corrupted story entry: ${ids[i]}`);
        }
      } else if (result.status === "rejected") {
        console.warn(
          `[LibraryStorage] Failed to read story ${ids[i]}:`,
          result.reason,
        );
      }
    }
    return stories;
  }

  async saveLibrary(library: Story[]): Promise<void> {
    await this.migrateIfNeeded();
    const ids: string[] = [];
    const writes: Promise<void>[] = [];

    for (const story of library) {
      ids.push(story.id);
      writes.push(
        AsyncStorage.setItem(storyKey(story.id), JSON.stringify(story)),
      );
    }

    await Promise.all([
      this.saveIndex(ids),
      ...writes,
    ]);
  }

  async addStory(story: Story): Promise<void> {
    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const ids = await this.getIndex();
      const index = ids.indexOf(story.id);

      if (index >= 0) {
        // Existing story — preserve dateAdded
        const existingJson = await AsyncStorage.getItem(storyKey(story.id));
        if (existingJson) {
          try {
            const existing = JSON.parse(existingJson) as Story;
            story.dateAdded = existing.dateAdded || story.dateAdded;
          } catch {
            // Use whatever dateAdded is on the incoming story
          }
        }
      } else {
        story.dateAdded = Date.now();
        ids.push(story.id);
        await this.saveIndex(ids);
      }

      await AsyncStorage.setItem(storyKey(story.id), JSON.stringify(story));
    });
  }

  async getStory(id: string): Promise<Story | undefined> {
    await this.migrateIfNeeded();
    const json = await AsyncStorage.getItem(storyKey(id));
    if (json === null) return undefined;
    try {
      return JSON.parse(json) as Story;
    } catch {
      return undefined;
    }
  }

  async updateStory(story: Story): Promise<void> {
    await this.addStory(story);
  }

  async createArchivedStorySnapshot(
    story: Story,
    reason: ArchiveReason,
  ): Promise<Story> {
    const archivedAt = Date.now();
    const archiveId = this.generateArchiveStoryId(story.id, archivedAt);

    const archivedChapters = await Promise.all(
      story.chapters.map(async (chapter, index) => {
        if (chapter.downloaded && chapter.filePath) {
          const archivedFilePath = await fileSystem.copyChapterToNovel(
            chapter.filePath,
            archiveId,
            index,
            chapter.title,
          );
          return {
            ...chapter,
            filePath: archivedFilePath,
          };
        }

        return {
          ...chapter,
          filePath: chapter.downloaded ? chapter.filePath : undefined,
        };
      }),
    );

    const archivedStory: Story = {
      ...story,
      id: archiveId,
      chapters: archivedChapters,
      isArchived: true,
      archiveOfStoryId: story.id,
      archivedAt,
      archiveReason: reason,
      pendingNewChapterIds: undefined,
      epubPath: undefined,
      epubPaths: undefined,
      epubStale: undefined,
      lastUpdated: archivedAt,
    };

    await this.addStory(archivedStory);
    return archivedStory;
  }

  async deleteStory(id: string): Promise<void> {
    try {
      await fileSystem.deleteNovel(id);
    } catch (e) {
      console.warn("Failed to delete files for story", id, e);
    }

    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const ids = await this.getIndex();
      const newIds = ids.filter((sid) => sid !== id);

      await Promise.all([
        this.saveIndex(newIds),
        AsyncStorage.removeItem(storyKey(id)),
      ]);
    });
  }

  async deleteStories(ids: string[]): Promise<void> {
    const idSet = new Set(ids);
    for (const id of ids) {
      try {
        await fileSystem.deleteNovel(id);
      } catch (e) {
        console.warn("Failed to delete files for story", id, e);
      }
    }

    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const currentIds = await this.getIndex();
      const newIds = currentIds.filter((sid) => !idSet.has(sid));

      await Promise.all([
        this.saveIndex(newIds),
        ...ids.map((id) => AsyncStorage.removeItem(storyKey(id))),
      ]);
    });
  }

  async updateStoryStatus(
    id: string,
    status: DownloadStatus,
  ): Promise<void> {
    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const json = await AsyncStorage.getItem(storyKey(id));
      if (json === null) return;

      let story: Story;
      try {
        story = JSON.parse(json);
      } catch {
        return;
      }

      story.status = status;
      await AsyncStorage.setItem(storyKey(id), JSON.stringify(story));
    });
  }

  async updateLastRead(
    storyId: string,
    chapterId: string,
  ): Promise<void> {
    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const json = await AsyncStorage.getItem(storyKey(storyId));
      if (json === null) return;

      let story: Story;
      try {
        story = JSON.parse(json);
      } catch {
        return;
      }

      story.lastReadChapterId = chapterId;
      story.lastUpdated = Date.now();
      await AsyncStorage.setItem(storyKey(storyId), JSON.stringify(story));
    });
  }

  async moveStoriesToTab(
    storyIds: string[],
    tabId: string | null,
  ): Promise<void> {
    await this.withLock(async () => {
      await this.migrateIfNeeded();
      const updates: Promise<void>[] = [];

      for (const id of storyIds) {
        const json = await AsyncStorage.getItem(storyKey(id));
        if (json === null) continue;

        let story: Story;
        try {
          story = JSON.parse(json);
        } catch {
          continue;
        }

        story.tabId = tabId;
        updates.push(
          AsyncStorage.setItem(storyKey(id), JSON.stringify(story)),
        );
      }

      await Promise.all(updates);
    });
  }
}
