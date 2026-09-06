package com.vinicius741.webnovelarchiver.feature.settings

import android.webkit.WebStorage
import android.webkit.WebView
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.platform.WebViewSafety
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.network.CloudflareCookies
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewSolver
import com.vinicius741.webnovelarchiver.ui.toast

/** Clears every browser session declared by the registered source descriptors. */
internal fun ScreenHost.resetSourceWebSessions(sources: List<SourceProvider>) {
    CloudflareWebViewSolver.destroySessions()
    WebStorage.getInstance().deleteAllData()
    // R18: the shared WebView HTTP cache outlives the teardown above; clearCache(true) on a
    // throwaway WebView acts on the app-wide cache, not this instance.
    runCatching { WebViewSafety.destroyAndClearSharedCache(WebView(app)) }
    val sourceUrls = sources.map { it.baseUrl }
    sourceUrls.forEach { url ->
        app.appContainer.network.clearSourceAccess(url, keepBrowserTransport = false)
    }

    fun clearCookiesAt(index: Int) {
        val url = sourceUrls.getOrNull(index)
        if (url == null) {
            toast("Source web sessions reset")
        } else {
            CloudflareCookies.removeAllFor(url) { clearCookiesAt(index + 1) }
        }
    }
    clearCookiesAt(0)
}
