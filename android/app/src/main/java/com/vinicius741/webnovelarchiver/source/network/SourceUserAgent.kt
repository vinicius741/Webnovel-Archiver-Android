package com.vinicius741.webnovelarchiver.source.network

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebSettings
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.SourceUserAgentMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The single UA shared by every HTTP/WebView surface: Cloudflare binds `cf_clearance` to the client
 * IP *and* the exact UA, so OkHttp and the solving WebView must send byte-identical strings — both
 * read [resolved].
 *
 * Built from the system WebView UA (tracks the installed Chrome version) and [normalize]d to a
 * plain Chrome-on-Android string, avoiding an "emulator WebView" fingerprint. Resolution is deferred
 * off the main thread ([WebSettings.getDefaultUserAgent] lazily loads the WebView provider and
 * caused a startup ANR); the first consumer freezes the value for the process so the UA can never
 * change midway through a Cloudflare passage.
 */
object SourceUserAgent {
    /** Safe pre-resolution UA when WebView resolution fails or first use is on the main thread. */
    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/130.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var value: String = FALLBACK

    @Volatile
    private var frozen = false

    private val resolvedLatch = CountDownLatch(1)

    /** First consumer freezes the value; background callers briefly await resolution, main-thread callers never block. */
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

    /** Safe from Application.onCreate: posts the read to the main Looper without blocking startup. */
    fun resolveAsync(context: Context) {
        val appContext = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            // getDefaultUserAgent needs a Looper thread; runCatching keeps a bad provider from crashing.
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

    /** Some sources serve incomplete mobile pages and declare that they require a desktop UA. */
    fun forUrl(url: String): String {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return resolved
        val provider = SourceRegistry.providerForHost(host) ?: return resolved
        if (provider.descriptor.userAgentMode != SourceUserAgentMode.DESKTOP) return resolved
        val chromeVersion =
            Regex("Chrome/([^\\s]+)")
                .find(resolved)
                ?.groupValues
                ?.get(1)
                ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/$chromeVersion Safari/537.36"
    }

    /** Mihon-style reduction: keeps Chrome's version, drops the device segment and WebView Version token. Idempotent. */
    fun normalize(raw: String): String {
        if (raw.isBlank()) return raw
        return raw
            .replace("; Android .*?\\)".toRegex(), "; Android 10; K)")
            .replace("Version/.* Chrome/".toRegex(), "Chrome/")
    }
}
