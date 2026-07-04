package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings

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
 * to the main Looper (WebSettings requires a Looper thread) but does not block startup; [resolved]
 * falls back to [FALLBACK] until the async read completes, which is acceptable because no OkHttp
 * request fires during that window (the user must navigate to a screen first).
 */
object SourceUserAgent {
    /**
     * A safe pre-resolution UA used until [resolveAsync] completes. Kept current-ish (a recent
     * Chrome major) so requests made in the brief window before resolution are not flagged as stale
     * by Cloudflare; once resolution finishes, every subsequent request uses the exact WebView UA.
     */
    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var value: String = FALLBACK

    /** The normalized UA to send on every request and WebView. Falls back to [FALLBACK] pre-resolution. */
    val resolved: String get() = value

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
            value =
                runCatching { normalize(WebSettings.getDefaultUserAgent(appContext)) }
                    .getOrDefault(value)
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
