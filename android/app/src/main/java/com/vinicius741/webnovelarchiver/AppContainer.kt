package com.vinicius741.webnovelarchiver

import android.content.Context
import com.vinicius741.webnovelarchiver.core.AppRepository
import com.vinicius741.webnovelarchiver.core.AppStorage
import com.vinicius741.webnovelarchiver.core.EpubEngine
import com.vinicius741.webnovelarchiver.core.NetworkClient
import com.vinicius741.webnovelarchiver.core.StorySyncEngine
import com.vinicius741.webnovelarchiver.core.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Lightweight process-wide dependency container (Maintainability M2). Attached to
 * [WebnovelArchiverApp] and reachable from any Android component via
 * `(applicationContext as WebnovelArchiverApp).container`. Holds exactly one instance of each
 * process-wide dependency:
 *
 *  - [repository] → owns the single [AppStorage] and the queue/story transaction lock (R2/R3).
 *  - [network] → shared OkHttp client + per-host rate limiter (R6).
 *  - [syncEngine] / [epubEngine] → stateful engines built on the shared repository + network.
 *  - [ttsEngine] → the single TTS playback engine, shared by [MainActivity] (reader highlight +
 *    transport, parity gaps 3 & 4) and [com.vinicius741.webnovelarchiver.tts.TtsForegroundService]
 *    (MediaSession + notification, parity gaps 1 & 2). Sharing one instance means the reader's
 *    multicast state listener fires for playback the service drives, instead of each component
 *    racing with its own TextToSpeech handle against the same session JSON.
 *
 * This is deliberately *not* a DI framework: it is the native-Android manual-service-locator pattern
 * that prevents the activity and foreground services from accidentally instantiating duplicate
 * engines racing against the same files.
 */
class AppContainer(
    context: Context,
) {
    /** Process-lifetime work that must finish even if the initiating Activity is recreated. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val network: NetworkClient = NetworkClient()
    val storage: AppStorage = AppStorage(context)
    val repository: AppRepository = AppRepository(storage)
    val syncEngine: StorySyncEngine = StorySyncEngine(storage, network)
    val epubEngine: EpubEngine = EpubEngine(storage, network)
    val ttsEngine: TtsEngine = TtsEngine(context.applicationContext, storage)

    /** Refreshes the repository's cached state flows; call from [Application.onCreate]. */
    fun init() {
        storage.migrateChapterPathsToRelative()
        storage.recoverInterruptedDownloads()
        repository.refresh()
    }
}
