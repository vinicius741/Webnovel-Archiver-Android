import { useEffect, useState } from "react";
import { downloadManager } from "../../services/download/DownloadManager";
import { downloadQueue } from "../../services/download/DownloadQueue";
import { DownloadJob } from "../../services/download/types";

export const useDownloadProgress = (storyId: string) => {
  const [progress, setProgress] = useState(0);
  const [status, setStatus] = useState("");
  const [isDownloading, setIsDownloading] = useState(false);

  useEffect(() => {
    const updateState = () => {
      const jobs = downloadQueue.getJobsForStory(storyId);

      // If no jobs, we aren't downloading this story
      if (jobs.length === 0) {
        setIsDownloading(false);
        setProgress(0);
        setStatus("");
        return;
      }

      const total = jobs.length;
      const completed = jobs.filter((j) => j.status === "completed").length;
      const active = jobs.filter((j) => j.status === "downloading").length;
      const pending = jobs.filter((j) => j.status === "pending").length;
      const failed = jobs.filter((j) => j.status === "failed").length;
      const paused = jobs.filter((j) => j.status === "paused").length;
      const cancelled = jobs.filter((j) => j.status === "cancelled").length;

      if (pending === 0 && active === 0 && paused === 0) {
        setIsDownloading(false);
        setProgress(total > 0 ? completed / total : 0);
        if (failed > 0 || cancelled > 0) {
          const parts = [`${completed}/${total} downloaded`];
          if (failed > 0) parts.push(`${failed} failed`);
          if (cancelled > 0) parts.push(`${cancelled} cancelled`);
          setStatus(`Finished (${parts.join(", ")})`);
        } else {
          setProgress(1);
          setStatus("Download Complete");
        }
      } else if (paused > 0 && pending === 0 && active === 0) {
        setIsDownloading(false);
        setProgress(total > 0 ? completed / total : 0);
        setStatus(`Paused (${completed}/${total})`);
      } else {
        setIsDownloading(true);
        const p = total > 0 ? completed / total : 0;
        setProgress(p);

        // Find currently downloading title
        const activeJob = jobs.find((j) => j.status === "downloading");
        const title = activeJob ? activeJob.chapter.title : "";

        setStatus(
          activeJob
            ? `Downloading: ${title} (${completed}/${total})`
            : `Queued (${completed}/${total})`,
        );
      }
    };

    // Initial check
    updateState();

    const onQueueUpdate = () => updateState();
    const onJobStarted = (job: DownloadJob) => {
      if (job.storyId === storyId) updateState();
    };
    const onJobCompleted = (job: DownloadJob) => {
      if (job.storyId === storyId) updateState();
    };
    const onJobFailed = (job: DownloadJob) => {
      if (job.storyId === storyId) updateState();
    };

    downloadManager.on("queue-updated", onQueueUpdate);
    downloadManager.on("job-started", onJobStarted);
    downloadManager.on("job-completed", onJobCompleted);
    downloadManager.on("job-failed", onJobFailed);
    downloadManager.on("job-cancelled", onJobFailed);
    downloadManager.on("job-retry-scheduled", onJobFailed);

    return () => {
      downloadManager.off("queue-updated", onQueueUpdate);
      downloadManager.off("job-started", onJobStarted);
      downloadManager.off("job-completed", onJobCompleted);
      downloadManager.off("job-failed", onJobFailed);
      downloadManager.off("job-cancelled", onJobFailed);
      downloadManager.off("job-retry-scheduled", onJobFailed);
    };
  }, [storyId]);

  return { progress, status, isDownloading };
};
