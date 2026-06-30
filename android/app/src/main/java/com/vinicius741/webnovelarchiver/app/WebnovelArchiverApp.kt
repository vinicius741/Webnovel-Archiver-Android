package com.vinicius741.webnovelarchiver.app

import android.app.Application
import android.content.Context
import com.vinicius741.webnovelarchiver.BuildConfig
import timber.log.Timber

/**
 * Application entry point. Owns the process-wide [AppContainer] (Maintainability M2) so that the
 * activity and the download/TTS foreground services share a single [com.vinicius741.webnovelarchiver.data.storage.AppStorage],
 * [com.vinicius741.webnovelarchiver.source.NetworkClient], and set of engines — preventing duplicate
 * engines from racing on the same JSON files.
 *
 * Observability (Tier 1, T1): plants a [Timber.DebugTree] in debug builds so diagnostics flow to
 * logcat. In release a minimal tree keeps warnings+ so serious failures (caught in catch blocks, or
 * unexpected throwables) are still recorded for bug reports, without leaking verbose debug logs.
 */
class WebnovelArchiverApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (Timber.treeCount == 0) {
            if (BuildConfig.DEBUG) {
                Timber.plant(Timber.DebugTree())
            } else {
                Timber.plant(ReleaseLogTree())
            }
        }
        container = AppContainer(this).apply { init() }
    }
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
