package com.vinicius741.webnovelarchiver.data.diagnostics

import android.util.Log
import timber.log.Timber

/**
 * App-wide diagnostic producers: WARN+ log events (with redacted message, origin, and stack
 * trace), operation timings, process starts, and uncaught crashes. Everything lands in the
 * persistent [DiagnosticJournal]; [DiagnosticReportExporter] turns a time window of it into the
 * shareable report. With no journal installed (unit tests) recording is a no-op.
 */
object LocalDiagnostics {
    fun record(
        priority: Int,
        throwable: Throwable?,
        timestampMillis: Long = System.currentTimeMillis(),
        message: String? = null,
        origin: String? = null,
    ) {
        if (priority < Log.WARN) return
        val journal = DiagnosticJournal.active ?: return
        journal.append(logRecord(journal.sessionId, priority, throwable, timestampMillis, message, origin))
    }

    internal fun logRecord(
        sessionId: String,
        priority: Int,
        throwable: Throwable?,
        timestampMillis: Long,
        message: String?,
        origin: String?,
    ): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "k" to DiagnosticRecordKind.LOG,
            "ts" to timestampMillis,
            "sid" to sessionId,
            "level" to levelName(priority),
            "origin" to safeToken(origin, MAX_ORIGIN_LENGTH),
            "msg" to DiagnosticRedactor.redact(message),
            "error" to throwable?.let { DiagnosticRedactor.throwable(it) },
        )

    /**
     * Records one timed operation: privacy-safe static [operation] name, duration, and a
     * failure flag — enough to distinguish slow-but-successful from failing boundaries after the
     * fact, without retaining text, prompts, keys, or URLs.
     */
    fun recordOperation(
        operation: String,
        durationMillis: Long,
        failed: Boolean = false,
    ) {
        val journal = DiagnosticJournal.active ?: return
        journal.append(
            linkedMapOf(
                "k" to DiagnosticRecordKind.OPERATION,
                "ts" to System.currentTimeMillis(),
                "sid" to journal.sessionId,
                "op" to safeToken(operation),
                "ms" to durationMillis.coerceAtLeast(0L),
                "failed" to failed.takeIf { it },
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

    /** One record per process start, so a report shows restarts between events. */
    fun recordSessionStart(fields: Map<String, Any?>) {
        val journal = DiagnosticJournal.active ?: return
        journal.append(
            linkedMapOf<String, Any?>(
                "k" to DiagnosticRecordKind.SESSION,
                "ts" to System.currentTimeMillis(),
                "sid" to journal.sessionId,
            ) + fields,
        )
    }

    /** Synchronous: the process is about to die, so the record is fsynced before returning. */
    fun recordCrash(
        thread: Thread,
        throwable: Throwable,
    ) {
        val journal = DiagnosticJournal.active ?: return
        journal.appendBlocking(
            linkedMapOf(
                "k" to DiagnosticRecordKind.CRASH,
                "ts" to System.currentTimeMillis(),
                "sid" to journal.sessionId,
                "thread" to safeToken(thread.name),
                "error" to DiagnosticRedactor.throwable(throwable),
            ),
        )
    }

    /**
     * Chains a crash recorder in front of the existing default handler (which still shows the
     * system crash dialog / kills the process). Recording failures never block the original handler.
     */
    fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { recordCrash(thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    internal fun levelName(priority: Int): String =
        when (priority) {
            Log.WARN -> "W"
            Log.ERROR -> "E"
            Log.ASSERT -> "A"
            else -> priority.toString()
        }

    internal fun safeToken(
        value: String?,
        maxLength: Int = MAX_TOKEN_LENGTH,
    ): String? =
        value
            ?.take(maxLength)
            ?.map { character -> if (character.isLetterOrDigit() || character in SAFE_PUNCTUATION) character else '_' }
            ?.joinToString("")
            ?.takeIf(String::isNotBlank)

    private const val MAX_TOKEN_LENGTH = 64
    private const val MAX_ORIGIN_LENGTH = 160
    private val SAFE_PUNCTUATION = setOf('.', '_', '-', '$', ':', '(', ')')
}

/**
 * Feeds WARN+ Timber logs into [LocalDiagnostics]. Timber appends the stack trace to [message]
 * when a throwable is present; that copy is stripped because the throwable is stored structured.
 * The origin is the first stack frame outside Timber, so release reports show where the warning
 * was logged (retrace with the release's R8 mapping file).
 */
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
        val text =
            if (t !=
                null
            ) {
                message.substringBefore("\n${t.javaClass.name}").takeUnless { it.startsWith(t.javaClass.name) }
            } else {
                message
            }
        LocalDiagnostics.record(priority, t, message = text, origin = tag ?: callerOrigin())
    }

    private fun callerOrigin(): String? =
        Thread
            .currentThread()
            .stackTrace
            .firstOrNull { frame ->
                !frame.className.startsWith("java.lang.Thread") &&
                    !frame.className.startsWith("dalvik.system.") &&
                    !frame.className.startsWith("timber.log.") &&
                    !frame.className.startsWith(LocalDiagnosticTree::class.java.name) &&
                    !frame.className.startsWith(LocalDiagnostics::class.java.name)
            }?.let { frame -> "${frame.className.substringAfterLast('.')}.${frame.methodName}:${frame.lineNumber}" }
}
