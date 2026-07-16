package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The single User-Agent string shared by every HTTP/WebView surface in the app.
 *
 * Cloudflare binds the `cf_clearance` cookie to (at least) the client IP **and** the exact
 * User-Agent. If OkHttp and the solving WebView send different UA strings, the clearance earned by
 * the WebView is rejected when OkHttp replays it. To keep them identical by construction, both read
 * [resolved]: OkHttp through [NetworkRequests] and both WebViews through `settings.userAgentString`.
 *
 * The value is derived from the system WebView's own UA ([WebSettings.getDefaultUserAgent]) so it
 * tracks the installed Chrome/WebView version automatically, then [normalize]d to a plain
 * Chrome-on-Android string. This mirrors Mihon's inferred UA shape: keep the WebView's current
 * Chrome version, but replace the highly specific Android/device/build segment with Chrome's
 * reduced `Android 10; K` token and remove WebView-only markers. That avoids telling Cloudflare
 * "Android emulator WebView" while still keeping OkHttp and WebView byte-identical.
 *
 * Resolution is deferred off the Application main thread because [WebSettings.getDefaultUserAgent]
 * lazily loads the WebView provider, which is expensive and caused a startup ANR when done in
 * [com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp.onCreate]. [resolveAsync] posts the read
 * to the main Looper (WebSettings requires a Looper thread) but does not block startup. The first
 * background request briefly waits for that resolution, then freezes the selected value so the
 * process cannot change User-Agent midway through a Cloudflare passage.
 */
object SourceUserAgent {
    /**
     * A safe pre-resolution UA used if WebView resolution fails or the first consumer arrives from
     * the main thread. Once consumed, the selected value is frozen for the process.
     */
    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var value: String = FALLBACK

    @Volatile
    private var frozen = false

    private val resolvedLatch = CountDownLatch(1)

    /**
     * The normalized UA to send on every request and WebView. The first consumer freezes the value
     * for the process so a clearance cannot be minted with the fallback UA and replayed later with a
     * different UA. Background callers briefly await normal WebView resolution; main-thread callers
     * never block and safely freeze the fallback in the unlikely early-access case.
     */
    val resolved: String
        get() {
            val canAwait = runCatching { Looper.myLooper() != Looper.getMainLooper() }.getOrDefault(false)
            if (canAwait) {
                runCatching { resolvedLatch.await(2, TimeUnit.SECONDS) }
            }
            synchronized(this) {
                frozen = true
                return value
            }
        }

    /**
     * Reads the system WebView UA asynchronously and stores the normalized form. Safe to call from
     * [com.vinicius741.webnovelarchiver.app.WebnovelArchiverApp.onCreate] — it posts the actual
     * [WebSettings.getDefaultUserAgent] read to the main Looper without blocking the startup path.
     */
    fun resolveAsync(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            // WebSettings.getDefaultUserAgent must run on a Looper thread; we are on the main one.
            // Wrapped in runCatching so a misbehaving WebView provider can never crash the app —
            // the fallback UA remains in effect and requests still go out.
            synchronized(this) {
                if (!frozen) {
                    value =
                        runCatching { normalize(WebSettings.getDefaultUserAgent(appContext)) }
                            .getOrDefault(value)
                }
                resolvedLatch.countDown()
            }
        }
    }

    /**
     * Pure transform used by [resolveAsync] and unit-tested directly. It keeps the WebView's actual
     * Chrome version while applying the same reductions Mihon uses:
     *  - replace the full Android/device/build segment with `"; Android 10; K)"`
     *  - `"Version/x.y Chrome/"` → `"Chrome/"` (the redundant WebView Version token)
     * Idempotent: an already-normal string passes through unchanged.
     */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace("; Android .*?\\)".toRegex(), "; Android 10; K)")
            .replace("Version/.* Chrome/".toRegex(), "Chrome/")
    }
}
