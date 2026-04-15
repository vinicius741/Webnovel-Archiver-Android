import { downloadQueue } from "./DownloadQueue";
import {
  clearDownloadState,
  setDownloadState,
  showDownloadCompletionNotification,
} from "../ForegroundServiceCoordinator";

export class DownloadNotificationManager {
  private lastNotificationUpdate = 0;

  async showInitial(): Promise<void> {
    const stats = downloadQueue.getStats();
    if (stats.pending > 0 || stats.active > 0) {
      const completed = Math.max(
        0,
        stats.total - stats.pending - stats.active - stats.paused,
      );
      await setDownloadState({
        title: "Downloading chapters",
        message: `Active: ${stats.active}, Pending: ${stats.pending}, Paused: ${stats.paused}`,
        current: completed,
        total: stats.total,
      });
    }
  }

  async update(): Promise<void> {
    const now = Date.now();
    if (now - this.lastNotificationUpdate < 1000) return;
    this.lastNotificationUpdate = now;

    const stats = downloadQueue.getStats();
    if (stats.pending === 0 && stats.active === 0) {
      await clearDownloadState();
      return;
    }

    const completed = Math.max(
      0,
      stats.total - stats.pending - stats.active - stats.paused,
    );
    await setDownloadState({
      title: "Downloading...",
      message: `Active: ${stats.active}, Pending: ${stats.pending}, Paused: ${stats.paused}`,
      current: completed,
      total: stats.total,
    });
  }

  async showCompletion(cancelled: boolean): Promise<void> {
    await clearDownloadState();
    if (!cancelled) {
      await showDownloadCompletionNotification(
        "Downloads Completed",
        "All queued chapters have been processed.",
      );
    }
  }
}
