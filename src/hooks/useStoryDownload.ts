import { useState } from "react";
import { activateKeepAwakeAsync, deactivateKeepAwake } from "expo-keep-awake";

import { storageService } from "../services/StorageService";
import { downloadService } from "../services/DownloadService";
import { useAppAlert } from "../context/AlertContext";
import { Story } from "../types";
import { validateStory, validateDownloadRange } from "../utils/storyValidation";
import {
  buildStoryForSync,
  EmptyChapterListError,
  prepareStorySyncData,
} from "../services/story/storySyncOrchestrator";

interface UseStoryDownloadParams {
  story: Story | null;
  onStoryUpdated: (updatedStory: Story) => void;
}

export const useStoryDownload = ({
  story,
  onStoryUpdated,
}: UseStoryDownloadParams) => {
  const { showAlert } = useAppAlert();
  const [syncing, setSyncing] = useState(false);
  const [syncStatus, setSyncStatus] = useState("");
  const [queueing, setQueueing] = useState(false);

  const showArchivedStoryAlert = (action: string) => {
    showAlert(
      "Archived Snapshot",
      `${action} is disabled for archived snapshots. Use the active story entry instead.`,
    );
  };

  const buildUpdatedEpubConfig = (
    currentStory: Story,
    nextChapterCount: number,
  ) => {
    if (!currentStory.epubConfig) return currentStory.epubConfig;

    const oldTotal = currentStory.chapters.length;
    const hasNewChapters = nextChapterCount > oldTotal;
    const wasAtEnd = currentStory.epubConfig.rangeEnd >= oldTotal;
    const nextRangeStart = Math.min(
      Math.max(currentStory.epubConfig.rangeStart, 1),
      nextChapterCount,
    );
    let nextRangeEnd = currentStory.epubConfig.rangeEnd;

    if (hasNewChapters && wasAtEnd) {
      nextRangeEnd = nextChapterCount;
    }

    nextRangeEnd = Math.min(Math.max(nextRangeEnd, nextRangeStart), nextChapterCount);

    return {
      ...currentStory.epubConfig,
      rangeStart: nextRangeStart,
      rangeEnd: nextRangeEnd,
    };
  };

  const confirmArchiveAndUpdate = (
    oldChapterCount: number,
    newChapterCount: number,
    removedChapterCount: number,
    queuedNewChapterCount: number,
  ): Promise<boolean> => {
    return new Promise((resolve) => {
      const newChapterLine =
        queuedNewChapterCount > 0
          ? `\n\n${queuedNewChapterCount} brand new chapter${queuedNewChapterCount === 1 ? "" : "s"} will be queued after the update.`
          : "";

      showAlert(
        "Source Chapters Removed",
        `This story used to list ${oldChapterCount} chapters, but the source now lists ${newChapterCount}. ${removedChapterCount} saved chapter${removedChapterCount === 1 ? "" : "s"} would disappear from the active story.${newChapterLine}\n\nArchive the current version before updating?`,
        [
          {
            text: "Cancel",
            style: "cancel",
            onPress: () => resolve(false),
          },
          {
            text: "Archive & Update",
            onPress: () => resolve(true),
          },
        ],
      );
    });
  };

  const downloadAll = async () => {
    if (story?.isArchived) {
      showArchivedStoryAlert("Downloading");
      return;
    }
    if (!validateStory(story)) return;

    try {
      setQueueing(true);
      await activateKeepAwakeAsync();

      const updatedStory = await downloadService.downloadAllChapters(story);
      await storageService.addStory(updatedStory);
      onStoryUpdated(updatedStory);
      showAlert(
        "Download Started",
        "Chapters have been added to the download queue.",
      );
    } catch (error) {
      console.error("Download error", error);
      showAlert("Download Error", "Failed to download chapters. Check logs.");
    } finally {
      setQueueing(false);
      await deactivateKeepAwake();
    }
  };

  const syncChapters = async () => {
    if (story?.isArchived) {
      showArchivedStoryAlert("Sync");
      return;
    }
    if (!validateStory(story)) return;

    try {
      setSyncing(true);
      setSyncStatus("Initializing...");
      const prepared = await prepareStorySyncData({
        sourceUrl: story.sourceUrl,
        existingStory: story,
        onStatus: setSyncStatus,
        onProgress: setSyncStatus,
      });
      setSyncStatus("Merging...");
      const { mergeResult, metadata } = prepared;

      const tagsChanged =
        JSON.stringify(story.tags) !== JSON.stringify(metadata.tags);
      const oldTotal = story.chapters.length;
      const newTotal = mergeResult.chapters.length;
      const newChapterCount = mergeResult.newChapterIds.length;
      const removedChapterCount = mergeResult.removedChapterIds.length;
      const hasUpdates =
        newChapterCount > 0 || tagsChanged || removedChapterCount > 0;

      if (removedChapterCount > 0) {
        const missingDownloads = mergeResult.removedChapters.filter(
          (chapter) => !chapter.downloaded,
        );

        if (missingDownloads.length > 0) {
          showAlert(
            "Sync Canceled",
            `The source shrank from ${oldTotal} chapters to ${newTotal}. ${missingDownloads.length} removed chapter${missingDownloads.length === 1 ? "" : "s"} are not downloaded, so the update was canceled to avoid data loss.`,
          );
          return;
        }

        const shouldArchive = await confirmArchiveAndUpdate(
          oldTotal,
          newTotal,
          removedChapterCount,
          newChapterCount,
        );

        if (!shouldArchive) {
          return;
        }

        setSyncStatus("Archiving snapshot...");
        await storageService.createArchivedStorySnapshot(
          story,
          "source_chapters_removed",
        );
      }

      const updatedEpubConfig = buildUpdatedEpubConfig(story, newTotal);
      const updatedStory = buildStoryForSync({
        currentStory: story,
        prepared,
        updatedEpubConfig,
      });

      await storageService.addStory(updatedStory);
      onStoryUpdated(updatedStory);

      if (hasUpdates) {
        if (newChapterCount > 0) {
          setQueueing(true);
          const queuedStory = await downloadService.downloadChaptersByIds(
            updatedStory,
            mergeResult.newChapterIds,
          );
          await storageService.addStory(queuedStory);
          onStoryUpdated(queuedStory);
          showAlert(
            "Update Found",
            removedChapterCount > 0
              ? `Archived the previous version, updated the active story, and queued ${newChapterCount} new chapter${newChapterCount === 1 ? "" : "s"}.`
              : `Found ${newChapterCount} new chapters. Download queued.`,
          );
        } else if (removedChapterCount > 0) {
          showAlert(
            "Archive Created",
            `Archived the previous version and updated the active story from ${oldTotal} chapters to ${newTotal}.`,
          );
        } else if (tagsChanged) {
          showAlert("Metadata Updated", "Tags and details updated.");
        }
      } else {
        showAlert(
          "No Updates",
          "No new chapters found. Updated last checked time.",
        );
      }
    } catch (error: unknown) {
      if (error instanceof EmptyChapterListError) {
        console.warn("Sync canceled", error.message);
      } else {
        console.error("Update error", error);
      }
      const message = error instanceof Error
        ? error.message
        : "Failed to sync chapters. Check logs.";
      showAlert(
        error instanceof EmptyChapterListError ? "Sync Canceled" : "Sync Error",
        message,
      );
    } finally {
      setQueueing(false);
      setSyncing(false);
      setSyncStatus("");
    }
  };

  // `startChapter`/`endChapter` are 1-based inclusive values from UI inputs.
  const downloadRange = async (startChapter: number, endChapter: number) => {
    if (story?.isArchived) {
      showArchivedStoryAlert("Downloading");
      return;
    }
    if (!validateStory(story)) return;

    const validation = validateDownloadRange(
      startChapter,
      endChapter,
      story.totalChapters,
    );
    if (!validation.valid) {
      showAlert(
        "Invalid Range",
        validation.error || "Please enter a valid range of chapters.",
      );
      return;
    }

    const startIndex = startChapter - 1;
    const endIndex = endChapter - 1;

    try {
      setQueueing(true);
      await activateKeepAwakeAsync();

      const updatedStory = await downloadService.downloadRange(
        story,
        startIndex,
        endIndex,
      );
      await storageService.addStory(updatedStory);
      onStoryUpdated(updatedStory);
      showAlert("Download Started", "Selected chapters have been queued.");
    } catch (error) {
      console.error("Download error", error);
      showAlert("Download Error", "Failed to download chapters. Check logs.");
    } finally {
      setQueueing(false);
      await deactivateKeepAwake();
    }
  };

  const applySentenceRemoval = async () => {
    if (story?.isArchived) {
      showArchivedStoryAlert("Text cleanup");
      return;
    }
    if (!story) return;

    const downloadedChapters = story.chapters.filter((c) => c.downloaded);
    if (downloadedChapters.length === 0) {
      showAlert("No Chapters", "No downloaded chapters to process.");
      return;
    }

    showAlert(
      "Apply Text Cleanup",
      `This will apply sentence removal and regex cleanup rules to ${downloadedChapters.length} downloaded chapters. The EPUB will need to be regenerated after.`,
      [
        { text: "Cancel", style: "cancel" },
        {
          text: "Apply",
          onPress: async () => {
            try {
              setSyncing(true);
              setSyncStatus("Processing...");

              const { processed, errors } =
                await downloadService.applySentenceRemovalToStory(
                  story,
                  (current, total, title) => {
                    setSyncStatus(`Processing ${current}/${total}: ${title}`);
                  },
                );

              setSyncing(false);
              setSyncStatus("");

              if (errors > 0) {
                showAlert(
                  "Processing Complete with Errors",
                  `Processed ${processed} chapters, ${errors} had errors.`,
                );
              } else {
                showAlert(
                  "Processing Complete",
                  `Successfully applied text cleanup to ${processed} chapters. Please regenerate the EPUB.`,
                );
              }

              const reloadedStory = await storageService.getStory(story.id);
              if (reloadedStory) {
                onStoryUpdated(reloadedStory);
              }
            } catch (error: unknown) {
              setSyncing(false);
              setSyncStatus("");
              const message = error instanceof Error ? error.message : "Failed to apply text cleanup.";
              showAlert(
                "Error",
                message,
              );
            }
          },
        },
      ],
    );
  };

  const downloadChaptersByIds = async (chapterIds: string[]) => {
    if (story?.isArchived) {
      showArchivedStoryAlert("Downloading");
      return;
    }
    if (!validateStory(story)) return;

    try {
      setQueueing(true);
      await activateKeepAwakeAsync();

      const updatedStory = await downloadService.downloadChaptersByIds(
        story,
        chapterIds,
      );
      await storageService.addStory(updatedStory);
      onStoryUpdated(updatedStory);
      showAlert("Download Started", `${chapterIds.length} chapters have been queued.`);
    } catch (error) {
      console.error("Download error", error);
      showAlert("Download Error", "Failed to download chapters. Check logs.");
    } finally {
      setQueueing(false);
      await deactivateKeepAwake();
    }
  };

  return {
    syncing,
    syncStatus,
    queueing,
    downloadAll,
    syncChapters,
    downloadRange,
    applySentenceRemoval,
    downloadChaptersByIds,
  };
};
