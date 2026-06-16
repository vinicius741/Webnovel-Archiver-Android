package com.vinicius741.webnovelarchiver

import android.app.Application
import android.content.Context

/**
 * Application entry point. Owns the process-wide [AppContainer] (Maintainability M2) so that the
 * activity and the download/TTS foreground services share a single [com.vinicius741.webnovelarchiver.core.AppStorage],
 * [com.vinicius741.webnovelarchiver.core.NetworkClient], and set of engines — preventing duplicate
 * engines from racing on the same JSON files.
 */
class WebnovelArchiverApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this).apply { init() }
    }
}

/** Convenience accessor for any component holding an application [Context]. */
val Context.appContainer: AppContainer
    get() = (applicationContext as WebnovelArchiverApp).container
