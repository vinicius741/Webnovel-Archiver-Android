package com.vinicius741.webnovelarchiver.core

data class OrphanJobCleanupResult(
    val cleanedJobCount: Int,
    val affectedStoryIds: List<String>,
)

object DownloadQueueMaintenance {
    const val NO_PROVIDER_MESSAGE = "No matching source provider"
    const val NO_PROVIDER_CATEGORY = "source"
    const val NO_PROVIDER_CODE = "NO_SOURCE_PROVIDER"

    fun failUnsupportedSourceJobs(
        jobs: MutableList<DownloadJob>,
        providerNameForJob: (DownloadJob) -> String?,
    ): OrphanJobCleanupResult {
        val affectedStoryIds = linkedSetOf<String>()
        var cleaned = 0
        jobs.forEach { job ->
            if (job.status != "pending" && job.status != "downloading") return@forEach
            if (providerNameForJob(job) != null) return@forEach
            job.status = "failed"
            job.error = NO_PROVIDER_MESSAGE
            job.errorCategory = NO_PROVIDER_CATEGORY
            job.errorCode = NO_PROVIDER_CODE
            job.nextRetryAt = null
            affectedStoryIds.add(job.storyId)
            cleaned += 1
        }
        return OrphanJobCleanupResult(cleaned, affectedStoryIds.toList())
    }

    fun recoverStuckDownloadingStory(story: Story, jobsForStory: List<DownloadJob>): Boolean {
        if (story.status != DownloadStatus.downloading) return false
        val hasActiveJobs = jobsForStory.any { it.status == "pending" || it.status == "downloading" }
        if (hasActiveJobs) return false
        story.downloadedChapters = story.chapters.count { it.downloaded }
        story.status = if (story.downloadedChapters > 0) DownloadStatus.partial else DownloadStatus.idle
        return true
    }
}
