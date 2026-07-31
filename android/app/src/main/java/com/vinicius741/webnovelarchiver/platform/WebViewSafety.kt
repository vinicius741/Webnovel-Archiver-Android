package com.vinicius741.webnovelarchiver.platform

import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/** Shared WebView configuration and teardown policy for screen and source-renderer clients. */
object WebViewSafety {
    fun applyReaderSettings(
        web: WebView,
        enableTtsHighlight: Boolean = false,
    ) {
        val settings = web.settings
        settings.javaScriptEnabled = enableTtsHighlight
        settings.domStorageEnabled = false
        lockDownAccess(settings)
    }

    fun applyBrowserSettings(web: WebView) {
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(true)
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        lockDownAccess(settings)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)
        runCatching { settings.safeBrowsingEnabled = true }
    }

    private fun lockDownAccess(settings: WebSettings) {
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        settings.mediaPlaybackRequiresUserGesture = true
    }

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

    fun disposeAll(root: android.view.View) {
        if (root is WebView) {
            destroy(root)
            return
        }
        if (root is android.view.ViewGroup) {
            // destroy() removes WebViews from their parent, so traverse a stable child snapshot.
            val children = mutableListOf<android.view.View>()
            for (index in 0 until root.childCount) {
                children += root.getChildAt(index)
            }
            children.forEach(::disposeAll)
        }
    }
}
