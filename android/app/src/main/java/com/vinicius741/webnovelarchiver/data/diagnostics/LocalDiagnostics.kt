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
