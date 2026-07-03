package com.vinicius741.webnovelarchiver.feature.browser

import android.app.AlertDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme

internal object SourceAccessRetryCoordinator {
    private var pendingRetry: (() -> Unit)? = null

    fun arm(retry: (() -> Unit)?) {
        pendingRetry = retry
    }

    fun consumePendingRetry(): (() -> Unit)? {
        val retry = pendingRetry
        pendingRetry = null
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
        "Cloudflare is blocking automated access to this source. Open the page in the browser, pass the Cloudflare check, then return here and retry.",
    )
    builder.setPositiveButton("Open Browser") { _, _ ->
        SourceAccessRetryCoordinator.arm(retryAfterBrowser)
        showBrowser(url)
    }
    builder.setNegativeButton("Cancel", null)
    builder
        .show()
        .also { it.applyAppTheme() }
}
