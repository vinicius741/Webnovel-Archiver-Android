package com.vinicius741.webnovelarchiver.source.network

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl

/**
 * OkHttp [CookieJar] backed by [android.webkit.CookieManager].
 *
 * This is the seam that lets the OkHttp client and the in-app WebViews share a single cookie store:
 * when a WebView (background solver or interactive [com.vinicius741.webnovelarchiver.feature.browser.CloudflareSolveActivity])
 * earns a Cloudflare `cf_clearance` cookie, it lands in [CookieManager] — and [loadForRequest] then
 * hands it back to OkHttp on the next request with no explicit copy step. Persistence across app
 * restarts is free, because [CookieManager] flushes to the WebView data directory.
 *
 * Modelled on Mihon's `AndroidCookieJar`. Methods must be safe to call from OkHttp's IO threads;
 * [CookieManager] is itself thread-safe.
 */
class AndroidCookieJar : CookieJar {
    private val cookieManager: CookieManager get() = CookieManager.getInstance()

    override fun saveFromResponse(
        url: HttpUrl,
        cookies: List<Cookie>,
    ) {
        val urlString = url.toString()
        for (cookie in cookies) {
            cookieManager.setCookie(urlString, cookie.toString())
        }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val cookieString = cookieManager.getCookie(url.toString()) ?: return emptyList()
        if (cookieString.isBlank()) return emptyList()
        // CookieManager returns cookies as a single "name=value; name2=value2" header. OkHttp wants
        // parsed Cookie objects; Cookie.parse tolerates the trailing-semicolon form some stores emit.
        return cookieString
            .split(";")
            .mapNotNull { pair -> Cookie.parse(url, pair.trim()) }
    }
}
