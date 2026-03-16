import { useEffect, useState, useCallback } from "react";
import { downloadManager } from "../services/download/DownloadManager";
import { downloadQueue } from "../services/download/DownloadQueue";
import { DownloadJob, QueueStats } from "../services/download/types";

export interface DownloadQueueState {
  jobs: DownloadJob[];
  stats: QueueStats;
}

const DOWNLOAD_MANAGER_EVENTS = [
  "queue-updated",
  "job-started",
  "job-completed",
  "job-failed",
  "job-paused",
  "job-resumed",
] as const;

const INITIAL_STATS: QueueStats = {
  total: 0,
  pending: 0,
  active: 0,
  completed: 0,
  failed: 0,
  paused: 0,
};

const getQueueSnapshot = (): DownloadQueueState => ({
  jobs: downloadQueue.getAllJobs(),
  stats: downloadQueue.getStats(),
});

export const useDownloadQueue = () => {
  const [state, setState] = useState<DownloadQueueState>(() => {
    const snapshot = getQueueSnapshot();
    return {
      jobs: snapshot.jobs,
      stats: snapshot.stats || INITIAL_STATS,
    };
  });

  const refreshState = useCallback(() => {
    setState(getQueueSnapshot());
  }, []);

  useEffect(() => {
    const onQueueChanged = () => refreshState();
    DOWNLOAD_MANAGER_EVENTS.forEach((eventName) => {
      downloadManager.on(eventName, onQueueChanged);
    });

    return () => {
      DOWNLOAD_MANAGER_EVENTS.forEach((eventName) => {
        downloadManager.off(eventName, onQueueChanged);
      });
    };
  }, [refreshState]);

  const pauseJob = useCallback(async (jobId: string) => {
    await downloadManager.pauseJob(jobId);
  }, []);

  const resumeJob = useCallback(async (jobId: string) => {
    await downloadManager.resumeJob(jobId);
  }, []);

  const cancelJob = useCallback(async (jobId: string) => {
    await downloadManager.cancelJob(jobId);
  }, []);

  const pauseAll = useCallback(async () => {
    await downloadManager.pauseAll();
  }, []);

  const resumeAll = useCallback(async () => {
    await downloadManager.resumeAll();
  }, []);

  const cancelAll = useCallback(async () => {
    await downloadManager.cancelAll();
  }, []);

  const clearCompleted = useCallback(() => {
    downloadQueue.clearCompleted();
    refreshState();
  }, [refreshState]);

  const retryJob = useCallback(async (jobId: string) => {
    await downloadManager.retryJob(jobId);
  }, []);

  const jobsByStory = state.jobs.reduce(
    (acc, job) => {
      if (!acc[job.storyId]) {
        acc[job.storyId] = {
          storyId: job.storyId,
          storyTitle: job.storyTitle,
          jobs: [],
        };
      }
      acc[job.storyId].jobs.push(job);
      return acc;
    },
    {} as Record<
      string,
      { storyId: string; storyTitle: string; jobs: DownloadJob[] }
    >,
  );

  return {
    jobs: state.jobs,
    jobsByStory: Object.values(jobsByStory).reverse(),
    stats: state.stats,
    pauseJob,
    resumeJob,
    cancelJob,
    retryJob,
    pauseAll,
    resumeAll,
    cancelAll,
    clearCompleted,
    refreshState,
  };
};
