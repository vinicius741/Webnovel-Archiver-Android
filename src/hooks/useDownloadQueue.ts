/* eslint-disable react-hooks/set-state-in-effect */
import { useEffect, useState, useCallback } from "react";
import { downloadManager } from "../services/download/DownloadManager";
import { downloadQueue } from "../services/download/DownloadQueue";
import { DownloadJob, QueueStats } from "../services/download/types";

export interface DownloadQueueState {
  jobs: DownloadJob[];
  stats: QueueStats;
}

export const useDownloadQueue = () => {
  const [state, setState] = useState<DownloadQueueState>({
    jobs: [],
    stats: {
      total: 0,
      pending: 0,
      active: 0,
      completed: 0,
      failed: 0,
      paused: 0,
    },
  });

  const refreshState = useCallback(() => {
    const jobs = downloadQueue.getAllJobs();
    const stats = downloadQueue.getStats();
    setState({ jobs, stats });
  }, []);

  useEffect(() => {
    refreshState();

    const onQueueUpdated = () => refreshState();
    const onJobStarted = () => refreshState();
    const onJobCompleted = () => refreshState();
    const onJobFailed = () => refreshState();
    const onJobPaused = () => refreshState();
    const onJobResumed = () => refreshState();

    downloadManager.on("queue-updated", onQueueUpdated);
    downloadManager.on("job-started", onJobStarted);
    downloadManager.on("job-completed", onJobCompleted);
    downloadManager.on("job-failed", onJobFailed);
    downloadManager.on("job-paused", onJobPaused);
    downloadManager.on("job-resumed", onJobResumed);

    return () => {
      downloadManager.off("queue-updated", onQueueUpdated);
      downloadManager.off("job-started", onJobStarted);
      downloadManager.off("job-completed", onJobCompleted);
      downloadManager.off("job-failed", onJobFailed);
      downloadManager.off("job-paused", onJobPaused);
      downloadManager.off("job-resumed", onJobResumed);
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
    jobsByStory: Object.values(jobsByStory),
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
