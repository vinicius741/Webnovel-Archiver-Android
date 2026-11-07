package com.vinicius741.webnovelarchiver.data.diagnostics

import java.util.concurrent.atomic.AtomicLong

/**
 * Structured Cloudflare-bypass / source-access events, recorded directly at pipeline call sites
 * (not via Timber, so events stay structured and lossless) and persisted in the shared
 * [DiagnosticJournal] as `k = "src"` records. The dump for diagnostics is [BypassLogExporter];
 * the Settings "Share Source Access Logs" row is the user surface, and the full
 * [DiagnosticReportExporter] report includes the same events.
 *
 * Privacy by omission: events carry hosts, enums, decisions, timings, and fixed-vocabulary notes.
 * There are no URLs, paths, story/chapter titles, cookies, or bodies — callers must not pass them,
 * and [sanitize] strips anything but a small character set as a backstop.
 */
object BypassEventLog {
    private val sequence = AtomicLong(0)
    private val ids = AtomicLong(0)

    fun record(
        category: BypassEventCategory,
        type: String,
        host: String? = null,
        vararg fields: Pair<String, Any?>,
    ) {
        val journal = DiagnosticJournal.active ?: return
        val event = build(category, type, host, *fields)
        journal.append(linkedMapOf<String, Any?>("k" to DiagnosticRecordKind.SOURCE, "sid" to journal.sessionId) + event.toJsonMap())
    }

    /** Builds the sanitized event without recording it; [record] is the production entry point. */
    internal fun build(
        category: BypassEventCategory,
        type: String,
        host: String? = null,
        vararg fields: Pair<String, Any?>,
    ): BypassEvent =
        BypassEvent(
            seq = sequence.incrementAndGet(),
            timestampMillis = System.currentTimeMillis(),
            category = category,
            type = sanitizeText(type),
            host = host?.let(::sanitizeHost),
            fields = fields.toMap().filterValues { it != null }.mapValues { (_, value) -> sanitize(value) },
        )

    /** Short correlation ids: one per HTTP attempt (`a…`), one per WebView render (`r…`). */
    fun nextId(prefix: String): String = "$prefix${ids.incrementAndGet()}"

    private fun sanitizeHost(value: String): String =
        value
            .lowercase()
            .filter {
                it.isLetterOrDigit() || it == '.' || it == '-'
            }.take(MAX_FIELD_LENGTH)
            .ifBlank { "unknown" }

    private fun sanitize(value: Any?): Any? = if (value is String) sanitizeText(value) else value

    private fun sanitizeText(value: String): String =
        value
            .map { character -> if (character.isLetterOrDigit() || character in SAFE_PUNCTUATION) character else '_' }
            .joinToString("")
            .take(MAX_FIELD_LENGTH)

    private const val MAX_FIELD_LENGTH = 64
    private val SAFE_PUNCTUATION = setOf('.', '_', '-', ':')
}

enum class BypassEventCategory(
    val wire: String,
) {
    NET("net"),
    CF("cf"),
    DL("dl"),
}

data class BypassEvent(
    val seq: Long,
    val timestampMillis: Long,
    val category: BypassEventCategory,
    val type: String,
    val host: String?,
    val fields: Map<String, Any?>,
) {
    fun toJsonMap(): LinkedHashMap<String, Any?> =
        linkedMapOf(
            "seq" to seq,
            "ts" to timestampMillis,
            "cat" to category.wire,
            "type" to type,
            "host" to host,
            *fields.map { (key, value) -> key to value }.toTypedArray(),
        ).filterValues { it != null } as LinkedHashMap<String, Any?>
}
