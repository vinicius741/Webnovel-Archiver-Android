package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

/**
 * Queue-order rewrites behind reordering and site checks. The scheduler takes pending jobs
 * first-in-first-out per site, so moving a novel's block of jobs changes when it downloads.
 */
object DownloadQueueOrderPlanning {
    /** Rearranges the named novels among the queue positions they already occupy; others stay put. */
    fun reorderStories(
        jobs: List<DownloadJob>,
        orderedStoryIds: List<String>,
    ): List<DownloadJob> {
        val blocks = storyBlocks(jobs)
        val moving = orderedStoryIds.distinct().filter(blocks::containsKey)
        val movingSet = moving.toSet()
        val replacements = ArrayDeque(moving)
        return blocks.keys.flatMap { storyId ->
            blocks.getValue(if (storyId in movingSet) replacements.removeFirst() else storyId)
        }
    }

    /**
     * "Download now": the novel's chapters become the first pending work for its site. Another
     * novel's chapter in [handBackJobIds] (claimed but still waiting out the pacing delay) goes
     * back to pending, so the moved novel takes over now instead of after that wait; a chapter
     * already being fetched is left to finish.
     */
    fun moveStoryToFront(
        jobs: List<DownloadJob>,
        storyId: String,
        handBackJobIds: Set<String> = emptySet(),
    ): List<DownloadJob> {
        val blocks = storyBlocks(jobs)
        val moved = blocks[storyId] ?: return jobs
        val reordered = moved + blocks.filterKeys { it != storyId }.values.flatten()
        if (handBackJobIds.isEmpty()) return reordered
        return reordered.map { original ->
            if (original.storyId == storyId || original.id !in handBackJobIds) return@map original
            original.copy(chapter = original.chapter.copy()).also(DownloadJobTransitions::recoverInterrupted)
        }
    }

    fun moveStoryToEnd(
        jobs: List<DownloadJob>,
        storyId: String,
    ): List<DownloadJob> {
        val blocks = storyBlocks(jobs)
        val moved = blocks[storyId] ?: return jobs
        return blocks.filterKeys { it != storyId }.values.flatten() + moved
    }

    private fun storyBlocks(jobs: List<DownloadJob>): LinkedHashMap<String, MutableList<DownloadJob>> {
        val blocks = LinkedHashMap<String, MutableList<DownloadJob>>()
        jobs.forEach { job -> blocks.getOrPut(job.storyId) { mutableListOf() } += job }
        return blocks
    }

    /**
     * After a passed check: chapters that failed as blocked go back to pending without spending a
     * retry, and the site's deferred pending chapters lose their recheck delay so they start now.
     */
    fun resumeAfterVerification(
        jobs: List<DownloadJob>,
        providerId: String,
        providerIdForJob: (DownloadJob) -> String?,
    ): VerificationResume {
        var resumed = 0
        val updated =
            jobs.map { original ->
                val job = original.copy(chapter = original.chapter.copy())
                if (providerIdForJob(job) != providerId) return@map job
                if (DownloadJobTransitions.releaseAfterVerification(job)) resumed += 1
                job
            }
        return VerificationResume(updated, resumed)
    }
}
