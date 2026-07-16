package com.vinicius741.webnovelarchiver.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow

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
    private val appContext = context.applicationContext
    private val sourceReliability = SourceReliabilityCoordinator()
    val network: NetworkClient =
        NetworkClient(
            client = NetworkClient.buildDefault(appContext, sourceReliability),
            reliabilityCoordinator = sourceReliability,
        )

    @Volatile private var activeNetwork: Network? = null

    @Volatile private var hasObservedNetwork = false

    @Volatile private var activeNetworkWasLost = false

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(networkHandle: Network) {
                val previous = activeNetwork
                activeNetwork = networkHandle
                val changed = hasObservedNetwork && (activeNetworkWasLost || previous != null && previous != networkHandle)
                hasObservedNetwork = true
                activeNetworkWasLost = false
                if (changed) network.onNetworkChanged()
            }

            override fun onLost(networkHandle: Network) {
                if (activeNetwork == networkHandle) {
                    activeNetwork = null
                    activeNetworkWasLost = true
                }
            }
        }
    val storage: AppStorage = AppStorage(context)
    val repository: AppRepository = AppRepository(storage)
    val syncEngine: StorySyncEngine = StorySyncEngine(storage, network)
    val epubEngine: EpubEngine = EpubEngine(repository, network)
    private val repositoryStartup =
        RepositoryStartup {
            // One storage monitor covers the complete migration/recovery/hydration transaction.
            // Services that reach file APIs concurrently wait on the same monitor rather than
            // observing a partially migrated queue or library.
            synchronized(storage) {
                storage.migrateChapterPathsToRelative()
                storage.recoverInterruptedDownloads()
                repository.refresh()
            }
        }
    val repositoryReadiness: StateFlow<RepositoryReadiness> = repositoryStartup.readiness
    val ttsEngine: TtsEngine =
        TtsEngine(
            context = appContext,
            storage = storage,
            repository = repository,
            awaitRepositoryReady = repositoryStartup::awaitReady,
        )

    /** Starts migration/recovery/hydration on the process IO scope without blocking Application. */
    fun init() {
        runCatching {
            (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }
        repositoryStartup.start(applicationScope)
    }

    suspend fun awaitRepositoryReady() = repositoryStartup.awaitReady()
}
