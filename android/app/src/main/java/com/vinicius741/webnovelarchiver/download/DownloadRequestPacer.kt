package com.vinicius741.webnovelarchiver.download

import com.vinicius741.webnovelarchiver.domain.model.SourceDownloadSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

/** A queue mutation made this job ineligible while it was waiting to start a request. */
internal class DownloadJobInactiveException(
    jobId: String,
) : CancellationException("Download $jobId is no longer active")

/**
 * The live wait for the next chapter-content request from [providerName].
 *
 * This state is intentionally transient. Consumers should calculate the displayed remaining time
 * from [nextRequestAtMillis] instead of persisting or decrementing a counter.
 */
data class DownloadPacingSnapshot(
    val providerName: String,
    val storyId: String,
    val jobId: String,
    val chapterTitle: String,
    val nextRequestAtMillis: Long,
)

/**
 * Applies user-configured pacing to chapter downloads without changing shared source-network
 * behavior. Request starts for one source are serialized; unrelated sources remain independent.
 */
class DownloadRequestPacer(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val randomBetween: (Long, Long) -> Long = { minimum, maximum ->
        when {
            maximum <= minimum -> minimum
            maximum == Long.MAX_VALUE -> minimum + Random.nextLong(maximum - minimum)
            else -> Random.nextLong(minimum, maximum + 1L)
        }
    },
) {
    private data class DelayRange(
        val minimumMillis: Long,
        val maximumMillis: Long,
    )

    private data class SourceState(
        val mutex: Mutex = Mutex(),
        var lastRequestStartedAtMillis: Long? = null,
    )

    private val sourceStates = ConcurrentHashMap<String, SourceState>()
    private val mutableSnapshots = MutableStateFlow<Map<String, DownloadPacingSnapshot>>(emptyMap())

    val snapshots: StateFlow<Map<String, DownloadPacingSnapshot>> = mutableSnapshots.asStateFlow()

    /**
     * Waits until this source's next configured download slot and atomically claims it.
     *
     * The first request for each source starts immediately. While a later request is waiting,
     * [settingsProvider] is checked at least once per second so a reduced delay does not leave the
     * worker sleeping under stale settings. [claimSourcePermission] runs while the per-source lock
     * is held, and the actual start timestamp is recorded only after that process-wide claim
     * succeeds. This prevents a source cooldown from bunching already-paced requests when it ends.
     */
    suspend fun awaitTurn(
        providerName: String,
        storyId: String,
        jobId: String,
        chapterTitle: String,
        claimSourcePermission: suspend () -> Unit = {},
        settingsProvider: () -> SourceDownloadSettings,
    ) {
        val state = sourceStates.getOrPut(providerName) { SourceState() }
        state.mutex.withLock {
            try {
                val previousStart = state.lastRequestStartedAtMillis
                var delayRange = settingsProvider().delayRange()
                if (previousStart != null) {
                    var selectedDelay = selectDelay(delayRange)
                    while (true) {
                        val nextRequestAt = previousStart.saturatedPlus(selectedDelay)
                        val now = nowMillis()
                        if (nextRequestAt <= now) break

                        publishWaiting(
                            DownloadPacingSnapshot(
                                providerName = providerName,
                                storyId = storyId,
                                jobId = jobId,
                                chapterTitle = chapterTitle,
                                nextRequestAtMillis = nextRequestAt,
                            ),
                        )
                        sleep(minOf(nextRequestAt - now, SETTINGS_RECHECK_INTERVAL_MILLIS))

                        val latestRange = settingsProvider().delayRange()
                        if (latestRange != delayRange) {
                            delayRange = latestRange
                            selectedDelay = selectDelay(delayRange)
                        }
                    }
                }

                claimSourcePermission()
                // Re-check after a possibly long source cooldown. A pause/cancel during that wait
                // must abort before the HTTP attempt and must not consume a download pacing slot.
                settingsProvider()
                state.lastRequestStartedAtMillis = nowMillis()
            } finally {
                clearWaiting(providerName, jobId)
            }
        }
    }

    private fun selectDelay(range: DelayRange): Long =
        randomBetween(range.minimumMillis, range.maximumMillis)
            .coerceIn(range.minimumMillis, range.maximumMillis)

    private fun publishWaiting(snapshot: DownloadPacingSnapshot) {
        mutableSnapshots.update { current ->
            if (current[snapshot.providerName] == snapshot) {
                current
            } else {
                current + (snapshot.providerName to snapshot)
            }
        }
    }

    private fun clearWaiting(
        providerName: String,
        jobId: String,
    ) {
        mutableSnapshots.update { current ->
            if (current[providerName]?.jobId == jobId) {
                current - providerName
            } else {
                current
            }
        }
    }

    private fun SourceDownloadSettings.delayRange(): DelayRange {
        val minimum = delay.coerceAtLeast(0L)
        return DelayRange(
            minimumMillis = minimum,
            maximumMillis = delayMax.coerceAtLeast(minimum),
        )
    }

    private fun Long.saturatedPlus(other: Long): Long =
        if (this > Long.MAX_VALUE - other) {
            Long.MAX_VALUE
        } else {
            this + other
        }

    private companion object {
        const val SETTINGS_RECHECK_INTERVAL_MILLIS = 1_000L
    }
}
