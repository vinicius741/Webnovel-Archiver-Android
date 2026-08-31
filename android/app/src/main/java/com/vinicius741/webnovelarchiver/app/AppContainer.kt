package com.vinicius741.webnovelarchiver.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vinicius741.webnovelarchiver.ai.AiChapterRewriteEngine
import com.vinicius741.webnovelarchiver.ai.AiCoverArtEngine
import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiDescriptionEngine
import com.vinicius741.webnovelarchiver.ai.OpenRouterClient
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.data.storage.migrateSourceIdentities
import com.vinicius741.webnovelarchiver.download.DownloadRequestPacer
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityStore
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Process-wide manual service locator (deliberately not a DI framework): one instance of each
 * engine so the activity and foreground services never race duplicates against the same files.
 * [ttsEngine] is shared by the reader UI and [com.vinicius741.webnovelarchiver.tts.TtsForegroundService]
 * so both observe one playback session instead of racing TextToSpeech handles.
 */
class AppContainer(
    context: Context,
) {
    /** Process-lifetime work that must finish even if the initiating Activity is recreated. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext

    // Orphaned .tmp.N backup temps mean a process death mid-write; nothing else can be writing at process start.
    private val storage =
        AppStorage(context).also { BackupFilePlanning.sweepOrphanTempFiles(it.backupRoot) }

    private val reliabilityStore = SourceReliabilityStore(storage.root)

    // Conflated: bursts coalesce, and the drain coroutine always persists the latest state.
    private val reliabilityPersistSignals = Channel<Unit>(Channel.CONFLATED)

    private val sourceReliability = SourceReliabilityCoordinator(onStateChanged = ::persistSourceReliability)
    val network: NetworkClient =
        NetworkClient(
            client = NetworkClient.buildDefault(appContext, sourceReliability),
            reliabilityCoordinator = sourceReliability,
        )

    /** Never writes synchronously: callers include OkHttp threads and main-thread UI paths. */
    private fun persistSourceReliability() {
        reliabilityPersistSignals.trySend(Unit)
    }

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
    val repository: AppRepository = AppRepository(storage)
    internal val downloadPacer = DownloadRequestPacer()
    val syncEngine: StorySyncEngine = StorySyncEngine(repository, network)
    val epubEngine: EpubEngine = EpubEngine(repository, network)
    val openRouter: OpenRouterClient = OpenRouterClient()
    val aiDescriptionEngine: AiDescriptionEngine = AiDescriptionEngine(repository, openRouter)
    val aiCoverArtEngine: AiCoverArtEngine = AiCoverArtEngine(repository, openRouter)

    /** Process scope so jobs survive navigation/exit; drafts persist before listeners are notified. */
    val aiCoverJobCoordinator: AiCoverJobCoordinator = AiCoverJobCoordinator(applicationScope, repository, aiCoverArtEngine)
    val aiChapterRewriteEngine: AiChapterRewriteEngine = AiChapterRewriteEngine(repository, openRouter)

    /** Process scope, same contract as covers: the draft persists before listeners are notified. */
    val aiChapterRewriteJobCoordinator: AiChapterRewriteJobCoordinator =
        AiChapterRewriteJobCoordinator(applicationScope, repository, aiChapterRewriteEngine)
    private val repositoryStartup =
        RepositoryStartup {
            // One storage monitor guards the whole migration/recovery/hydration transaction; concurrent file APIs wait on it.
            synchronized(storage) {
                storage.migrateChapterPathsToRelative()
                storage.migrateSourceIdentities(
                    sourceIdForUrl = { url -> SourceRegistry.getProvider(url)?.id },
                    sourceIdForSettingKey = SourceRegistry::sourceIdForPersistedKey,
                )
                storage.recoverInterruptedDownloads()
                repository.refresh()
            }
        }
    val ttsEngine: TtsEngine =
        TtsEngine(
            context = appContext,
            repository = repository,
            awaitRepositoryReady = repositoryStartup::awaitReady,
        )

    /** Starts migration/recovery/hydration on the process IO scope without blocking Application. */
    fun init() {
        runCatching {
            (appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager)
                .registerDefaultNetworkCallback(networkCallback)
        }
        // Serial drain: one writer coroutine so an older snapshot can never overwrite a newer one.
        applicationScope.launch {
            for (ignored in reliabilityPersistSignals) {
                reliabilityStore.save(sourceReliability.persistableStates())
            }
        }
        // Persisted circuit/transport state must govern scheduling again, but is advisory: an
        // unreadable document degrades to empty state instead of crashing Application.onCreate.
        runCatching { sourceReliability.restore(reliabilityStore.load()) }
            .onFailure { Timber.e(it, "Failed to restore source reliability state") }
        repositoryStartup.start(applicationScope)
    }

    suspend fun awaitRepositoryReady() = repositoryStartup.awaitReady()
}
