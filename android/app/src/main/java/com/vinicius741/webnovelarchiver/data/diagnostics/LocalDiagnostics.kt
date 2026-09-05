package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import timber.log.Timber

/** Process-local, metadata-only warning/error ring. Log messages are intentionally never retained. */
object LocalDiagnostics {
    const val MAX_EVENTS = 200
    private val events = ArrayDeque<DiagnosticEvent>(MAX_EVENTS)

    @Synchronized
    fun record(
        priority: Int,
        throwable: Throwable?,
        timestampMillis: Long = System.currentTimeMillis(),
    ) {
        if (priority < Log.WARN) return
        if (events.size == MAX_EVENTS) events.removeFirst()
        events.addLast(
            DiagnosticEvent(
                timestampMillis = timestampMillis,
                priority = priority,
                throwableType = safeToken(throwable?.javaClass?.simpleName),
            ),
        )
    }

    /**
     * Records one timed operation (R30): privacy-safe static [operation] name, duration, and a
     * failure flag — enough to distinguish slow-but-successful from failing boundaries after the
     * fact, without retaining text, prompts, keys, or URLs.
     */
    @Synchronized
    fun recordOperation(
        operation: String,
        durationMillis: Long,
        failed: Boolean = false,
    ) {
        if (events.size == MAX_EVENTS) events.removeFirst()
        events.addLast(
            DiagnosticEvent(
                timestampMillis = System.currentTimeMillis(),
                priority = if (failed) Log.WARN else Log.INFO,
                throwableType = null,
                operation = safeToken(operation),
                durationMillis = durationMillis.coerceAtLeast(0L),
                failed = failed,
            ),
        )
    }

    /** Measures [block] and records it under [operation]; failures rethrow after being recorded. */
    inline fun <T> measuring(
        operation: String,
        block: () -> T,
    ): T {
        val startedAt = System.nanoTime() / 1_000_000L
        return try {
            block().also { recordOperation(operation, System.nanoTime() / 1_000_000L - startedAt, failed = false) }
        } catch (error: Throwable) {
            recordOperation(operation, System.nanoTime() / 1_000_000L - startedAt, failed = true)
            throw error
        }
    }

    @Synchronized
    fun snapshot(): List<DiagnosticEvent> = events.toList()

    @Synchronized
    internal fun clear() = events.clear()

    internal fun safeToken(value: String?): String? =
        value
            ?.take(MAX_TOKEN_LENGTH)
            ?.map { character -> if (character.isLetterOrDigit() || character in SAFE_PUNCTUATION) character else '_' }
            ?.joinToString("")
            ?.takeIf(String::isNotBlank)

    private const val MAX_TOKEN_LENGTH = 64
    private val SAFE_PUNCTUATION = setOf('.', '_', '-', '$')
}

class LocalDiagnosticTree : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= Log.WARN

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        LocalDiagnostics.record(priority, t)
    }
}
