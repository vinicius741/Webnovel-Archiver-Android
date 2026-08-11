package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.Chapter
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.Story

data class QueueChapterPlan(
    val jobs: MutableList<DownloadJob>,
    val changed: Boolean,
    val hasRunnableWork: Boolean,
)

object DownloadQueuePlanning {
    private val terminalStatuses = DownloadJobStatus.terminalWires
    private val runnableStatuses = DownloadJobStatus.activeWires

    fun queueChapters(
        existingJobs: List<DownloadJob>,
        story: Story,
        indexes: List<Int>,
    ): QueueChapterPlan {
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

        // A completed row is history from an earlier batch, not part of the new enqueue's progress.
        // Keep it until genuinely new work is added (so a no-op tap cannot silently clear history),
        // then retire only this story's completed rows. Failed/cancelled rows remain available for
        // retry, and active rows remain part of the current work. This is especially important after
        // sync auto-downloads new chapters: a later "Download Remaining (N)" batch must report N
        // jobs rather than aggregating those already-finished sync jobs into its total.
        if (changed) {
            jobs.removeAll { job ->
                job.storyId == story.id && job.status == DownloadJobStatus.Completed.wire
            }
        }

        return QueueChapterPlan(jobs, changed, hasRunnableWork)
    }

    private fun pendingJob(
        story: Story,
        index: Int,
        chapter: Chapter,
        retryCount: Int = 0,
    ): DownloadJob =
        DownloadJob(
            id = "${story.id}_$index",
            storyId = story.id,
            storyTitle = story.title,
            sourceId = story.sourceId,
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
