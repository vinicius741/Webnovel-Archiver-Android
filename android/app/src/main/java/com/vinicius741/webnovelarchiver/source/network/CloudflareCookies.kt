package com.vinicius741.webnovelarchiver.source.network

import android.webkit.CookieManager
import java.net.URI
import java.util.Locale

/**
 * Focused helpers over [CookieManager] for the Cloudflare `cf_clearance` cookie. The clearance
 * cookie is what unblocks OkHttp requests once a WebView has solved the JS/Turnstile challenge, so
 * reading/clearing/inspecting it is the one cookie operation the bypass pipeline needs beyond what
 * [AndroidCookieJar] already does transparently.
 *
 * All functions are main-thread-safe ([CookieManager] is thread-safe) and are called from the
 * background interceptor, the visible [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity],
 * and the diagnostics entry in Settings.
 */
object CloudflareCookies {
    private const val CLEARANCE_NAME = "cf_clearance"

    /** The raw `cf_clearance=…` segment for [url], or null when no clearance cookie is present. */
    fun clearanceFor(url: String): String? {
        val cookies = CookieManager.getInstance().getCookie(url) ?: return null
        if (cookies.isBlank()) return null
        return cookies
            .split(";")
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$CLEARANCE_NAME=", ignoreCase = true) }
    }

    /** True when [url] currently has a non-empty `cf_clearance` cookie. */
    fun hasClearance(url: String): Boolean = clearanceFor(url) != null

    /**
     * Removes the `cf_clearance` cookie for [url]'s host so the next solve can be detected as a
     * *fresh* grant (Mihon's success criterion: any clearance present after the page load must be
     * newly minted, not the stale one). Cloudflare may scope the cookie either to the exact host or
     * to the parent domain, so expire every plausible domain/path variant. Best-effort: returns true
     * if the clearance is gone afterwards.
     */
    fun clearClearance(url: String): Boolean {
        val cm = CookieManager.getInstance()
        expireCookie(cm, url, CLEARANCE_NAME)
        cm.flush()
        return !hasClearance(url)
    }

    /** Persists the in-memory cookie store to disk. Call after a successful solve. */
    fun flush() {
        CookieManager.getInstance().flush()
    }

    /**
     * Removes every visible cookie for [url]'s host — used by the "Clear source cookies" diagnostic
     * so a user can purge a stale/invalid `cf_clearance` and force a fresh solve on the next request.
     * [callback] (invoked on the calling thread) receives true if cookies were removed.
     */
    fun removeAllFor(
        url: String,
        callback: ((Boolean) -> Unit)? = null,
    ) {
        val cm = CookieManager.getInstance()
        val before = cm.getCookie(url).orEmpty()
        cookieNames(before)
            .plus(CLEARANCE_NAME)
            .plus("toc_show")
            .forEach { name ->
                expireCookie(cm, url, name)
            }
        cm.flush()
        val after = cm.getCookie(url).orEmpty()
        callback?.invoke(before.isNotBlank() && after.isBlank())
    }

    private fun expireCookie(
        cm: CookieManager,
        url: String,
        name: String,
    ) {
        for (domain in domainCandidates(url)) {
            for (path in listOf("/", "")) {
                val attributes =
                    buildString {
                        if (domain != null) append("; Domain=").append(domain)
                        if (path.isNotEmpty()) append("; Path=").append(path)
                        append("; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
                    }
                runCatching { cm.setCookie(url, "$name=$attributes") }
            }
        }
    }

    private fun cookieNames(cookieHeader: String): Set<String> =
        cookieHeader
            .split(";")
            .asSequence()
            .map { it.trim().substringBefore("=") }
            .filter { it.isNotBlank() }
            .toSet()

    internal fun domainCandidates(url: String): List<String?> {
        val host =
            runCatching { URI(url).host }
                .getOrNull()
                ?.trimEnd('.')
                ?.lowercase(Locale.US)
                ?: return listOf(null)
        if (host.isBlank()) return listOf(null)
        val parent = host.removePrefix("www.").takeIf { it != host }
        return buildList {
            add(null)
            add(host)
            add(".$host")
            if (parent != null) {
                add(parent)
                add(".$parent")
            }
        }.distinct()
    }
}
