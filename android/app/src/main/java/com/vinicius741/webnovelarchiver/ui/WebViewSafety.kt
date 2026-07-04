package com.vinicius741.webnovelarchiver.ui

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import com.vinicius741.webnovelarchiver.app.MainActivity

/**
 * Centralized WebView configuration (Reliability R9). Reader and Browser WebViews route through
 * here so the security/lifecycle posture is consistent and cannot drift between screens.
 *
 * Two policies:
 *  - [Reader]: JS off by default; opt-in via `enableTtsHighlight` so the reader can run the TTS
 *    highlight + tap-to-start script (parity gap 3). When JS is on, callers MUST sanitize the
 *    chapter HTML first ([com.vinicius741.webnovelarchiver.feature.reader.ChapterHtmlSanitizer]); file/content
 *    access stays locked down either way, so the only script that can run is this app's own.
 *  - [Browser]: JS + DOM storage ON (required for novel sites' TOC pagination) but with file/content
 *    access locked down and Safe Browsing enabled where the platform supports it.
 *
 * WebViews themselves are torn down via [disposeWebViews] in Scaffold.kt on screen navigation, and
 * the activity's final WebView is cleaned up in MainActivity.onDestroy.
 */
object WebViewSafety {
    /**
     * Configure a WebView used to render trusted, sanitized chapter HTML.
     *
     * @param enableTtsHighlight when true, enables JavaScript so the reader can run the TTS
     *   paragraph-highlight + tap-to-start script (parity gap 3). Callers MUST first sanitize the
     *   chapter HTML through [com.vinicius741.webnovelarchiver.feature.reader.ChapterHtmlSanitizer]; file and
     *   content access stay disabled regardless, so the only script that can run is the one this app
     *   injects. Defaults to `false` to preserve the historical posture for any non-reader caller.
     */
    fun applyReaderSettings(
        web: WebView,
        enableTtsHighlight: Boolean = false,
    ) {
        val s = web.settings
        s.javaScriptEnabled = enableTtsHighlight
        // DOM storage stays off for the reader even with JS on: the TTS highlight script is stateless
        // and needs no localStorage/sessionStorage, so there's nothing to gain and an attack surface
        // to lose.
        s.domStorageEnabled = false
        lockDownAccess(s)
    }

    /**
     * Configure a WebView used for the in-app import browser. JS + DOM storage must stay enabled
     * (Scribble Hub's chapter list loads via AJAX), but file/content/remote access is restricted and
     * Safe Browsing is enabled where available (R9).
     */
    fun applyBrowserSettings(web: WebView) {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.javaScriptCanOpenWindowsAutomatically = false
        s.setSupportMultipleWindows(true)
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        lockDownAccess(s)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        enableSafeBrowsing(web)
    }

    private fun lockDownAccess(s: WebSettings) {
        // Reader HTML is injected via loadDataWithBaseURL, never from the filesystem; the browser
        // never needs to open local files/content URIs. Disable both to shrink the attack surface.
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.allowFileAccessFromFileURLs = false
        s.allowUniversalAccessFromFileURLs = false
        s.mediaPlaybackRequiresUserGesture = true
    }

    private fun enableSafeBrowsing(web: WebView) {
        // setSafeBrowsingEnabled is available from API 26 (our minSdk), so no reflection needed.
        runCatching { web.settings.safeBrowsingEnabled = true }
    }

    /** Full teardown for a WebView leaving the screen (called by Scaffold.disposeWebViews). */
    fun destroy(web: WebView) {
        runCatching {
            web.stopLoading()
            web.clearHistory()
            web.clearCache(true)
            web.clearFormData()
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.removeAllViews()
            web.destroy()
        }
    }

    /**
     * Walks [root] and destroys every WebView in the tree. Public so [com.vinicius741.webnovelarchiver.app.MainActivity]
     * can clean up the final Reader/Browser WebView in onDestroy, and so Scaffold can call it before
     * `removeAllViews()` on navigation.
     */
    fun disposeAll(root: android.view.View) {
        if (root is WebView) {
            destroy(root)
            return
        }
        if (root is android.view.ViewGroup) {
            (0 until root.childCount).map { root.getChildAt(it) }.forEach { disposeAll(it) }
        }
    }
}
