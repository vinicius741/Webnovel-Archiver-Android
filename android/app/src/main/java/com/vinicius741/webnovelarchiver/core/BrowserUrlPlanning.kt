package com.vinicius741.webnovelarchiver.core

import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object BrowserUrlPlanning {
    private val urlLikePattern =
        Regex(
            """^(https?://)?([\da-z.-]+)\.([a-z.]{2,6})([/\w .-]*)*/?(?:\?[^#]*)?(?:#.*)?$""",
            RegexOption.IGNORE_CASE,
        )

    fun resolveUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return ""
        val looksLikeUrl = urlLikePattern.matches(trimmed)
        if (looksLikeUrl) {
            return if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
                trimmed
            } else {
                "https://$trimmed"
            }
        }
        return "https://www.google.com/search?q=${URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name()).replace("+", "%20")}"
    }

    fun isGoogleAuthUrl(url: String): Boolean =
        runCatching {
            if (url.isBlank()) return false
            val parsed = URI(url)
            val host = parsed.host?.lowercase().orEmpty()
            val path = parsed.path.orEmpty()
            host == "accounts.google.com" ||
                host.endsWith(".accounts.google.com") ||
                ((host == "google.com" || host.endsWith(".google.com")) && path.startsWith("/o/oauth2"))
        }.getOrDefault(false)
}
