package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.DownloadJob
import com.vinicius741.webnovelarchiver.domain.model.DownloadJobStatus
import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.HttpNetworkException
import com.vinicius741.webnovelarchiver.source.network.NetworkOfflineException
import com.vinicius741.webnovelarchiver.source.network.NetworkParseException
import com.vinicius741.webnovelarchiver.source.network.NetworkTimeoutException
import com.vinicius741.webnovelarchiver.source.network.NetworkTransportException
import com.vinicius741.webnovelarchiver.source.network.RateLimitNetworkException
import com.vinicius741.webnovelarchiver.source.network.SourceAccessBlockedException
import kotlin.random.Random

/**
 * Download scheduling, error classification, and progress.
 * These are the pure helpers the [DownloadEngine] process loop relies on; they stay together because
 * the scheduler, classifier, and progress shape are tightly coupled to job lifecycle.
 */
object DownloadScheduler {
    /**
     * How often the process loop re-checks a blocked source's pending jobs. Short enough that a
     * solved challenge resumes the queue almost immediately no matter which recovery path the user
     * took (solve activity, queue retry, or a Settings session reset); the scheduler skips the
     * source while the circuit stays open, so the recheck costs one queue read.
     */
    const val BLOCKED_SOURCE_RECHECK_MILLIS = 60_000L

