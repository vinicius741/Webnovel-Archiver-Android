package com.vinicius741.webnovelarchiver.data.diagnostics

/**
 * Pure rules for the persistent activity log: how an event becomes one JSON line, how values are
 * bounded and scrubbed. Kept free of Android and I/O so every rule is unit-testable. File naming and
 * retention live in [ActivityLogFiles].
 */
object ActivityLogPlanning {
    const val MAX_STRING_LENGTH = 300
    const val MAX_STACK_LENGTH = 2000
    const val MAX_COLLECTION_ITEMS = 25
    const val MAX_MAP_DEPTH = 3
    const val REDACTED = "[redacted]"

    private val reservedKeys = setOf("ts", "seq", "sid", "cat", "type")
    private val secretKeyWords = listOf("cookie", "token", "authorization", "apikey", "api_key", "password", "secret", "session_key")
    private val secretPatterns =
        listOf(
            Regex("""sk-or-[A-Za-z0-9_-]{8,}"""),
            Regex("""sk-[A-Za-z0-9_-]{16,}"""),
            Regex("""(?i)bearer\s+[A-Za-z0-9._~+/=-]+"""),
            Regex("""(?i)(cookie|set-cookie|authorization)\s*[:=]\s*[^\s;,]+"""),
            Regex("""(?i)(session_id|cf_clearance|__cf_bm)=[^\s;,&]+"""),
        )

    /**
     * Whether a source-access event is copied into the persistent log. Routine per-request noise
     * (every request start, every successful finish, every still-loading Cloudflare poll) stays in
     * the in-memory source-access ring only; a bulk download would otherwise push the sync and
     * library history out of the size cap. Failures, challenges, and decisive polls are kept.
     */
    fun mirrorsSourceAccessEvent(
        type: String,
        fields: Map<String, Any?>,
    ): Boolean =
        when (type) {
            "net_request_start" -> false
            "net_request_finish" -> fields["ok"] != true
            "render_poll" -> fields["decision"] != "keep"
            else -> true
        }

    /** One event as an ordered map: envelope first, then scrubbed fields. Null fields are dropped. */
    fun eventMap(
        seq: Long,
        timestampMillis: Long,
        sessionId: String,
        category: ActivityCategory,
        type: String,
        fields: List<Pair<String, Any?>>,
    ): LinkedHashMap<String, Any?> {
        val map =
            linkedMapOf<String, Any?>(
                "ts" to timestampMillis,
                "seq" to seq,
                "sid" to sessionId,
                "cat" to category.wire,
                "type" to type,
            )
        fields.forEach { (rawKey, value) ->
            if (value == null) return@forEach
            val key = if (rawKey in reservedKeys) "f_$rawKey" else rawKey
            map[key] = scrubField(key, value)
        }
        return map
    }

    fun scrubField(
        key: String,
        value: Any?,
    ): Any? {
        if (isSecretKey(key)) return REDACTED
        val limit = if (key.endsWith("stack", ignoreCase = true)) MAX_STACK_LENGTH else MAX_STRING_LENGTH
        return scrubValue(value, limit, depth = 0)
    }

    @Suppress("CyclomaticComplexMethod")
    private fun scrubValue(
        value: Any?,
        limit: Int,
        depth: Int,
    ): Any? =
        when (value) {
            null -> null
            is Number, is Boolean -> value
            is String -> scrubText(value, limit)
            is CharSequence -> scrubText(value.toString(), limit)
            is Enum<*> -> value.name
            is Throwable -> scrubText("${value.javaClass.simpleName}: ${value.message.orEmpty()}", limit)
            is Map<*, *> -> if (depth >= MAX_MAP_DEPTH) scrubText(value.toString(), limit) else scrubMap(value, limit, depth)
            is Iterable<*> -> scrubList(value.toList(), limit, depth)
            is Array<*> -> scrubList(value.toList(), limit, depth)
            else -> scrubText(value.toString(), limit)
        }

    private fun scrubMap(
        value: Map<*, *>,
        limit: Int,
        depth: Int,
    ): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        value.entries.take(MAX_COLLECTION_ITEMS).forEach { (k, v) ->
            val childKey = k.toString()
            out[childKey] = if (isSecretKey(childKey)) REDACTED else scrubValue(v, limit, depth + 1)
        }
        if (value.size > MAX_COLLECTION_ITEMS) out["_more"] = value.size - MAX_COLLECTION_ITEMS
        return out
    }

    private fun scrubList(
        items: List<Any?>,
        limit: Int,
        depth: Int,
    ): List<Any?> {
        val kept = items.take(MAX_COLLECTION_ITEMS).map { scrubValue(it, limit, depth + 1) }
        return if (items.size > MAX_COLLECTION_ITEMS) kept + "…(+${items.size - MAX_COLLECTION_ITEMS} more)" else kept
    }

    fun scrubText(
        value: String,
        limit: Int = MAX_STRING_LENGTH,
    ): String {
        var text = value
        secretPatterns.forEach { pattern -> text = pattern.replace(text, REDACTED) }
        return if (text.length > limit) text.take(limit) + "…(+${text.length - limit})" else text
    }

    private fun isSecretKey(key: String): Boolean {
        val lower = key.lowercase()
        return secretKeyWords.any { it in lower }
    }

    /** First lines of a stack trace: enough to locate the failure without flooding the log. */
    fun shortStack(
        error: Throwable,
        maxLines: Int = 12,
    ): String =
        error
            .stackTraceToString()
            .lineSequence()
            .take(maxLines)
            .joinToString("\n")
}
