package com.vinicius741.webnovelarchiver.app

import android.app.Application
import android.content.Context
import android.os.StrictMode
import android.webkit.CookieManager
import com.vinicius741.webnovelarchiver.BuildConfig
import com.vinicius741.webnovelarchiver.data.diagnostics.LocalDiagnosticTree
import com.vinicius741.webnovelarchiver.notification.AppNotificationChannels
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.SourceUserAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Process-wide [AppContainer] so the activity and download/TTS services share one storage,
 * network client, and engines — duplicate engines would race on the same JSON files.
 */
class WebnovelArchiverApp : Application() {
    lateinit var container: AppContainer
        private set

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
        // Must exist before Settings opens system controls; creation is idempotent and does not
        // trigger the Android 13 runtime permission prompt.
        AppNotificationChannels.ensureCreated(this)
        // WebSettings.getDefaultUserAgent lazy-loads the WebView provider; calling it synchronously
        // caused a startup ANR. resolveAsync posts the read; the fallback UA is safe since no
        // OkHttp request fires before the user navigates somewhere.
        SourceUserAgent.resolveAsync(this)
        container = AppContainer(this).apply { init() }
        // CookieManager.getInstance() also lazy-loads the WebView provider; defer seeding to the
        // background. OkHttp's cookie jar reads CookieManager lazily per request, so seeds land in time.
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
            // OkHttp's cookie jar and the in-app WebViews all funnel through CookieManager.
            CookieManager.getInstance().setAcceptCookie(true)
            SourceRegistry
                .all()
                .flatMap { it.descriptor.cookieSeeds }
                .forEach { seed -> cm().setCookie(seed.url, seed.value) }
            cm().flush()
        }
    }

    private fun cm() = CookieManager.getInstance()
}

/** Release-only tree: WARN+ only, so diagnostics survive in shipped builds without DEBUG/INFO noise. */
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

val Context.appContainer: AppContainer
    get() = (applicationContext as WebnovelArchiverApp).container
