package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped circuit breaker for user-provided cleanup regex rules (audit Rec 7).
 *
 * Kotlin/JVM regex matching has no execution timeout, so a pattern that passes
 * [RegexRuleCleanup]'s validation heuristic can still cause catastrophic backtracking (ReDoS) at
 * apply time — during download cleanup, TTS preparation, reader preparation, or preview. The
 * heuristic only inspects the literal pattern string and is bypassable.
 *
 * This breaker is a *disable-after-the-fact* safety net: it times each rule application and, after
 * [maxStrikes] consecutive slow (over [slowThresholdNanos]) or failing applications, marks the rule
 * disabled for the rest of the process. Subsequent applications skip the disabled rule instead of
 * re-running the pathological match. It is **not** a hard per-call timeout — interrupting a running
 * JVM `Matcher` mid-match is unreliable; the breaker trips on the *next* check after a slow call so
 * a rule can stall at most `maxStrikes` times before being quarantined.
 *
 * Used by both cleanup paths so convergence (audit gap 5) does not require merging the cached
 * [CleanupEngine] and the stateless [TextCleanup.regexRunner]: each path consults [isDisabled] and
 * reports outcomes through the same breaker, so a rule that misbehaves on either path is disabled
 * for both.
 *
 * Thread-safe via [ConcurrentHashMap]; the strike count is approximate (read-modify-write is not
 * atomic) which is acceptable — over-counting just trips the breaker slightly sooner.
 */
object RegexCircuitBreaker {
    /** A rule application taking longer than this is considered "slow". */
    private const val SLOW_THRESHOLD_NANOS = 250_000_000L // 250ms for a typical chapter

    /** Consecutive slow/failing applications before a rule is disabled for the session. */
    private const val MAX_STRIKES = 3

    private data class Health(
        var strikes: Int = 0,
    )

    private val health = ConcurrentHashMap<String, Health>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()

    /** Stable key for a rule across both cleanup paths (pattern + flags + appliesTo). */
    fun key(rule: RegexCleanupRule): String = "${rule.pattern}|${rule.flags}|${rule.appliesTo}"

    /** Whether [rule] has been disabled for this session and should be skipped. */
    fun isDisabled(rule: RegexCleanupRule): Boolean = disabled.contains(key(rule))

    /**
     * Reports the outcome of applying [rule]: [elapsedNanos] for a successful application, or a
     * failure if the matcher threw. Resets strikes on a fast success; bumps strikes (and disables the
     * rule at [MAX_STRIKES]) on a slow result or a failure.
     */
    fun report(
        rule: RegexCleanupRule,
        elapsedNanos: Long,
        failed: Boolean = false,
    ) {
        val k = key(rule)
        if (failed || elapsedNanos > SLOW_THRESHOLD_NANOS) {
            val h = health.computeIfAbsent(k) { Health() }
            h.strikes += 1
            if (h.strikes >= MAX_STRIKES && disabled.add(k)) {
                Timber.w(
                    "Regex cleanup rule '%s' disabled after %d slow/failing applications (pattern=%s).",
                    rule.name,
                    h.strikes,
                    rule.pattern,
                )
            }
        } else {
            health[k]?.strikes = 0
        }
    }

    /** Clears all recorded state. Intended for tests and a future "reset cleanup rules" UI action. */
    fun reset() {
        health.clear()
        disabled.clear()
    }
}
