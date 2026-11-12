package com.vinicius741.webnovelarchiver.navigation

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/** Badge values for the navigation bar / rail destinations. */
object ShellBadgePlanning {
    /** Novels with chapters still waiting or downloading (paused, finished and failed ones excluded). */
    fun downloadsBadge(queue: List<DownloadJob>): Int =
        queue
            .filter { it.status == DownloadJobStatus.Pending.wire || it.status == DownloadJobStatus.Downloading.wire }
            .map { it.storyId }
            .distinct()
            .size
}
