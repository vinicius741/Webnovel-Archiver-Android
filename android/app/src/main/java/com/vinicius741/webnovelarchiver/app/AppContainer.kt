package com.vinicius741.webnovelarchiver.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vinicius741.webnovelarchiver.ai.AiCoverArtEngine
import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiDescriptionEngine
import com.vinicius741.webnovelarchiver.ai.ChapterEvidenceSelector
import com.vinicius741.webnovelarchiver.ai.DecisionClient
import com.vinicius741.webnovelarchiver.ai.OpenRouterClient
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.GitHubReleaseClient
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogLibraryStats
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.data.storage.ChapterEvidenceStore
import com.vinicius741.webnovelarchiver.data.storage.foldLegacyArchives
import com.vinicius741.webnovelarchiver.data.storage.migrateSourceIdentities
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.download.DownloadRequestPacer
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationForegroundService
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityStore
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.storyChapterKey
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelInstaller
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.File

/**
 * Process-wide manual service locator (deliberately not a DI framework): one instance of each
 * engine so the activity and foreground services never race duplicates against the same files.
 * [ttsEngine] is shared by the reader UI and [com.vinicius741.webnovelarchiver.tts.TtsForegroundService]
 * so both observe one playback session instead of racing TextToSpeech handles.
 */
