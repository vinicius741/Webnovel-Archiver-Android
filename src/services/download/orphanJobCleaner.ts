import { downloadQueue } from "./DownloadQueue";
import { sourceRegistry } from "../source/SourceRegistry";
import { storageService } from "../StorageService";
import { DownloadStatus } from "../../types";

export interface OrphanCleanupResult {
  cleanedJobCount: number;
  affectedStoryIds: string[];
}

export const cleanupOrphanedJobs = (): OrphanCleanupResult => {
  const allJobs = downloadQueue.getAllJobs();
  const orphanedJobIds: string[] = [];
  const affectedStoryIds = new Set<string>();

  for (const job of allJobs) {
    if (job.status !== "pending" && job.status !== "downloading") continue;

    const providerName = sourceRegistry.getProvider(job.chapter.url)?.name;
    if (!providerName) {
      orphanedJobIds.push(job.id);
      affectedStoryIds.add(job.storyId);
    }
  }

  if (orphanedJobIds.length === 0) {
    return { cleanedJobCount: 0, affectedStoryIds: [] };
  }

  console.warn(
    `[DownloadManager] Cleaning up ${orphanedJobIds.length} orphaned job(s) with no matching provider.`,
  );

  for (const jobId of orphanedJobIds) {
    downloadQueue.updateJobStatus(jobId, "failed", "No matching source provider");
  }

  for (const storyId of affectedStoryIds) {
    void recoverStuckStoryStatus(storyId);
  }

  return {
    cleanedJobCount: orphanedJobIds.length,
    affectedStoryIds: Array.from(affectedStoryIds),
  };
};

const recoverStuckStoryStatus = async (storyId: string): Promise<void> => {
  try {
    const story = await storageService.getStory(storyId);
    if (!story) return;
    if (story.status !== DownloadStatus.Downloading) return;

    const remainingJobs = downloadQueue.getJobsForStory(storyId);
    const hasActiveJobs = remainingJobs.some(
      (j) => j.status === "pending" || j.status === "downloading",
    );

    if (!hasActiveJobs) {
      const downloadedCount = story.chapters.filter((c) => c.downloaded).length;
      const recoveredStatus =
        downloadedCount > 0 ? DownloadStatus.Partial : DownloadStatus.Idle;
      await storageService.updateStoryStatus(storyId, recoveredStatus);
      console.log(
        `[DownloadManager] Recovered stuck story ${storyId} to status: ${recoveredStatus}`,
      );
    }
  } catch (err) {
    console.error(
      `[DownloadManager] Failed to recover story status for ${storyId}`,
      err,
    );
  }
};
