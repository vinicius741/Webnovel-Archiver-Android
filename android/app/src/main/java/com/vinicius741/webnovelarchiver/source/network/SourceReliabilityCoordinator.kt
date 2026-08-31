package com.vinicius741.webnovelarchiver.source.network

import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.BypassEventLog
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

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
 * Process-wide source-safety, circuit-breaker, and browser-transport state, shared by every
 * consumer via [com.vinicius741.webnovelarchiver.app.AppContainer] so none can independently drain
 * a source's budget. User download pacing belongs to the download layer; always wait outside the
 * per-host mutex.
 */
@Suppress("TooManyFunctions")
class SourceReliabilityCoordinator(
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val onStateChanged: () -> Unit = {},
) {
    private data class HostState(
        val canonicalHost: String,
        var nextAllowedAt: Long = 0L,
        var cooldownUntil: Long = 0L,
        var manualVerificationRequired: Boolean = false,
        var browserTransportUntil: Long = 0L,
        var adaptiveMinimumGapMillis: Long = 0L,
        var consecutiveSuccesses: Int = 0,
        val recentRequests: ArrayDeque<Long> = ArrayDeque(),
        var requestCount: Long = 0L,
        var challengeCount: Long = 0L,
        var rateLimitCount: Long = 0L,
        var browserRenderCount: Long = 0L,
    )

    private val states = ConcurrentHashMap<String, HostState>()

    /**
     * Only state that must survive process death (open manual circuit, sticky Chromium window,
     * adaptive gap). Rolling-window bookkeeping is intentionally not persisted — it self-heals.
     */
    fun persistableStates(): List<PersistedHostReliability> =
        states.entries
            .map { (key, state) ->
                synchronized(state) {
                    PersistedHostReliability(
                        key = key,
                        canonicalHost = state.canonicalHost,
                        manualVerificationRequired = state.manualVerificationRequired,
                        cooldownUntil = state.cooldownUntil,
                        browserTransportUntil = state.browserTransportUntil,
                        adaptiveMinimumGapMillis = state.adaptiveMinimumGapMillis,
                        requestCount = state.requestCount,
                        challengeCount = state.challengeCount,
                        rateLimitCount = state.rateLimitCount,
                        browserRenderCount = state.browserRenderCount,
                    )
                }
            }.sortedBy { it.key }

    /** Restores persisted state; past timestamps expire on their own. Skips [onStateChanged] to avoid a redundant startup write. */
    fun restore(persisted: List<PersistedHostReliability>) {
        persisted.forEach { host ->
            val state = stateFor(host.key)
            synchronized(state) {
                state.manualVerificationRequired = host.manualVerificationRequired
                state.cooldownUntil = if (host.manualVerificationRequired) Long.MAX_VALUE else host.cooldownUntil
                state.browserTransportUntil = maxOf(state.browserTransportUntil, host.browserTransportUntil)
                state.adaptiveMinimumGapMillis = host.adaptiveMinimumGapMillis
                state.requestCount = host.requestCount
                state.challengeCount = host.challengeCount
                state.rateLimitCount = host.rateLimitCount
                state.browserRenderCount = host.browserRenderCount
            }
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
                    state.nextAllowedAt = now + minimumGap
                    state.recentRequests.addLast(now)
                    state.requestCount += 1L
                    0L
                }
            if (manualBlock) {
                BypassEventLog.record(BypassEventCategory.CF, "manual_block_throw", state.canonicalHost)
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
                BypassEventLog.record(BypassEventCategory.CF, "transport_sticky_refresh", state.canonicalHost)
            }
            if (state.consecutiveSuccesses >= SUCCESSES_BEFORE_RECOVERY) {
                state.adaptiveMinimumGapMillis =
                    (state.adaptiveMinimumGapMillis * 3L / 4L)
                        .coerceAtLeast(policy.minimumRequestGapMillis.coerceAtLeast(0L))
                state.consecutiveSuccesses = 0
            }
        }
        if (browserRendered) onStateChanged()
    }

    /** Opens a timed circuit and returns the queue-level cooldown that should be persisted. */
    fun recordRateLimit(
        host: String,
        policy: SourceNetworkPolicy,
        retryAfterMillis: Long?,
    ): Long {
        val state = stateFor(host)
        val cooldown =
            synchronized(state) {
                state.rateLimitCount += 1L
                state.consecutiveSuccesses = 0
                val currentFloor = effectiveMinimumGap(state, policy).coerceAtLeast(policy.baseRetryDelayMillis)
                state.adaptiveMinimumGapMillis =
                    (currentFloor * 2L).coerceAtMost(policy.maximumAdaptiveGapMillis.coerceAtLeast(currentFloor))
                val computed =
                    max(retryAfterMillis ?: 0L, state.adaptiveMinimumGapMillis * 2L)
                        .coerceAtLeast(policy.baseRetryDelayMillis)
                        .coerceAtMost(policy.maximumRetryAfterMillis)
                state.cooldownUntil = max(state.cooldownUntil, nowMillis() + computed)
                BypassEventLog.record(
                    BypassEventCategory.CF,
                    "rate_limit_recorded",
                    state.canonicalHost,
                    "cooldownMs" to computed,
                    "retryAfterMs" to retryAfterMillis,
                )
                computed
            }
        onStateChanged()
        return cooldown
    }

    /** A detected challenge immediately switches future requests to Chromium for this session. */
    fun recordChallengeDetected(host: String) {
        val state = stateFor(host)
        synchronized(state) {
            state.challengeCount += 1L
            state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
            state.consecutiveSuccesses = 0
            BypassEventLog.record(BypassEventCategory.CF, "challenge_detected", state.canonicalHost)
            BypassEventLog.record(BypassEventCategory.CF, "transport_sticky_enter", state.canonicalHost)
        }
        onStateChanged()
    }

    /** A browser render that still cannot pass requires one user-mediated verification for the host. */
    fun requireManualVerification(host: String) {
        val state = stateFor(host)
        synchronized(state) {
            if (!state.manualVerificationRequired) {
                BypassEventLog.record(BypassEventCategory.CF, "circuit_opened", state.canonicalHost)
            }
            state.manualVerificationRequired = true
            state.cooldownUntil = Long.MAX_VALUE
        }
        onStateChanged()
    }

    /** True while the manual circuit is open; [key] may be a host or provider id — state keys are provider ids. */
    fun isManualVerificationRequired(key: String): Boolean {
        val state = stateFor(key)
        synchronized(state) {
            return state.manualVerificationRequired
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
            BypassEventLog.record(
                BypassEventCategory.CF,
                "access_cleared",
                state.canonicalHost,
                "keepBrowserTransport" to keepBrowserTransport,
            )
            if (keepBrowserTransport) {
                state.browserTransportUntil = nowMillis() + BROWSER_TRANSPORT_TTL_MILLIS
            } else {
                state.browserTransportUntil = 0L
            }
        }
        onStateChanged()
    }

    /**
     * Keeps cooldowns and the request budget across network changes, and extends the sticky
     * Chromium window to force a fresh browser load on the next request.
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
        states.values
            .map { state ->
                synchronized(state) {
                    val now = nowMillis()
                    SourceReliabilitySnapshot(
                        host = state.canonicalHost,
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

    /** Host aliases share the provider's stable budget — closer to Cloudflare zones than per-subdomain accounting. */
    private fun stateFor(host: String): HostState {
        val normalizedHost = host.lowercase(Locale.US).removePrefix("www.")
        val provider = SourceRegistry.providerForHost(normalizedHost)
        val stateKey = provider?.id ?: normalizedHost
        val canonicalHost =
            provider
                ?.descriptor
                ?.hosts
                ?.firstOrNull()
                ?.lowercase(Locale.US)
                ?.removePrefix("www.")
                ?: normalizedHost
        return states.getOrPut(stateKey) { HostState(canonicalHost) }
    }

    private fun effectiveMinimumGap(
        state: HostState,
        policy: SourceNetworkPolicy,
    ): Long = max(policy.minimumRequestGapMillis.coerceAtLeast(0L), state.adaptiveMinimumGapMillis)

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
    }
}
