package com.vinicius741.webnovelarchiver.source

import android.webkit.CookieManager

/**
 * The user's patreon.com `session_id`, copied from a browser where they signed in (Google sign-in
 * cannot run inside the app). It lives only in the shared [CookieManager], so OkHttp sends it via
 * [com.vinicius741.webnovelarchiver.source.network.AndroidCookieJar]; it never enters app storage,
 * backups, diagnostics, or logs.
 */
object PatreonSession {
    private const val ORIGIN = "https://www.patreon.com"
    private const val COOKIE_NAME = "session_id"
    private const val ONE_YEAR_SECONDS = 31_536_000

    fun isPresent(): Boolean = runCatching { cookieValue(CookieManager.getInstance().getCookie(ORIGIN)) != null }.getOrDefault(false)

    /** Stores a pasted value, accepting either the bare value or a `session_id=...` pair. Returns false when blank. */
    fun store(pasted: String): Boolean {
        val value = normalizePastedValue(pasted) ?: return false
        val cookies = CookieManager.getInstance()
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=$value; Domain=.patreon.com; Path=/; Secure; HttpOnly; Max-Age=$ONE_YEAR_SECONDS")
        cookies.flush()
        return true
    }

    fun clear() {
        val cookies = CookieManager.getInstance()
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=; Domain=.patreon.com; Path=/; Max-Age=0")
        cookies.setCookie(ORIGIN, "$COOKIE_NAME=; Path=/; Max-Age=0")
        cookies.flush()
    }

    internal fun normalizePastedValue(pasted: String): String? =
        pasted
            .trim()
            .removePrefix("$COOKIE_NAME=")
            .substringBefore(';')
            .trim()
            .trim('"')
            .takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) }

    internal fun cookieValue(cookieHeader: String?): String? =
        cookieHeader
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("$COOKIE_NAME=") }
            ?.removePrefix("$COOKIE_NAME=")
            ?.takeIf { it.isNotEmpty() }
}