class AppContainer internal constructor(
    context: Context,
    internal val simulation: DevEnvironment? = null,
) {
    /** Process-lifetime work that must finish even if the initiating Activity is recreated. */
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val appContext = context.applicationContext
    val time: AppTime = simulation?.time ?: AppTime.Real

    // Orphaned .tmp.N backup temps mean a process death mid-write; nothing else can be writing at process start.
    // R07: recover an interrupted full restore BEFORE AppStorage creates or hydrates the live root.
    private val storage =
        run {
            com.vinicius741.webnovelarchiver.data.storage.RestoreStartupRecovery
                .recover(context)
            AppStorage(context).also { BackupFilePlanning.sweepOrphanTempFiles(it.backupRoot) }
        }

    private val reliabilityStore = SourceReliabilityStore(storage.root)

    // Conflated: bursts coalesce, and the drain coroutine always persists the latest state.
    private val reliabilityPersistSignals = Channel<Unit>(Channel.CONFLATED)

    private val sourceReliability =
        SourceReliabilityCoordinator(
            nowMillis = time::nowMillis,
            sleep = time::sleep,
            onStateChanged = ::persistSourceReliability,
        )
    val network: NetworkClient =
        NetworkClient(
            client = simulation?.sourceClient(sourceReliability) ?: NetworkClient.buildDefault(appContext, sourceReliability),
            nowMillis = time::nowMillis,
            sleep = time::sleep,
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
    internal val downloadPacer = DownloadRequestPacer(nowMillis = time::nowMillis, sleep = time::sleep)
    val syncEngine: StorySyncEngine = StorySyncEngine(repository, network)
    val epubEngine: EpubEngine = EpubEngine(repository, network)
    val openRouter: OpenRouterClient = OpenRouterClient(client = simulation?.aiClient() ?: OpenRouterClient.defaultClient())
    val chapterEvidence = ChapterEvidenceStore(appContext.cacheDir)
    private val chapterEvidenceSelector = ChapterEvidenceSelector(repository, DecisionClient(openRouter, time::sleep), chapterEvidence)
    val aiDescriptionEngine: AiDescriptionEngine =
        AiDescriptionEngine(repository, openRouter, chapterEvidenceSelector, chapterEvidence)
    val aiCoverArtEngine: AiCoverArtEngine =
        AiCoverArtEngine(repository, openRouter, chapterEvidenceSelector, chapterEvidence)

    /** Update checks against GitHub Releases; disabled in the offline simulation. */
    val appUpdates: AppUpdateCoordinator =
        AppUpdateCoordinator(
            context = appContext,
            scope = applicationScope,
            client =
                if (simulation == null) {
                    GitHubReleaseClient(
                        repository = com.vinicius741.webnovelarchiver.BuildConfig.UPDATE_REPOSITORY,
                        userAgent = "WebnovelArchiver-Android/${com.vinicius741.webnovelarchiver.BuildConfig.VERSION_NAME}",
                    )
                } else {
                    null
                },
            installedVersionName = com.vinicius741.webnovelarchiver.BuildConfig.VERSION_NAME,
            releaseBuild = com.vinicius741.webnovelarchiver.BuildConfig.BUILD_TYPE == "release",
            nowMillis = time::nowMillis,
        )

    /** Process scope so jobs survive navigation/exit; drafts persist before listeners are notified. */
    val aiCoverJobCoordinator: AiCoverJobCoordinator = AiCoverJobCoordinator(applicationScope, repository, aiCoverArtEngine)
    private val repositoryStartup =
        RepositoryStartup {
            // One storage monitor guards the whole migration/recovery/hydration transaction; concurrent file APIs wait on it.
            // R20: the library is read exactly once — path coercion, source-identity backfill, and
            // hydration all operate on that one pass's list, and only changed story documents are
            // written back. Phase timings below are the R30 startup evidence.
            synchronized(storage) {
                val startedAt = System.currentTimeMillis()
                val load = storage.loadLibraryForStartup()
                val loadFinishedAt = System.currentTimeMillis()

                val changedBySourceId =
                    storage.migrateSourceIdentities(
                        library = load.stories,
                        sourceIdForUrl = { url -> SourceRegistry.getProvider(url)?.id },
                        sourceIdForSettingKey = SourceRegistry::sourceIdForPersistedKey,
                    )
                // Stats fetched through a link naming no creator belong to another page; drop them.
                val changedByCreatorless = mutableSetOf<String>()
                PatreonCreatorPlanning
                    .clearCreatorlessStats(load.stories, changedByCreatorless)
                    .forEach(storage::stripPatreonHistory)
                val changedByPatreonCreator = PatreonCreatorPlanning.reconcileLibrary(load.stories)
                val changedIds = load.changedStoryIds + changedBySourceId + changedByCreatorless + changedByPatreonCreator
                if (changedIds.isNotEmpty()) {
                    storage.persistStartupStories(load.stories.filter { it.id in changedIds })
                }
                // Legacy archive snapshots become removed-from-source chapters of their live story.
                val stories = storage.foldLegacyArchives(load.stories, ::storyChapterKey)
                val migrationsFinishedAt = System.currentTimeMillis()

                storage.recoverInterruptedDownloads()
                repository.refresh(stories)
                val readyAt = System.currentTimeMillis()
                Timber.i(
                    "startup pass: stories=%d changed=%d loadMs=%d migrateMs=%d hydrateMs=%d totalMs=%d",
                    stories.size,
                    changedIds.size,
                    loadFinishedAt - startedAt,
                    migrationsFinishedAt - loadFinishedAt,
                    readyAt - migrationsFinishedAt,
                    readyAt - startedAt,
                )
                val loaded = ActivityLogLibraryStats.of(load.stories)
                val ready = ActivityLogLibraryStats.of(stories)
                ActivityLog.recordAll(
                    ActivityCategory.APP,
                    "startup_library_loaded",
                    listOf(
                        "legacyArchivesLoaded" to loaded.legacyArchives,
                        "legacyArchivesLeft" to ready.legacyArchives,
                        "changedOnLoad" to changedIds.size,
                        "totalMs" to readyAt - startedAt,
                    ) + ready.fields("ready"),
                )
                if (com.vinicius741.webnovelarchiver.BuildConfig.DEBUG) {
                    com.vinicius741.webnovelarchiver.perf.PerfInstrumentation.recordStartupPass(
                        stories = stories.size,
                        changed = changedIds.size,
                        loadMillis = loadFinishedAt - startedAt,
                        migrateMillis = migrationsFinishedAt - loadFinishedAt,
                        hydrateMillis = readyAt - migrationsFinishedAt,
                        totalMillis = readyAt - startedAt,
                    )
                }
            }
            simulation?.prepareRepository(repository)
        }

    /**
     * Downloads and unpacks the Kokoro model on demand; the files stay on the device. The offline
     * simulation's guarded client denies the download, so QA sessions never fetch it.
     */
    internal val kokoroModel: KokoroModelInstaller =
        simulation?.aiClient()?.let { KokoroModelInstaller(appContext, applicationScope, it) }
            ?: KokoroModelInstaller(appContext, applicationScope)

    /** The built-in Kokoro voice model, loaded once and shared by playback and the settings sample. */
    val kokoroRuntime: KokoroRuntime = KokoroRuntime(modelInstalled = { kokoroModel.isInstalled })

    /** Generated chapter audio: the queue, the background generator and the packs chapters play from. */
    val narration: NarrationCoordinator =
        NarrationCoordinator(
            context = appContext,
            repository = repository,
            kokoro = kokoroRuntime,
            startService = { NarrationForegroundService.start(appContext) },
        )

    val ttsEngine: TtsEngine =
        TtsEngine(
            context = appContext,
            repository = repository,
            awaitRepositoryReady = {
                repositoryStartup.awaitReady()
                narration.awaitLoaded()
            },
            kokoro = kokoroRuntime,
            narration = narration,
        )

    /** Starts migration/recovery/hydration on the process IO scope without blocking Application. */
    fun init() {
        // A chapter that started while Kokoro was still downloading switches to it once installed.
        applicationScope.launch {
            kokoroModel.state.collect { if (it is KokoroModelState.Installed) ttsEngine.refreshEngine() }
        }
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
        narration.init(repositoryStartup::awaitReady)
        applicationScope.launch { appUpdates.restoreCachedRelease() }
    }

    suspend fun awaitRepositoryReady() = repositoryStartup.awaitReady()
}
