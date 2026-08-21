package com.vinicius741.webnovelarchiver.feature.browser

import android.app.AlertDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme

internal object SourceAccessRetryCoordinator {
    private var pendingRetry: (() -> Unit)? = null
    private var readyToRetry = false

    fun arm(retry: (() -> Unit)?) {
        pendingRetry = retry
        readyToRetry = false
    }

    fun markReadyToRetry() {
        readyToRetry = true
    }

    fun consumeReadyRetry(): (() -> Unit)? {
        if (!readyToRetry) return null
        val retry = pendingRetry
        pendingRetry = null
        readyToRetry = false
        return retry
    }
}

internal fun ScreenHost.showSourceAccessBlockedDialog(
    url: String,
    retryAfterBrowser: (() -> Unit)? = null,
) {
    val builder = AlertDialog.Builder(app)
    builder.setTitle("Source access required")
    builder.setMessage(
        "Cloudflare is blocking automated access to this source. Open the in-app browser, pass the " +
            "Cloudflare check, then return here and the request will retry automatically.",
    )
    // Primary: in-app WebView that shares CookieManager with OkHttp, so the earned cf_clearance is
    // replayed on the retry (armed below, fired from MainActivity.onResume). This replaces the old
    // Chrome Custom Tab path, whose cookies were isolated from the app and never reached OkHttp.
    builder.setPositiveButton("Verify access") { _, _ ->
        SourceAccessRetryCoordinator.arm(retryAfterBrowser)
        CloudflareSolveActivity.launch(app, url)
    }
    // Secondary escape hatch: a full browser is a fully supported Cloudflare environment and can
    // solve challenges the WebView cannot. Its cookies don't share with the app, so the retry may
    // still fail — but it's useful when the in-app WebView itself is misbehaving.
    builder.setNeutralButton("Open browser") { _, _ ->
        SourceAccessRetryCoordinator.arm(retryAfterBrowser)
        showBrowser(url)
    }
    builder.setNegativeButton("Cancel", null)
    builder
        .show()
        .also { it.applyAppTheme() }
}
