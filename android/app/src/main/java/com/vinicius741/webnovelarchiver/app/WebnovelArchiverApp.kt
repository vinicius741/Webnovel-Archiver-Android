package com.vinicius741.webnovelarchiver.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.webkit.CookieManager
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.diagnostics.LocalDiagnosticTree
import com.vinicius741.webnovelarchiver.source.network.SourceUserAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Application entry point. Owns the process-wide [AppContainer] (Maintainability M2) so that the
 * activity and the download/TTS foreground services share a single [com.vinicius741.webnovelarchiver.data.storage.AppStorage],
 * [com.vinicius741.webnovelarchiver.source.network.NetworkClient], and set of engines — preventing duplicate
 * engines from racing on the same JSON files.
 *
 * Observability (Tier 1, T1): plants a [Timber.DebugTree] in debug builds so diagnostics flow to
 * logcat. In release a minimal tree keeps warnings+ so serious failures (caught in catch blocks, or
 * unexpected throwables) are still recorded for bug reports, without leaking verbose debug logs.
 */
class WebnovelArchiverApp : Application() {
    lateinit var container: AppContainer
        private set

    /** Background scope for startup work that must not block the main thread (see onCreate). */
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            } else {
                Timber.plant(ReleaseLogTree())
            }
        }
        Timber.plant(LocalDiagnosticTree())
        if (BuildConfig.DEBUG) enableDebugStrictMode()
        // Resolve the shared User-Agent asynchronously. WebSettings.getDefaultUserAgent lazily loads
        // the WebView provider, which is expensive — calling it synchronously on the main thread
        // here caused a startup ANR (the process failed to complete startup within the system's
        // window). resolveAsync posts the read to the main Looper without blocking onCreate; until
        // it completes, SourceUserAgent falls back to a current-ish Chrome UA, which is safe because
        // no OkHttp request fires during this brief window (the user must navigate to a screen first).
        SourceUserAgent.resolveAsync(this)
        container = AppContainer(this).apply { init() }
        // CookieManager.getInstance() also lazy-loads the WebView provider, so defer cookie
        // acceptance + the ScribbleHub toc_show seeding to the background. OkHttp's AndroidCookieJar
        // calls CookieManager lazily per-request, so by the time any request actually runs (after the
        // user navigates) the provider is loaded and the seeded toc_show cookie is in place.
        startupScope.launch { enableAndSeedCookies() }
    }

    private fun enableDebugStrictMode() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy
                .Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy
                .Builder()
                .detectLeakedClosableObjects()
                .penaltyLog()
                .build(),
        )
    }

    private fun enableAndSeedCookies() {
        runCatching {
            // The OkHttp AndroidCookieJar and the in-app WebViews all funnel through CookieManager,
            // so it must accept cookies app-wide before the first network request fires.
            CookieManager.getInstance().setAcceptCookie(true)
            // ScribbleHub's TOC pagination needs toc_show=50. It used to be a manual Cookie header
            // on each AJAX request, but once AndroidCookieJar carries a cf_clearance, OkHttp's
            // BridgeInterceptor rewrites the Cookie header from the jar and would drop the manual
            // one. Seeding it into CookieManager makes it part of the same single cookie source.
            cm().setCookie(
                "https://www.scribblehub.com",
                "toc_show=50; Domain=.scribblehub.com; Path=/; Max-Age=31536000",
            )
            cm().flush()
        }
    }

    private fun cm() = CookieManager.getInstance()
}

/**
 * Release-only [Timber.Tree]: emits WARN/ERROR levels with the callsite class tag so the diagnostic
 * logging added alongside catch blocks (Tier 1, T1) survives in shipped builds, while DEBUG/INFO
 * noise is dropped.
 */
private class ReleaseLogTree : Timber.Tree() {
    override fun isLoggable(
        tag: String?,
        priority: Int,
    ): Boolean = priority >= android.util.Log.WARN

    override fun log(
        priority: Int,
        tag: String?,
        message: String,
        t: Throwable?,
    ) {
        if (t != null) {
            android.util.Log.println(priority, tag, "$message\n${android.util.Log.getStackTraceString(t)}")
        } else {
            android.util.Log.println(priority, tag, message)
        }
    }
}

/** Convenience accessor for any component holding an application [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as WebnovelArchiverApp).container
