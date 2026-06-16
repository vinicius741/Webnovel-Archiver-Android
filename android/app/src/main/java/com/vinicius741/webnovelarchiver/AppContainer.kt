package com.vinicius741.webnovelarchiver

import android.content.Context
import com.vinicius741.webnovelarchiver.core.AppRepository
import com.vinicius741.webnovelarchiver.core.AppStorage
import com.vinicius741.webnovelarchiver.core.EpubEngine
import com.vinicius741.webnovelarchiver.core.NetworkClient
import com.vinicius741.webnovelarchiver.core.StorySyncEngine

/**
 * Lightweight process-wide dependency container (Maintainability M2). Attached to
 * [WebnovelArchiverApp] and reachable from any Android component via
 * `(applicationContext as WebnovelArchiverApp).container`. Holds exactly one instance of each
 * process-wide dependency:
 *
 *  - [repository] → owns the single [AppStorage] and the queue/story transaction lock (R2/R3).
 *  - [network] → shared OkHttp client + per-host rate limiter (R6).
 *  - [syncEngine] / [epubEngine] → stateful engines built on the shared repository + network.
 *
 * This is deliberately *not* a DI framework: it is the native-Android manual-service-locator pattern
 * that prevents the activity and foreground services from accidentally instantiating duplicate
 * engines racing against the same files.
 */
class AppContainer(context: Context) {
    val network: NetworkClient = NetworkClient()
    val storage: AppStorage = AppStorage(context)
    val repository: AppRepository = AppRepository(storage)
    val syncEngine: StorySyncEngine = StorySyncEngine(storage, network)
    val epubEngine: EpubEngine = EpubEngine(storage, network)

    /** Refreshes the repository's cached state flows; call from [Application.onCreate]. */
    fun init() {
        storage.recoverInterruptedDownloads()
        repository.refresh()
    }
}
