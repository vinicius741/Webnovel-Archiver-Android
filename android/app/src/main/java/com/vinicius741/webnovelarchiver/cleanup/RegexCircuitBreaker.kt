package com.vinicius741.webnovelarchiver.cleanup

import com.vinicius741.webnovelarchiver.domain.model.RegexCleanupRule
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * Session-scoped breaker against catastrophic backtracking (ReDoS) in user cleanup regexes:
 * validation is heuristic and bypassable, and JVM regex matching has no timeout. Disables a rule
 * after repeated slow/failing applications rather than acting as a hard timeout — interrupting a
 * running Matcher is unreliable. Shared by both cleanup paths; strike counts are approximate,
 * which only trips the breaker slightly sooner.
 */
object RegexCircuitBreaker {
    private const val SLOW_THRESHOLD_NANOS = 250_000_000L // 250ms for a typical chapter

    private const val MAX_STRIKES = 3

    private data class Health(
        var strikes: Int = 0,
    )

    private val health = ConcurrentHashMap<String, Health>()
    private val disabled = ConcurrentHashMap.newKeySet<String>()

    fun key(rule: RegexCleanupRule): String = "${rule.pattern}|${rule.flags}|${rule.appliesTo}"

    fun isDisabled(rule: RegexCleanupRule): Boolean = disabled.contains(key(rule))

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

    /** Clears all state; for tests and a future reset-rules UI action. */
    fun reset() {
        health.clear()
        disabled.clear()
    }
}
