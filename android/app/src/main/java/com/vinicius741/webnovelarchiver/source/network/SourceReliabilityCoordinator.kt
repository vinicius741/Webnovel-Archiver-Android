package com.vinicius741.webnovelarchiver.source.network

import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.random.Random

/** Privacy-safe, aggregate state for source-network diagnostics and Settings. */
data class SourceReliabilitySnapshot(
    val host: String,
    val browserTransportActive: Boolean,
    val manualVerificationRequired: Boolean,
    val cooldownRemainingMillis: Long,
    val effectiveMinimumGapMillis: Long,
    val requestCount: Long,
    val challengeCount: Long,
    val rateLimitCount: Long,
    val browserRenderCount: Long,
)

/**
 * Process-wide pacing, circuit-breaker, and browser-transport state for source traffic.
 *
 * Every network consumer shares one instance through [com.vinicius741.webnovelarchiver.app.AppContainer],
 * so update sync, downloads, cover fetches, and retries cannot independently consume the same
 * source's request budget. Waiting always occurs outside the per-host mutex.
 */
@Suppress("TooManyFunctions")
class SourceReliabilityCoordinator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val randomBetween: (Long, Long) -> Long = { minimum, maximum ->
        if (maximum <= minimum) {
            minimum
        } else if (maximum == Long.MAX_VALUE) {
            minimum + Random.nextLong(maximum - minimum)
        } else {
            Random.nextLong(minimum, maximum + 1L)
        }
    },
) {
    private data class HostState(
        var nextAllowedAt: Long = 0L,
        var cooldownUntil: Long = 0L,
        var manualVerificationRequired: Boolean = false,
        var browserTransportUntil: Long = 0L,
        var configuredMinimumGapMillis: Long? = null,
        var configuredMaximumGapMillis: Long? = null,
        var adaptiveMinimumGapMillis: Long = 0L,
        var consecutiveSuccesses: Int = 0,
        val recentRequests: ArrayDeque<Long> = ArrayDeque(),
        var requestCount: Long = 0L,
        var challengeCount: Long = 0L,
        var rateLimitCount: Long = 0L,
        var browserRenderCount: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, HostState>()

    fun configurePacing(
        host: String,
        minimumGapMillis: Long,
        maximumGapMillis: Long,
    ) {
        val state = stateFor(host)
        synchronized(state) {
            val minimum = minimumGapMillis.coerceIn(0L, MAX_CONFIGURED_GAP_MILLIS)
            state.configuredMinimumGapMillis = minimum
            state.configuredMaximumGapMillis = maximumGapMillis.coerceIn(minimum, MAX_CONFIGURED_GAP_MILLIS)
        }
    }

    /** Waits for both the cooldown and rolling request budget, then atomically claims one slot. */
    suspend fun awaitPermission(
        url: String,
        host: String,
        policy: SourceNetworkPolicy,
    ) {
        val state = stateFor(host)
        while (true) {
            var manualBlock = false
            val waitMillis =
                synchronized(state) {
                    val now = nowMillis()
                    manualBlock = state.manualVerificationRequired
                    if (manualBlock) return@synchronized 0L

                    pruneRequestWindow(state, now, policy.requestWindowMillis)
                    val windowWait =
                        if (state.recentRequests.size >= policy.maximumRequestsPerWindow.coerceAtLeast(1)) {
                            (state.recentRequests.first() + policy.requestWindowMillis - now).coerceAtLeast(0L)
                        } else {
                            0L
                        }
                    val waitUntil = max(max(state.nextAllowedAt, state.cooldownUntil), now + windowWait)
                    if (waitUntil > now) return@synchronized waitUntil - now

                    val minimumGap = effectiveMinimumGap(state, policy)
                    val maximumGap = effectiveMaximumGap(state, minimumGap)
                    state.nextAllowedAt = now + randomBetween(minimumGap, maximumGap)
                    state.recentRequests.addLast(now)
                    state.requestCount += 1L
                    0L
                }
            if (manualBlock) {
                throw SourceAccessBlockedException(url, manualVerificationRequired = true)
            }
            if (waitMillis <= 0L) return
            sleep(waitMillis)
        }
    }

    fun recordSuccess(
        host: String,
        policy: SourceNetworkPolicy,
        browserRendered: Boolean = false,
    ) {
        val state = stateFor(host)
        synchronized(state) {
            state.consecutiveSuccesses += 1
            if (browserRendered) {
                state.browserRenderCount += 1L
                state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
                state.manualVerificationRequired = false
                state.cooldownUntil = 0L
            }
            if (state.consecutiveSuccesses >= SUCCESSES_BEFORE_RECOVERY) {
                state.adaptiveMinimumGapMillis =
                    (state.adaptiveMinimumGapMillis * 3L / 4L)
                        .coerceAtLeast(policy.minimumRequestGapMillis.coerceAtLeast(0L))
                state.consecutiveSuccesses = 0
            }
        }
    }

    /** Opens a timed circuit and returns the queue-level cooldown that should be persisted. */
    fun recordRateLimit(
        host: String,
        policy: SourceNetworkPolicy,
        retryAfterMillis: Long?,
    ): Long {
        val state = stateFor(host)
        synchronized(state) {
            state.rateLimitCount += 1L
            state.consecutiveSuccesses = 0
            val currentFloor = effectiveMinimumGap(state, policy).coerceAtLeast(policy.baseRetryDelayMillis)
            state.adaptiveMinimumGapMillis =
                (currentFloor * 2L).coerceAtMost(policy.maximumAdaptiveGapMillis.coerceAtLeast(currentFloor))
            val cooldown =
                max(retryAfterMillis ?: 0L, state.adaptiveMinimumGapMillis * 2L)
                    .coerceAtLeast(policy.baseRetryDelayMillis)
                    .coerceAtMost(policy.maximumRetryAfterMillis)
            state.cooldownUntil = max(state.cooldownUntil, nowMillis() + cooldown)
            return cooldown
        }
    }

    /** A detected challenge immediately switches future requests to Chromium for this session. */
    fun recordChallengeDetected(host: String) {
        val state = stateFor(host)
        synchronized(state) {
            state.challengeCount += 1L
            state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
            state.consecutiveSuccesses = 0
        }
    }

    /** A browser render that still cannot pass requires one user-mediated verification for the host. */
    fun requireManualVerification(host: String) {
        val state = stateFor(host)
        synchronized(state) {
            state.manualVerificationRequired = true
            state.cooldownUntil = Long.MAX_VALUE
        }
    }

    fun browserTransportActive(host: String): Boolean {
        val state = stateFor(host)
        synchronized(state) {
            return state.browserTransportUntil > nowMillis()
        }
    }

    /** Called after interactive verification or an explicit source-session reset. */
    fun clearAccessBlock(
        host: String,
        keepBrowserTransport: Boolean = true,
    ) {
        val state = stateFor(host)
        synchronized(state) {
            state.manualVerificationRequired = false
            state.cooldownUntil = 0L
            state.consecutiveSuccesses = 0
            if (keepBrowserTransport) {
                state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
            } else {
                state.browserTransportUntil = 0L
            }
        }
    }

    /**
     * Network changes invalidate prepared page content, but do not erase server-directed cooldowns
     * or the rolling request budget. Retaining sticky Chromium mode makes the next request perform a
     * fresh browser load without first probing the source through the rejected native fingerprint.
     */
    fun onNetworkChanged() {
        states.values.forEach { state ->
            synchronized(state) {
                if (state.browserTransportUntil > nowMillis()) {
                    state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
                }
            }
        }
    }

    fun snapshots(): List<SourceReliabilitySnapshot> =
        states.entries
            .map { (host, state) ->
                synchronized(state) {
                    val now = nowMillis()
                    SourceReliabilitySnapshot(
                        host = host,
                        browserTransportActive = state.browserTransportUntil > now,
                        manualVerificationRequired = state.manualVerificationRequired,
                        cooldownRemainingMillis =
                            if (state.manualVerificationRequired) {
                                Long.MAX_VALUE
                            } else {
                                (state.cooldownUntil - now).coerceAtLeast(0L)
                            },
                        effectiveMinimumGapMillis = state.adaptiveMinimumGapMillis,
                        requestCount = state.requestCount,
                        challengeCount = state.challengeCount,
                        rateLimitCount = state.rateLimitCount,
                        browserRenderCount = state.browserRenderCount,
                    )
                }
            }.sortedBy { it.host }

    private fun stateFor(host: String): HostState = states.getOrPut(host.lowercase(Locale.US).removePrefix("www.")) { HostState() }

    private fun effectiveMinimumGap(
        state: HostState,
        policy: SourceNetworkPolicy,
    ): Long =
        max(
            max(policy.minimumRequestGapMillis.coerceAtLeast(0L), state.configuredMinimumGapMillis ?: 0L),
            state.adaptiveMinimumGapMillis,
        )

    private fun effectiveMaximumGap(
        state: HostState,
        minimumGap: Long,
    ): Long = (state.configuredMaximumGapMillis ?: minimumGap).coerceAtLeast(minimumGap)

    private fun pruneRequestWindow(
        state: HostState,
        now: Long,
        windowMillis: Long,
    ) {
        val threshold = now - windowMillis.coerceAtLeast(1L)
        while (state.recentRequests.firstOrNull()?.let { it <= threshold } == true) {
            state.recentRequests.removeFirst()
        }
    }

    private companion object {
        const val SUCCESSES_BEFORE_RECOVERY = 8
        const val BROWSER_TRANSPORT_TTL_MILLIS = 30L * 60L * 1_000L
        const val MAX_CONFIGURED_GAP_MILLIS = 30L * 60L * 1_000L
    }
}
