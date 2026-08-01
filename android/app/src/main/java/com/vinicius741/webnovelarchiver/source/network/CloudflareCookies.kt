package com.vinicius741.webnovelarchiver.source.network

import android.webkit.CookieManager
import java.net.URI
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

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
@Suppress("TooManyFunctions")
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
     * True when a clearance is present for [url] or one of the site's common host variants.
     *
     * Some sources redirect between `www`, mobile, and bare hosts. A host-only clearance minted
     * on one of those variants is still useful to the browser session, but must never be confused
     * with a clearance belonging to another source.
     */
    fun hasClearanceForSite(url: String): Boolean = clearanceScopeUrls(url).any(::hasClearance)

    /**
     * Removes the `cf_clearance` cookie for [url]'s host so the next solve can be detected as a
     * *fresh* grant (Mihon's success criterion: any clearance present after the page load must be
     * newly minted, not the stale one). Cloudflare may scope the cookie either to the exact host or
     * to the parent domain, so expire every plausible domain/path variant. Best-effort: returns true
     * if the clearance is gone afterwards.
     */
    fun clearClearance(url: String): Boolean {
        val cm = CookieManager.getInstance()
        val scopeUrls = clearanceScopeUrls(url)
        scopeUrls.forEach { scopeUrl ->
            expireCookie(cm, scopeUrl, CLEARANCE_NAME)
        }
        cm.flush()
        return scopeUrls.none(::hasClearance)
    }

    /**
     * Clears [url]'s clearance and invokes [callback] after Chromium has applied every expiration.
     * CookieManager's callback-free setter is asynchronous on current WebView releases, so a
     * caller that immediately loads a page can otherwise send the stale clearance one last time.
     */
    fun clearClearanceAsync(
        url: String,
        callback: (Boolean) -> Unit,
    ) {
        val cm = CookieManager.getInstance()
        val expirations =
            clearanceScopeUrls(url).flatMap { scopeUrl ->
                domainCandidates(scopeUrl).flatMap { domain ->
                    listOf("/", "").map { path ->
                        scopeUrl to expirationCookie(CLEARANCE_NAME, domain, path)
                    }
                }
            }
        if (expirations.isEmpty()) {
            callback(false)
            return
        }
        val remaining = AtomicInteger(expirations.size)
        val complete = {
            if (remaining.decrementAndGet() == 0) {
                cm.flush()
                callback(clearanceScopeUrls(url).none(::hasClearance))
            }
        }
        expirations.forEach { (scopeUrl, cookie) ->
            runCatching {
                cm.setCookie(scopeUrl, cookie) { complete() }
            }.onFailure {
                complete()
            }
        }
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
        val scopeUrls = clearanceScopeUrls(url)
        val beforeCookies =
            scopeUrls.map { scopeUrl ->
                cm.getCookie(scopeUrl).orEmpty()
            }
        val names =
            beforeCookies
                .flatMap { cookieNames(it) }
                .toSet()
                .plus(CLEARANCE_NAME)
                .plus("toc_show")
        scopeUrls.forEach { scopeUrl ->
            names.forEach { name ->
                expireCookie(cm, scopeUrl, name)
            }
        }
        cm.flush()
        val afterCookies =
            scopeUrls.map { scopeUrl ->
                cm.getCookie(scopeUrl).orEmpty()
            }
        callback?.invoke(beforeCookies.any { it.isNotBlank() } && afterCookies.all { it.isBlank() })
    }

    /** URLs whose host-only or parent-domain cookies may participate in the same source session. */
    internal fun clearanceScopeUrls(url: String): List<String> {
        val parsed = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host =
            parsed.host
                ?.trimEnd('.')
                ?.lowercase(Locale.US)
                ?.takeIf { it.isNotBlank() }
                ?: return listOf(url)
        val rootHost =
            when {
                host.startsWith("www.") || host.startsWith("m.") -> host.substringAfter('.')
                host.count { it == '.' } >= 2 -> host.substringAfter('.')
                else -> host
            }
        val hosts =
            listOf(host, rootHost, "www.$rootHost", "m.$rootHost")
                .distinct()
        return hosts
            .map { variantHost ->
                runCatching {
                    URI(
                        parsed.scheme,
                        parsed.userInfo,
                        variantHost,
                        parsed.port,
                        parsed.path,
                        parsed.query,
                        parsed.fragment,
                    ).toString()
                }.getOrDefault(url)
            }.distinct()
    }

    private fun expireCookie(
        cm: CookieManager,
        url: String,
        name: String,
    ) {
        for (domain in domainCandidates(url)) {
            for (path in listOf("/", "")) {
                runCatching { cm.setCookie(url, expirationCookie(name, domain, path)) }
            }
        }
    }

    private fun expirationCookie(
        name: String,
        domain: String?,
        path: String,
    ): String {
        val attributes =
            buildString {
                if (domain != null) append("; Domain=").append(domain)
                if (path.isNotEmpty()) append("; Path=").append(path)
                append("; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT")
            }
        return "$name=$attributes"
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
