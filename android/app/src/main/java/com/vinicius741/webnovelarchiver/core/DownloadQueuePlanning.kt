package com.vinicius741.webnovelarchiver.core

data class QueueChapterPlan(
    val jobs: MutableList<DownloadJob>,
    val changed: Boolean,
    val hasRunnableWork: Boolean,
)

object DownloadQueuePlanning {
    private val terminalStatuses = setOf(DownloadJobStatus.Failed.wire, DownloadJobStatus.Completed.wire, DownloadJobStatus.Cancelled.wire)
    private val runnableStatuses = setOf(DownloadJobStatus.Pending.wire, DownloadJobStatus.Downloading.wire)

    fun queueChapters(existingJobs: List<DownloadJob>, story: Story, indexes: List<Int>): QueueChapterPlan {
        val jobs = existingJobs.map { it.copy(chapter = it.chapter.copy()) }.toMutableList()
        var changed = false
        var hasRunnableWork = false

        indexes.filter { it in story.chapters.indices }.forEach { index ->
            val chapter = story.chapters[index]
            if (chapter.downloaded) return@forEach

            val id = "${story.id}_$index"
            val existingIndex = jobs.indexOfFirst { it.id == id }
            if (existingIndex == -1) {
                jobs.add(pendingJob(story, index, chapter))
                changed = true
                hasRunnableWork = true
                return@forEach
            }

            val existing = jobs[existingIndex]
            if (existing.status in terminalStatuses) {
                jobs[existingIndex] = pendingJob(story, index, chapter, retryCount = existing.retryCount)
                changed = true
                hasRunnableWork = true
            } else if (existing.status in runnableStatuses) {
                hasRunnableWork = true
            }
        }

        return QueueChapterPlan(jobs, changed, hasRunnableWork)
    }

    private fun pendingJob(story: Story, index: Int, chapter: Chapter, retryCount: Int = 0): DownloadJob =
        DownloadJob(
            id = "${story.id}_$index",
            storyId = story.id,
            storyTitle = story.title,
            chapterIndex = index,
            chapter = chapter.copy(),
            status = DownloadJobStatus.Pending.wire,
            retryCount = retryCount,
            error = null,
            errorCategory = null,
            errorCode = null,
            nextRetryAt = null,
        )
}