    // The scheduling inputs are individually meaningful and all call sites use named arguments;
    // bundling them would obscure the pure-function shape the tests exercise directly.
    @Suppress("LongParameterList")
    fun selectEligibleJobs(
        jobs: List<DownloadJob>,
        now: Long,
        maxParallelSources: Int,
        activeCounts: Map<String, Int>,
        nextAllowedAt: Map<String, Long>,
        lastScheduledSource: String?,
        providerNameForJob: (DownloadJob) -> String?,
        blockedSources: Set<String> = emptySet(),
    ): List<DownloadJob> {
        val activeSources = activeCounts.filterValues { it > 0 }.keys
        val availableSourceSlots = maxParallelSources.coerceAtLeast(1) - activeSources.size
        if (availableSourceSlots <= 0) return emptyList()

        val queuedSourceOrder = linkedSetOf<String>()
        val eligibleBySource = linkedMapOf<String, DownloadJob>()
        jobs.forEach { job ->
            if (job.status != DownloadJobStatus.Pending.wire) return@forEach
            val source = providerNameForJob(job) ?: return@forEach
            queuedSourceOrder += source
            if (job.nextRetryAt != null && job.nextRetryAt!! > now) return@forEach
            // A source under manual verification waits for a human: keep its jobs pending instead of
            // letting each one start, hit the open circuit, and burn to a terminal failure.
            if (source in activeSources || source in blockedSources || (nextAllowedAt[source] ?: 0L) > now) return@forEach
            eligibleBySource.putIfAbsent(source, job)
        }
        if (eligibleBySource.isEmpty()) return emptyList()

        // Rotate the stable queue-derived source order after the most recently scheduled source.
        // This prevents two busy sources from permanently starving a third source while still
        // preserving FIFO order inside each individual source lane.
        val sourceOrder = queuedSourceOrder.toList()
        val cursorIndex = sourceOrder.indexOf(lastScheduledSource)
        val rotatedOrder =
            if (cursorIndex < 0) {
                sourceOrder
            } else {
                sourceOrder.drop(cursorIndex + 1) + sourceOrder.take(cursorIndex + 1)
            }
        return rotatedOrder.filter(eligibleBySource::containsKey).take(availableSourceSlots).map(eligibleBySource::getValue)
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
        val provider =
            SourceRegistry.getById(providerName)
                ?: SourceRegistry.all().firstOrNull { it.name == providerName }
        val override = provider?.let { sourceSettings[it.id] ?: sourceSettings[it.name] } ?: sourceSettings[providerName]
        val minDelay = (override?.delay ?: globalSettings.delay).coerceAtLeast(0)
        val maxDelay = override?.delayMax ?: globalSettings.delayMax
        val requestedConcurrency = override?.concurrency ?: globalSettings.concurrency.coerceAtLeast(1)
        val concurrency =
            provider
                ?.maximumDownloadConcurrency
                ?.let { requestedConcurrency.coerceAtMost(it) }
                ?: requestedConcurrency
        return SourceDownloadSettings(
            concurrency = concurrency,
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
    val retryAfterMillis: Long? = null,
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
        if (error is RateLimitNetworkException) {
            return ClassifiedDownloadError(
                "HTTP ${error.statusCode}",
                "rate_limit",
                error.statusCode.toString(),
                true,
                error.retryAfterMillis,
            )
        }
        if (error is HttpNetworkException) {
            val retryable = error.statusCode in setOf(408, 500, 502, 503, 504)
            return ClassifiedDownloadError("HTTP ${error.statusCode}", "network", error.statusCode.toString(), retryable)
        }
        if (error is NetworkTimeoutException) {
            return ClassifiedDownloadError(error.message ?: "Network timeout", "network", "TIMEOUT", true)
        }
        if (error is NetworkOfflineException) {
            return ClassifiedDownloadError(error.message ?: "Network unavailable", "network", "OFFLINE", true)
        }
        if (error is NetworkTransportException) {
            return ClassifiedDownloadError(error.message ?: "Network error", "network", "NETWORK_ERROR", true)
        }
        if (error is NetworkParseException) {
            return ClassifiedDownloadError(error.message ?: "Content parse failed", "parse", "CONTENT_TOO_SHORT", true)
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

    fun retryDelayMs(
        job: DownloadJob,
        error: ClassifiedDownloadError,
    ): Long = maxOf(retryDelayMs(job), error.retryAfterMillis ?: 0L)
}

/** Source-wide queue transitions used by the Cloudflare circuit breaker and rate-limit cooldown. */
object DownloadSourceFailurePlanning {
    /**
     * One transaction when the manual-verification circuit opens: in-flight jobs fail as
     * `source_blocked` (the solve flow keys off that category), and pending jobs are deferred to a
     * near-term recheck instead of being scheduled against the open circuit one by one. The
     * scheduler skips blocked sources entirely, so the recheck only has to notice that verification
     * succeeded and the whole remaining queue resumes on its own. Note [DownloadJobStatus.activeWires]
     * includes Pending — matching it here would fail the whole queue, which is exactly the drain
     * this function exists to prevent.
     */
    fun blockSource(
        jobs: List<DownloadJob>,
        providerName: String,
        message: String?,
        recheckAtMillis: Long,
        providerNameForJob: (DownloadJob) -> String?,
    ): List<DownloadJob> =
        jobs.onEach { job ->
            if (providerNameForJob(job) != providerName) return@onEach
            if (job.status == DownloadJobStatus.Downloading.wire) {
                job.status = DownloadJobStatus.Failed.wire
                job.error = message
                job.errorCategory = "source_blocked"
                job.errorCode = "SOURCE_BLOCKED"
                job.nextRetryAt = null
            } else if (job.status == DownloadJobStatus.Pending.wire) {
                job.nextRetryAt = maxOf(job.nextRetryAt ?: 0L, recheckAtMillis)
            }
        }

    fun deferPendingJobs(
        jobs: List<DownloadJob>,
        providerName: String,
        retryAt: Long,
        providerNameForJob: (DownloadJob) -> String?,
    ): List<DownloadJob> =
        jobs.onEach { job ->
            if (job.status == DownloadJobStatus.Pending.wire && providerNameForJob(job) == providerName) {
                job.nextRetryAt = maxOf(job.nextRetryAt ?: 0L, retryAt)
            }
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
    /** Jobs failed as source_blocked right now; 0 means the manual circuit is closed or cleared. */
    val sourceBlocked: Int = 0,
    /** Pending jobs held by an open manual circuit (no failed jobs left to key the solve flow off). */
    val blockedPending: Int = 0,
    /** A representative chapter URL for the pending-held blocked source; null when none are held. */
    val blockedPendingUrl: String? = null,
) {
    val unfinished: Int
        get() = pending + active
}
