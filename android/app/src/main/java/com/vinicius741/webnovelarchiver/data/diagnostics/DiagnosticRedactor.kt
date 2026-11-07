package com.vinicius741.webnovelarchiver.data.diagnostics

/**
 * Scrubs free text (log messages, exception messages, system exit descriptions) before it is
 * persisted to the [DiagnosticJournal]. The goal is a report a user can send without leaking
 * credentials or browsing history, while keeping enough shape to diagnose the failure:
 *
 * - URLs keep only scheme + host (`https://host/…`); content URIs collapse to `content://…`.
 * - Absolute file paths keep only the final segment (`…/story.json`).
 * - Emails, API keys (`sk-…`), bearer tokens, `key=value` secrets, and long opaque tokens are
 *   replaced with placeholders.
 *
 * Story titles that a call site interpolates into a message are not detectable and may survive;
 * the Settings copy says so. Stack frames are code locations only and are kept verbatim.
 */
object DiagnosticRedactor {
    const val MAX_TEXT_LENGTH = 400
    const val MAX_FRAMES = 24
    const val MAX_CAUSE_DEPTH = 3

    private val urlPattern = Regex("""\b([a-zA-Z][a-zA-Z0-9+.-]{1,15})://([^/\s?#"'<>]+)[^\s"'<>]*""")
    private val emailPattern = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")
    private val secretAssignmentPattern =
        Regex(
            """(?i)\b(cookie|set-cookie|authorization|token|access_token|refresh_token|api[_-]?key|key|password|passwd|secret|sessionid|session_id)(\s*[=:]\s*)("[^"]*"|[^\s,;&]+)""",
        )
    private val bearerPattern = Regex("""(?i)\bbearer\s+[A-Za-z0-9._~+/=-]+""")
    private val apiKeyPattern = Regex("""\bsk-[A-Za-z0-9_-]{8,}""")
    private val filePathPattern = Regex("""(?<![A-Za-z0-9:/])(/[^\s/"'<>:]+){2,}/?""")
    private val opaqueTokenPattern = Regex("""\b[A-Za-z0-9_\-+/=]{40,}\b""")

    fun redact(
        text: String?,
        maxLength: Int = MAX_TEXT_LENGTH,
    ): String? {
        if (text.isNullOrBlank()) return null
        var result = text
        result =
            urlPattern.replace(result) { match ->
                val scheme = match.groupValues[1].lowercase()
                if (scheme == "content" || scheme == "file") "$scheme://…" else "$scheme://${match.groupValues[2].substringAfter('@')}/…"
            }
        result = bearerPattern.replace(result, "Bearer <redacted>")
        result = apiKeyPattern.replace(result, "<api-key>")
        result = secretAssignmentPattern.replace(result) { match -> "${match.groupValues[1]}${match.groupValues[2]}<redacted>" }
        result = emailPattern.replace(result, "<email>")
        result = filePathPattern.replace(result) { match -> "…/" + match.value.trimEnd('/').substringAfterLast('/') }
        result = opaqueTokenPattern.replace(result, "<token>")
        return result.trim().let { if (it.length > maxLength) it.take(maxLength) + "…" else it }.ifBlank { null }
    }

    /**
     * Structured, redacted throwable: type, message, top [MAX_FRAMES] frames, and up to
     * [MAX_CAUSE_DEPTH] causes. `fp` is a stable fingerprint of type + frames so exports can
     * deduplicate repeated stack traces.
     */
    fun throwable(
        error: Throwable,
        depth: Int = 0,
    ): LinkedHashMap<String, Any?> {
        val frames = error.stackTrace.take(MAX_FRAMES).map(StackTraceElement::toString)
        val map =
            linkedMapOf<String, Any?>(
                "type" to error.javaClass.name,
                "msg" to redact(error.message),
                "frames" to frames,
            )
        if (error.stackTrace.size > MAX_FRAMES) map["moreFrames"] = error.stackTrace.size - MAX_FRAMES
        val cause = error.cause
        if (cause != null && cause !== error && depth < MAX_CAUSE_DEPTH) map["cause"] = throwable(cause, depth + 1)
        if (depth == 0) map["fp"] = fingerprint(map)
        return map
    }

    @Suppress("UNCHECKED_CAST")
    private fun fingerprint(map: Map<String, Any?>): String {
        val builder = StringBuilder()
        var current: Map<String, Any?>? = map
        while (current != null) {
            builder.append(current["type"]).append('|')
            (current["frames"] as? List<String>)?.forEach { builder.append(it).append('|') }
            current = current["cause"] as? Map<String, Any?>
        }
        return Integer.toHexString(builder.toString().hashCode())
    }
}
