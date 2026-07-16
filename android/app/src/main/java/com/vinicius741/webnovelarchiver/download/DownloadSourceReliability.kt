package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import timber.log.Timber

internal data class DownloadQueueMutation(
    val queue: List<DownloadJob>,
    val activeJobId: String? = null,
)

internal data class BulkPreflightResult(
    val attempted: Boolean,
    val mutation: DownloadQueueMutation? = null,
)

/** Owns source-wide preflight and failure transitions without expanding DownloadEngine's process loop. */
internal class DownloadSourceReliability(
    private val storage: AppStorage,
    private val network: NetworkClient,
    private val acceptsWorkerResults: () -> Boolean,
    private val nowMillis: () -> Long = System::currentTimeMillis,
) {
    private val preflightedSources = mutableSetOf<String>()

    suspend fun preflightLargeBatch(queue: List<DownloadJob>): BulkPreflightResult {
        val grouped =
            queue
                .filter { it.status == DownloadJobStatus.Pending.wire }
                .groupBy { SourceRegistry.getProvider(it.chapter.url)?.name }
        preflightedSources.retainAll(grouped.keys.filterNotNull().toSet())
        val candidate =
            grouped.entries
                .firstOrNull { (providerName, jobs) ->
                    providerName != null &&
                        providerName !in preflightedSources &&
                        jobs.size >= BULK_PREFLIGHT_CHAPTERS
                } ?: return BulkPreflightResult(attempted = false)
        val providerName = candidate.key ?: return BulkPreflightResult(attempted = false)
        preflightedSources += providerName
        return try {
            network.prepareBulkDownload(
                candidate.value
                    .first()
                    .chapter.url,
            )
            BulkPreflightResult(attempted = true)
        } catch (error: SourceAccessBlockedException) {
            BulkPreflightResult(attempted = true, mutation = blockSourceJobs(providerName, error))
        } catch (error: RateLimitNetworkException) {
            BulkPreflightResult(
                attempted = true,
                mutation = deferSourceJobs(providerName, error.retryAfterMillis ?: DEFAULT_RATE_LIMIT_DELAY_MILLIS),
            )
        }
    }

    fun handleJobError(
        job: DownloadJob,
        error: Throwable,
        providerName: String?,
    ): DownloadQueueMutation? {
        if (!acceptsWorkerResults()) return null
        val classified = DownloadErrorClassifier.classify(error)
        Timber.w(
            error,
            "Download job %s failed (category=%s, code=%s, retry=%s)",
            job.id,
            classified.category,
            classified.code,
            job.retryCount,
        )
        lateinit var queue: List<DownloadJob>
        var accepted = false
        storage.mutateQueueInPlace { current ->
            queue = current
            if (!acceptsWorkerResults()) return@mutateQueueInPlace current
            val failedJob = current.find { it.id == job.id }
            failedJob?.let { queuedJob -> updateFailedJob(queuedJob, classified) }
            if (classified.category == "rate_limit" && providerName != null) {
                val retryAt =
                    failedJob?.nextRetryAt
                        ?: (nowMillis() + DownloadErrorClassifier.retryDelayMs(job, classified))
                DownloadSourceFailurePlanning.deferPendingJobs(current, providerName, retryAt, ::providerNameForJob)
            }
            accepted = true
            current
        }
        return if (accepted) DownloadQueueMutation(queue, job.id) else null
    }

    fun blockSourceJobs(
        providerName: String,
        error: SourceAccessBlockedException,
    ): DownloadQueueMutation? {
        if (!acceptsWorkerResults()) return null
        lateinit var queue: List<DownloadJob>
        var accepted = false
        storage.mutateQueueInPlace { current ->
            queue = current
            if (!acceptsWorkerResults()) return@mutateQueueInPlace current
            DownloadSourceFailurePlanning.blockActiveJobs(current, providerName, error.message, ::providerNameForJob)
            accepted = true
            current
        }
        return if (accepted) DownloadQueueMutation(queue) else null
    }

    private fun deferSourceJobs(
        providerName: String,
        delayMillis: Long,
    ): DownloadQueueMutation? {
        if (!acceptsWorkerResults()) return null
        val retryAt = nowMillis() + delayMillis.coerceAtLeast(0L)
        lateinit var queue: List<DownloadJob>
        var accepted = false
        storage.mutateQueueInPlace { current ->
            queue = current
            if (!acceptsWorkerResults()) return@mutateQueueInPlace current
            DownloadSourceFailurePlanning.deferPendingJobs(current, providerName, retryAt, ::providerNameForJob)
            accepted = true
            current
        }
        return if (accepted) DownloadQueueMutation(queue) else null
    }

    private fun updateFailedJob(
        job: DownloadJob,
        classified: ClassifiedDownloadError,
    ) {
        job.retryCount += 1
        job.error = classified.message
        job.errorCategory = classified.category
        job.errorCode = classified.code
        if (DownloadErrorClassifier.shouldAutoRetry(job, classified)) {
            job.status = DownloadJobStatus.Pending.wire
            job.nextRetryAt = nowMillis() + DownloadErrorClassifier.retryDelayMs(job, classified)
        } else {
            job.status =
                if (classified.category == "cancelled") {
                    DownloadJobStatus.Cancelled.wire
                } else {
                    DownloadJobStatus.Failed.wire
                }
            job.nextRetryAt = null
        }
    }

    private fun providerNameForJob(job: DownloadJob): String? = SourceRegistry.getProvider(job.chapter.url)?.name

    private companion object {
        const val BULK_PREFLIGHT_CHAPTERS = 20
        const val DEFAULT_RATE_LIMIT_DELAY_MILLIS = 60_000L
    }
}
