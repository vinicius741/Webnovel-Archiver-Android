import AsyncStorage from "@react-native-async-storage/async-storage";
import type { ArchiveReason, DownloadStatus, Story } from "../../types";
import { STORAGE_KEYS } from "./storageKeys";
import * as fileSystem from "./fileSystem";

export class LibraryStorage {
  private generateArchiveStoryId(storyId: string, archivedAt: number): string {
    const randomSuffix = Math.random().toString(36).slice(2, 8);
    return `${storyId}__archive_${archivedAt}_${randomSuffix}`;
  }

  async getLibrary(): Promise<Story[]> {
    try {
      const jsonValue = await AsyncStorage.getItem(STORAGE_KEYS.LIBRARY);
      return jsonValue != null ? JSON.parse(jsonValue) : [];
    } catch (e) {
      console.error("Failed to load library", e);
      return [];
    }
  }

  async saveLibrary(library: Story[]): Promise<void> {
    try {
      const jsonValue = JSON.stringify(library);
      await AsyncStorage.setItem(STORAGE_KEYS.LIBRARY, jsonValue);
    } catch (e) {
      console.error("Failed to save library", e);
    }
  }

  async addStory(story: Story): Promise<void> {
    const library = await this.getLibrary();
    const index = library.findIndex((s) => s.id === story.id);
    if (index >= 0) {
      const existing = library[index];
      story.dateAdded = existing.dateAdded || story.dateAdded;
      library[index] = story;
    } else {
      story.dateAdded = Date.now();
      library.push(story);
    }
    await this.saveLibrary(library);
  }

  async getStory(id: string): Promise<Story | undefined> {
    const library = await this.getLibrary();
    return library.find((s) => s.id === id);
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

    const library = await this.getLibrary();
    const newLibrary = library.filter((s) => s.id !== id);
    await this.saveLibrary(newLibrary);
  }

  async updateStoryStatus(id: string, status: DownloadStatus): Promise<void> {
    const library = await this.getLibrary();
    const story = library.find((s) => s.id === id);
    if (story) {
      story.status = status;
      await this.saveLibrary(library);
    }
  }

  async updateLastRead(storyId: string, chapterId: string): Promise<void> {
    const library = await this.getLibrary();
    const story = library.find((s) => s.id === storyId);
    if (story) {
      story.lastReadChapterId = chapterId;
      story.lastUpdated = Date.now();
      await this.saveLibrary(library);
    }
  }

  async moveStoriesToTab(
    storyIds: string[],
    tabId: string | null,
  ): Promise<void> {
    const library = await this.getLibrary();
    storyIds.forEach((id) => {
      const story = library.find((s) => s.id === id);
      if (story) {
        story.tabId = tabId;
      }
    });
    await this.saveLibrary(library);
  }
}
