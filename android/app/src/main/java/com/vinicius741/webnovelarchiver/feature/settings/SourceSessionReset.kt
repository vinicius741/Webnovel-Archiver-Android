package com.vinicius741.webnovelarchiver.feature.settings

import android.webkit.WebStorage
import com.vinicius741.webnovelarchiver.app.appContainer
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.source.SourceProvider
import com.vinicius741.webnovelarchiver.source.network.CloudflareCookies
import com.vinicius741.webnovelarchiver.source.network.CloudflareWebViewSolver
import com.vinicius741.webnovelarchiver.ui.toast

/** Clears every browser session declared by the registered source descriptors. */
internal fun ScreenHost.resetSourceWebSessions(sources: List<SourceProvider>) {
    CloudflareWebViewSolver.destroySessions()
    WebStorage.getInstance().deleteAllData()
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
