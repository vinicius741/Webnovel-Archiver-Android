package com.vinicius741.webnovelarchiver.feature.browser

import java.net.URI

/**
 * Pure planning for the Source Picker screen. Kept free of Android dependencies so it can be unit
 * tested directly, matching the repo's "deterministic decisions in pure functions" rule.
 */
object SourcePickerPlanning {
    /**
     * Derives a short, user-facing host from a provider's [baseUrl] (e.g. "https://www.royalroad.com"
     * → "royalroad.com"). Strips the leading `www.` so every source reads consistently, and drops any
     * path/query/fragment — only the registrable host is useful on a picker row.
     *
     * Returns the trimmed input (or empty string) when [baseUrl] is blank or unparseable, so callers
     * can render safely without a separate null check.
     */
    fun host(baseUrl: String): String {
        val trimmed = baseUrl.trim()
        if (trimmed.isBlank()) return ""
        val raw =
            runCatching { URI(trimmed).host ?: URI(trimmed).authority }.getOrNull()
                ?: return trimmed
        return raw.removePrefix("www.").removePrefix("WWW.").ifBlank { trimmed }
    }
}
