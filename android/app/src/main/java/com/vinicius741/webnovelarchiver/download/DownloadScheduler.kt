package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import com.vinicius741.webnovelarchiver.ui.size
import kotlin.random.Random

/**
 * Download scheduling + error classification + progress (Maintainability M1: split out of Engines.kt).
 * These are the pure helpers the [DownloadEngine] process loop relies on; they stay together because
 * the scheduler, classifier, and progress shape are tightly coupled to job lifecycle.
 */
object DownloadScheduler {
    fun selectEligibleJobs(
        jobs: List<DownloadJob>,
        now: Long,
        globalSettings: SourceDownloadSettings,
        sourceSettings: Map<String, SourceDownloadSettings>,
        activeCounts: Map<String, Int>,
        nextAllowedAt: Map<String, Long>,
        providerNameForJob: (DownloadJob) -> String?,
    ): List<DownloadJob> {
        val availableGlobalSlots = globalSettings.concurrency.coerceAtLeast(1) - activeCounts.values.sum()
        if (availableGlobalSlots <= 0) return emptyList()

        val selected = mutableListOf<DownloadJob>()
        val selectedBySource = mutableMapOf<String, Int>()
        for (job in jobs) {
            if (selected.size >= availableGlobalSlots) break
            if (job.status != DownloadJobStatus.Pending.wire) continue
            if (job.nextRetryAt != null && job.nextRetryAt!! > now) continue

            val providerName = providerNameForJob(job) ?: continue
            val sourceLimit =
                settingsFor(
                    providerName,
                    globalSettings,
                    sourceSettings,
                ).concurrency.coerceAtLeast(1)
            val activeForSource = activeCounts[providerName] ?: 0
            val selectedForSource = selectedBySource[providerName] ?: 0
            if (activeForSource + selectedForSource >= sourceLimit) continue
            if ((nextAllowedAt[providerName] ?: 0L) > now) continue

            selected.add(job)
            selectedBySource[providerName] = selectedForSource + 1
        }
        return selected
    }

    fun nextWakeUpAt(
        jobs: List<DownloadJob>,
        now: Long,
        nextAllowedAt: Map<String, Long>,
        providerNameForJob: (DownloadJob) -> String?,
    ): Long? {
        var next: Long? = null
        jobs.filter { it.status == DownloadJobStatus.Pending.wire }.forEach { job ->
            val retryAt = job.nextRetryAt?.takeIf { it > now }
            val providerAt = providerNameForJob(job)?.let { nextAllowedAt[it] }?.takeIf { it > now }
            listOfNotNull(retryAt, providerAt).forEach { candidate ->
                next = minOf(next ?: candidate, candidate)
            }
        }
        return next
    }

    fun settingsFor(
        providerName: String,
        globalSettings: SourceDownloadSettings,
        sourceSettings: Map<String, SourceDownloadSettings>,
    ): SourceDownloadSettings {
        val override = sourceSettings[providerName]
        val minDelay = (override?.delay ?: globalSettings.delay).coerceAtLeast(0)
        val maxDelay = override?.delayMax ?: globalSettings.delayMax
        return SourceDownloadSettings(
            concurrency = override?.concurrency ?: globalSettings.concurrency.coerceAtLeast(1),
            delay = minDelay,
            delayMax = maxDelay.coerceAtLeast(minDelay),
        )
    }

    fun randomDelayMillis(
        settings: SourceDownloadSettings,
        random: Random = Random.Default,
    ): Long {
        val minDelay = settings.delay.coerceAtLeast(0)
        val maxDelay = settings.delayMax.coerceAtLeast(minDelay)
        if (minDelay == maxDelay) return minDelay

        val exclusiveUpperBound = maxDelay + 1
        return if (exclusiveUpperBound > maxDelay) {
            random.nextLong(minDelay, exclusiveUpperBound)
        } else {
            minDelay + random.nextLong(maxDelay - minDelay)
        }
    }
}

data class ClassifiedDownloadError(
    val message: String,
    val category: String,
    val code: String,
    val retryable: Boolean,
)

object DownloadErrorClassifier {
    private const val RETRY_BASE_DELAY_MS = 3000L
    private const val RETRY_MAX_DELAY_MS = 60000L

    fun classify(error: Throwable): ClassifiedDownloadError {
        if (error is SourceAccessBlockedException) {
            return ClassifiedDownloadError(
                error.message ?: "Source blocked automated access",
                "source_blocked",
                "SOURCE_BLOCKED",
                false,
            )
        }
        val message = error.message ?: "Download failed"
        val lower = message.lowercase()
        val httpCode =
            Regex("HTTP\\s+(\\d+)")
                .find(message)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        if (httpCode != null) {
            val rateLimit = httpCode == 403 || httpCode == 429
            val retryableHttp = rateLimit || httpCode in setOf(408, 500, 502, 503, 504)
            return ClassifiedDownloadError("HTTP $httpCode", if (rateLimit) "rate_limit" else "network", httpCode.toString(), retryableHttp)
        }
        return when {
            lower.contains("cancel") || error is kotlinx.coroutines.CancellationException ->
                ClassifiedDownloadError("Cancelled", "cancelled", "CANCELLED", false)
            lower.contains("story not found") ->
                ClassifiedDownloadError(message, "missing_story", "STORY_NOT_FOUND", false)
            lower.contains("unsupported source") || lower.contains("no provider") ->
                ClassifiedDownloadError(message, "missing_provider", "NO_PROVIDER", false)
            lower.contains("no url") ->
                ClassifiedDownloadError(message, "invalid_chapter", "NO_CHAPTER_URL", false)
            lower.contains("empty") ||
                lower.contains("too short") ||
                lower.contains("no downloaded chapters") ||
                lower.contains("content not found") ->
                ClassifiedDownloadError(message, "parse", "CONTENT_TOO_SHORT", true)
            lower.contains("network") || lower.contains("timeout") || lower.contains("failed to fetch") || lower.contains("connection") ->
                ClassifiedDownloadError(message, "network", "NETWORK_ERROR", true)
            else ->
                ClassifiedDownloadError(message, "unknown", error::class.java.simpleName.ifBlank { "UNKNOWN" }, false)
        }
    }

    fun shouldAutoRetry(
        job: DownloadJob,
        error: ClassifiedDownloadError,
    ): Boolean = error.retryable && job.retryCount <= job.maxRetries.coerceAtLeast(0)

    fun retryDelayMs(job: DownloadJob): Long {
        val retryAttempt = job.retryCount.coerceAtLeast(1)
        val multiplier = 1L shl (retryAttempt - 1).coerceIn(0, 10)
        return minOf(RETRY_MAX_DELAY_MS, RETRY_BASE_DELAY_MS * multiplier)
    }
}

data class DownloadProgress(
    val pending: Int,
    val active: Int,
    val completed: Int,
    val failed: Int,
    val cancelled: Int,
    val paused: Int,
    val total: Int,
    val activeTitle: String?,
) {
    val unfinished: Int
        get() = pending + active
}
