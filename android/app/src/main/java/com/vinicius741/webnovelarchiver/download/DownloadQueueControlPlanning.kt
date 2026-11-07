package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.download.DownloadJobTransitions
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob

/** Bulk queue controls. Each selects jobs and applies one [DownloadJobTransitions] move to them. */
object DownloadQueueControlPlanning {
    fun pauseAll(jobs: List<DownloadJob>): MutableList<DownloadJob> = jobs.copyJobs().onEach(DownloadJobTransitions::pause)

    fun pauseJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.id == jobId) DownloadJobTransitions.pause(it) }

    /** Pauses every active job of one story in a single transform (one durable save for the group). */
    fun pauseStory(
        jobs: List<DownloadJob>,
        storyId: String,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.storyId == storyId) DownloadJobTransitions.pause(it) }

    fun pauseWhere(
        jobs: List<DownloadJob>,
        predicate: (DownloadJob) -> Boolean,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (predicate(it)) DownloadJobTransitions.pause(it) }

    fun resumeAll(jobs: List<DownloadJob>): MutableList<DownloadJob> = jobs.copyJobs().onEach(DownloadJobTransitions::resume)

    fun resumeJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.id == jobId) DownloadJobTransitions.resume(it) }

    /** Resumes every paused job of one story in a single transform. */
    fun resumeStory(
        jobs: List<DownloadJob>,
        storyId: String,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.storyId == storyId) DownloadJobTransitions.resume(it) }

    fun cancelAll(
        jobs: List<DownloadJob>,
        reason: String = "cancelled",
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { DownloadJobTransitions.cancel(it, reason) }

    fun cancelJob(
        jobs: List<DownloadJob>,
        jobId: String,
        reason: String = "cancelled by user",
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.id == jobId) DownloadJobTransitions.cancel(it, reason) }

    /** Cancels every cancellable job of one story in a single transform. */
    fun cancelStory(
        jobs: List<DownloadJob>,
        storyId: String,
        reason: String = "cancelled by user",
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.storyId == storyId) DownloadJobTransitions.cancel(it, reason) }

    // The queue UI offers RETRY for both terminal-failure rows; a cancelled job is re-queued
    // through the same path as a failed one.
    fun retryFailed(
        jobs: List<DownloadJob>,
        storyId: String? = null,
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { if (storyId == null || it.storyId == storyId) DownloadJobTransitions.requeueForManualRetry(it) }

    fun retryFailedJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> = jobs.copyJobs().onEach { if (it.id == jobId) DownloadJobTransitions.requeueForManualRetry(it) }

    private fun List<DownloadJob>.copyJobs(): MutableList<DownloadJob> = map { it.copy(chapter = it.chapter.copy()) }.toMutableList()
}
