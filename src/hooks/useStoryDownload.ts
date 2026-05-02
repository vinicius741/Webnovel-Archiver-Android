import { useState } from "react";
import { activateKeepAwakeAsync, deactivateKeepAwake } from "expo-keep-awake";

import { storageService } from "../services/StorageService";
import { downloadService } from "../services/DownloadService";
import { useAppAlert } from "../context/AlertContext";
import { Story } from "../types";
import { validateStory, validateDownloadRange } from "../utils/storyValidation";
import { saveAndNotify } from "../utils/saveAndNotify";
import {
  buildStoryForSync,
  EmptyChapterListError,
  prepareStorySyncData,
} from "../services/story/storySyncOrchestrator";

interface UseStoryDownloadParams {
  story: Story | null;
  onStoryUpdated: (updatedStory: Story) => void;
}

const withDownloadGuard = (
  story: Story | null,
  action: string,
  showAlert: (title: string, message: string) => void,
): boolean => {
  if (story?.isArchived) {
    showAlert(
      "Archived Snapshot",
      `${action} is disabled for archived snapshots. Use the active story entry instead.`,
    );
    return false;
  }
  if (!validateStory(story)) return false;

  return true;
};

export const useStoryDownload = ({
  story,
  onStoryUpdated,
}: UseStoryDownloadParams) => {
  const { showAlert } = useAppAlert();
  const [syncing, setSyncing] = useState(false);
  const [syncStatus, setSyncStatus] = useState("");
  const [queueing, setQueueing] = useState(false);

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
    downloadedRemovedChapterCount: number,
    queuedNewChapterCount: number,
  ): Promise<boolean> => {
    return new Promise((resolve) => {
      const undownloadedRemovedChapterCount =
        removedChapterCount - downloadedRemovedChapterCount;
      const downloadedLine =
        `The archive will preserve text for ${downloadedRemovedChapterCount} downloaded removed chapter${downloadedRemovedChapterCount === 1 ? "" : "s"}.`;
      const undownloadedLine =
        undownloadedRemovedChapterCount > 0
          ? ` ${undownloadedRemovedChapterCount} removed chapter${undownloadedRemovedChapterCount === 1 ? " is" : "s are"} not downloaded, so the archive can only keep ${undownloadedRemovedChapterCount === 1 ? "its" : "their"} metadata and URLs, not chapter text.`
          : "";
      const newChapterLine =
        queuedNewChapterCount > 0
          ? `\n\n${queuedNewChapterCount} brand new chapter${queuedNewChapterCount === 1 ? "" : "s"} will be queued after the update.`
          : "";

      showAlert(
        "Source Chapters Removed",
        `This story used to list ${oldChapterCount} chapters, but the source now lists ${newChapterCount}. ${removedChapterCount} saved chapter${removedChapterCount === 1 ? "" : "s"} were removed from the source and would disappear from the active story.\n\n${downloadedLine}${undownloadedLine}${newChapterLine}\n\nArchive the current version before updating?`,
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

  const executeQueuedDownload = async (
    getUpdatedStory: () => Promise<Story>,
    successTitle: string,
    successMessage: string,
  ) => {
    try {
      setQueueing(true);
      await activateKeepAwakeAsync();

      const updatedStory = await getUpdatedStory();
      await saveAndNotify(updatedStory, onStoryUpdated);
      showAlert(successTitle, successMessage);
    } catch (error) {
      console.error("Download error", error);
      showAlert("Download Error", "Failed to download chapters. Check logs.");
    } finally {
      setQueueing(false);
      await deactivateKeepAwake();
    }
  };

  const downloadAll = async () => {
    if (!withDownloadGuard(story, "Downloading", showAlert)) return;

    await executeQueuedDownload(
      () => downloadService.downloadAllChapters(story!),
      "Download Started",
      "Chapters have been added to the download queue.",
    );
  };

  const syncChapters = async () => {
    if (!withDownloadGuard(story, "Sync", showAlert)) return;

    try {
      setSyncing(true);
      setSyncStatus("Initializing...");
      const prepared = await prepareStorySyncData({
        sourceUrl: story!.sourceUrl,
        existingStory: story!,
        onStatus: setSyncStatus,
        onProgress: setSyncStatus,
      });
      setSyncStatus("Merging...");
      const { mergeResult, metadata } = prepared;

      const tagsChanged =
        JSON.stringify(story!.tags) !== JSON.stringify(metadata.tags);
      const oldTotal = story!.chapters.length;
      const newTotal = mergeResult.chapters.length;
      const newChapterCount = mergeResult.newChapterIds.length;
      const removedChapterCount = mergeResult.removedChapterIds.length;
      const hasUpdates =
        newChapterCount > 0 || tagsChanged || removedChapterCount > 0;

      if (removedChapterCount > 0) {
        const downloadedRemovedChapterCount = mergeResult.removedChapters.filter(
          (chapter) => chapter.downloaded,
        ).length;
        const shouldArchive = await confirmArchiveAndUpdate(
          oldTotal,
          newTotal,
          removedChapterCount,
          downloadedRemovedChapterCount,
          newChapterCount,
        );

        if (!shouldArchive) {
          return;
        }

        setSyncStatus("Archiving snapshot...");
        await storageService.createArchivedStorySnapshot(
          story!,
          "source_chapters_removed",
        );
      }

      const updatedEpubConfig = buildUpdatedEpubConfig(story!, newTotal);
      const updatedStory = buildStoryForSync({
        currentStory: story!,
        prepared,
        updatedEpubConfig,
      });

      await saveAndNotify(updatedStory, onStoryUpdated);

      if (hasUpdates) {
        if (newChapterCount > 0) {
          setQueueing(true);
          const queuedStory = await downloadService.downloadChaptersByIds(
            updatedStory,
            mergeResult.newChapterIds,
          );
          await saveAndNotify(queuedStory, onStoryUpdated);
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

  const downloadRange = async (startChapter: number, endChapter: number) => {
    if (!withDownloadGuard(story, "Downloading", showAlert)) return;

    const validation = validateDownloadRange(
      startChapter,
      endChapter,
      story!.totalChapters,
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

    await executeQueuedDownload(
      () => downloadService.downloadRange(story!, startIndex, endIndex),
      "Download Started",
      "Selected chapters have been queued.",
    );
  };

  const applySentenceRemoval = async () => {
    if (!withDownloadGuard(story, "Text cleanup", showAlert)) return;

    const downloadedChapters = story!.chapters.filter((c) => c.downloaded);
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
                  story!,
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

              const reloadedStory = await storageService.getStory(story!.id);
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
    if (!withDownloadGuard(story, "Downloading", showAlert)) return;

    await executeQueuedDownload(
      () => downloadService.downloadChaptersByIds(story!, chapterIds),
      "Download Started",
      `${chapterIds.length} chapters have been queued.`,
    );
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
