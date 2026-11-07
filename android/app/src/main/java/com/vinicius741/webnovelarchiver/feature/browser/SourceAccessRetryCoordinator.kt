package com.vinicius741.webnovelarchiver.feature.browser

import android.app.AlertDialog
import com.vinicius741.webnovelarchiver.navigation.ScreenHost
import com.vinicius741.webnovelarchiver.ui.applyAppTheme

/**
 * Holds the action to repeat once a source check passes. A retry belongs to exactly one solver
 * launch: [arm] returns a token the launch carries, and only that launch can mark it ready or
 * abandon it. Without the token, a check the listener backed out of left its closure armed, and the
 * next unrelated passed check (say, from the download notification) fired it from a stale screen.
 *
 * Main thread only: armed from UI callbacks, settled by [CloudflareSolveActivity], consumed in
 * `MainActivity.onResume`.
 */
internal object SourceAccessRetryCoordinator {
    /** A solver launch with nothing to retry, such as the blocked-download notification. */
    const val NO_RETRY = 0L

    private class Armed(
        val token: Long,
        val retry: () -> Unit,
    )

    private var armed: Armed? = null
    private var ready = false
    private var lastToken = NO_RETRY

    /** Arms [retry] for the next solver launch, replacing any earlier one; returns that launch's token. */
    fun arm(retry: () -> Unit): Long {
        lastToken += 1
        armed = Armed(lastToken, retry)
        ready = false
        return lastToken
    }

    /** Drops any armed retry, e.g. when the listener leaves for an external browser instead. */
    fun clear() {
        armed = null
        ready = false
    }

    /** The launch holding [token] passed its check; the retry fires when the app resumes. */
    fun markReadyToRetry(token: Long) {
        if (token != NO_RETRY && armed?.token == token) ready = true
    }

    /** The launch holding [token] closed without passing; its retry must never fire later. */
    fun abandon(token: Long) {
        if (armed?.token == token && !ready) armed = null
    }

    fun consumeReadyRetry(): (() -> Unit)? {
        if (!ready) return null
        val retry = armed?.retry
        armed = null
        ready = false
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
    // Primary: in-app WebView that shares CookieManager with source requests. The retry is armed
    // below and fired from MainActivity.onResume; the challenged host stays on Chromium transport.
    builder.setPositiveButton("Verify access") { _, _ ->
        val token =
            retryAfterBrowser?.let(SourceAccessRetryCoordinator::arm)
                ?: SourceAccessRetryCoordinator.NO_RETRY.also { SourceAccessRetryCoordinator.clear() }
        CloudflareSolveActivity.launch(app, url, token)
    }
    // Secondary escape hatch: a full browser is a fully supported Cloudflare environment and can
    // solve challenges the WebView cannot. Its cookies don't share with the app, so nothing marks
    // the check passed and no retry is armed — but it's useful when the in-app WebView misbehaves.
    builder.setNeutralButton("Open browser") { _, _ ->
        SourceAccessRetryCoordinator.clear()
        showBrowser(url)
    }
    builder.setNegativeButton("Cancel", null)
    builder
        .show()
        .also { it.applyAppTheme() }
}
