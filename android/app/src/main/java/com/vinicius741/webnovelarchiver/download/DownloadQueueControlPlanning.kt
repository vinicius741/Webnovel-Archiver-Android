package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus

object DownloadQueueControlPlanning {
    private val activeStatuses = DownloadJobStatus.activeWires
    private val cancellableStatuses = DownloadJobStatus.cancellableWires

    fun pauseAll(jobs: List<DownloadJob>): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.status in activeStatuses) {
                job.status = DownloadJobStatus.Paused.wire
                job.nextRetryAt = null
            }
        }

    fun pauseJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.id == jobId && job.status in activeStatuses) {
                job.status = DownloadJobStatus.Paused.wire
                job.nextRetryAt = null
            }
        }

    fun resumeAll(jobs: List<DownloadJob>): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.status == DownloadJobStatus.Paused.wire) {
                job.status = DownloadJobStatus.Pending.wire
                job.nextRetryAt = null
            }
        }

    fun resumeJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.id == jobId && job.status == DownloadJobStatus.Paused.wire) {
                job.status = DownloadJobStatus.Pending.wire
                job.nextRetryAt = null
            }
        }

    fun cancelAll(
        jobs: List<DownloadJob>,
        reason: String = "cancelled",
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.status in cancellableStatuses) {
                markCancelled(job, reason)
            }
        }

    fun cancelJob(
        jobs: List<DownloadJob>,
        jobId: String,
        reason: String = "cancelled by user",
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.id == jobId && job.status in cancellableStatuses) {
                markCancelled(job, reason)
            }
        }

    fun retryFailed(
        jobs: List<DownloadJob>,
        storyId: String? = null,
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            val storyMatches = storyId == null || job.storyId == storyId
            if (storyMatches && job.status == DownloadJobStatus.Failed.wire) {
                markPendingForRetry(job)
            }
        }

    fun retryFailedJob(
        jobs: List<DownloadJob>,
        jobId: String,
    ): MutableList<DownloadJob> =
        jobs.copyJobs().onEach { job ->
            if (job.id == jobId && job.status == DownloadJobStatus.Failed.wire) {
                markPendingForRetry(job)
            }
        }

    private fun markCancelled(
        job: DownloadJob,
        reason: String,
    ) {
        job.status = DownloadJobStatus.Cancelled.wire
        job.error = reason
        job.errorCategory = "cancelled"
        job.errorCode = "CANCELLED"
        job.nextRetryAt = null
    }

    private fun markPendingForRetry(job: DownloadJob) {
        job.status = DownloadJobStatus.Pending.wire
        job.retryCount += 1
        job.error = null
        job.errorCategory = null
        job.errorCode = null
        job.nextRetryAt = null
    }

    private fun List<DownloadJob>.copyJobs(): MutableList<DownloadJob> = map { it.copy(chapter = it.chapter.copy()) }.toMutableList()
}
