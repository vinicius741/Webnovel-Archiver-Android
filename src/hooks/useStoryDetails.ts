import { useCallback, useEffect, useState } from "react";
import { useRouter } from "expo-router";

import { storageService } from "../services/StorageService";
import { downloadManager } from "../services/download/DownloadManager";
import { Story, DownloadStatus } from "../types";
import { DownloadJob } from "../services/download/types";

import { useDownloadProgress } from "./useDownloadProgress";
import { useStoryActions } from "./useStoryActions";
import { useStoryDownload } from "./useStoryDownload";
import { useStoryEPUB } from "./useStoryEPUB";

export const useStoryDetails = (id: string | string[] | undefined) => {
  const router = useRouter();
  const [story, setStory] = useState<Story | null>(null);
  const [loading, setLoading] = useState(true);
  const [showEpubSelector, setShowEpubSelector] = useState(false);
  const [availableEpubs, setAvailableEpubs] = useState<string[]>([]);

  const storyId = Array.isArray(id) ? id[0] : typeof id === "string" ? id : "";
  const {
    progress: downloadProgress,
    status: downloadStatus,
    isDownloading: isDownloadingHook,
  } = useDownloadProgress(storyId);

  const reloadStory = useCallback(async () => {
    if (!storyId) return;

    try {
      const data = await storageService.getStory(storyId);
      if (data) {
        setStory(data);
      }
    } catch (error) {
      console.error("Failed to reload story", error);
      setStory(null);
    }
  }, [storyId]);

  useEffect(() => {
    const loadStory = async () => {
      if (storyId) {
        const data = await storageService.getStory(storyId);
        if (data) {
          setStory(data);
        }
      }
      setLoading(false);
    };
    void loadStory();
  }, [storyId]);

  useEffect(() => {
    const onAllComplete = (completedStoryIds: string[]) => {
      if (!completedStoryIds.includes(storyId)) return;

      void reloadStory();
    };

    downloadManager.on("all-complete", onAllComplete);

    return () => {
      downloadManager.off("all-complete", onAllComplete);
    };
  }, [reloadStory, storyId]);

  useEffect(() => {
    const onJobCompleted = (job: DownloadJob, filePath: string) => {
      if (job.storyId !== storyId) return;

      setStory((prevStory) => {
        if (!prevStory) return prevStory;

        const chapters = [...prevStory.chapters];
        if (chapters[job.chapterIndex]) {
          chapters[job.chapterIndex] = {
            ...chapters[job.chapterIndex],
            downloaded: true,
            filePath,
          };
        }

        const downloadedCount = chapters.filter((c) => c.downloaded).length;
        const status =
          downloadedCount === chapters.length
            ? DownloadStatus.Completed
            : DownloadStatus.Partial;

        return {
          ...prevStory,
          chapters,
          downloadedChapters: downloadedCount,
          status,
        };
      });
    };

    downloadManager.on("job-completed", onJobCompleted);

    return () => {
      downloadManager.off("job-completed", onJobCompleted);
    };
  }, [storyId]);

  const { deleteStory, markChapterAsRead } = useStoryActions({
    story,
    onStoryUpdated: setStory,
    onStoryDeleted: () => {
      if (router.canDismiss()) {
        router.dismiss();
      } else {
        router.replace("/");
      }
    },
  });

  const {
    syncing,
    syncStatus,
    queueing,
    downloadAll,
    syncChapters,
    downloadRange,
    applySentenceRemoval,
    downloadChaptersByIds,
  } = useStoryDownload({
    story,
    onStoryUpdated: setStory,
  });

  const {
    generating,
    progress: epubProgress,
    generateEpub,
    readEpub,
    readEpubAtPath,
  } = useStoryEPUB({
    story,
    onStoryUpdated: setStory,
    onMultipleEpubs: (paths) => {
      setAvailableEpubs(paths);
      setShowEpubSelector(true);
    },
  });

  const updateStory = async (updatedStory: Story) => {
    await storageService.addStory(updatedStory);
    setStory(updatedStory);
  };

  return {
    story,
    loading,
    downloading: queueing || isDownloadingHook,
    syncing,
    syncStatus,
    downloadProgress,
    downloadStatus,
    generating,
    epubProgress,
    deleteStory,
    markChapterAsRead,
    syncChapters,
    downloadAll,
    downloadRange,
    generateEpub,
    readEpub,
    readEpubAtPath,
    applySentenceRemoval,
    updateStory,
    showEpubSelector,
    setShowEpubSelector,
    availableEpubs,
    downloadChaptersByIds,
  };
};
