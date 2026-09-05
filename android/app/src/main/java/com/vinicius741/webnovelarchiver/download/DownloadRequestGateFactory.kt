package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkRequestGate

/**
 * Builds the per-job [NetworkRequestGate] that combines the user-configured download delay (via
 * [DownloadRequestPacer]) with the shared source-safety claim, and the liveness check that aborts a
 * request when its job was paused/cancelled while still queued for pacing. Extracted from
 * [DownloadEngine] so that file stays within its size budget.
 */
internal class DownloadRequestGateFactory(
    private val repository: AppRepository,
    private val downloadPacer: DownloadRequestPacer,
) {
    fun gateFor(
        sourceId: String,
        job: DownloadJob,
    ): NetworkRequestGate =
        NetworkRequestGate { claimSourcePermission ->
            val providerName = SourceRegistry.getById(sourceId)?.name ?: sourceId
            downloadPacer.awaitTurn(
                providerName = providerName,
                storyId = job.storyId,
                jobId = job.id,
                chapterTitle = job.chapter.title,
                claimSourcePermission = claimSourcePermission,
            ) {
                ensureJobActive(job.id)
                val settings = repository.getSettings()
                DownloadScheduler.settingsFor(
                    providerName = sourceId,
                    globalSettings =
                        SourceDownloadSettings(
                            concurrency = settings.downloadConcurrency,
                            delay = settings.downloadDelay,
                            delayMax = settings.downloadDelayMax,
                        ),
                    sourceSettings = repository.getSourceDownloadSettings(),
                )
            }
        }

    /** Liveness check against the repository's coherent cached queue (R21), not durable JSON. */
    fun ensureJobActive(jobId: String) {
        val active =
            repository
                .queue()
                .firstOrNull { it.id == jobId }
                ?.status
                ?.let { it in DownloadJobStatus.activeWires } == true
        if (!active) throw DownloadJobInactiveException(jobId)
    }
}
