package com.vinicius741.webnovelarchiver.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import com.vinicius741.webnovelarchiver.ai.AiCoverArtEngine
import com.vinicius741.webnovelarchiver.ai.AiCoverJobCoordinator
import com.vinicius741.webnovelarchiver.ai.AiDescriptionEngine
import com.vinicius741.webnovelarchiver.ai.AiDescriptionJobCoordinator
import com.vinicius741.webnovelarchiver.ai.ChapterEvidenceSelector
import com.vinicius741.webnovelarchiver.ai.CharacterEngine
import com.vinicius741.webnovelarchiver.ai.CharacterJobCoordinator
import com.vinicius741.webnovelarchiver.ai.DecisionClient
import com.vinicius741.webnovelarchiver.ai.OpenRouterClient
import com.vinicius741.webnovelarchiver.appupdate.AppUpdateCoordinator
import com.vinicius741.webnovelarchiver.appupdate.GitHubReleaseClient
import com.vinicius741.webnovelarchiver.appupdate.UpdateChannel
import com.vinicius741.webnovelarchiver.data.backup.BackupFilePlanning
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityCategory
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLog
import com.vinicius741.webnovelarchiver.data.diagnostics.ActivityLogLibraryStats
import com.vinicius741.webnovelarchiver.data.repository.AppRepository
import com.vinicius741.webnovelarchiver.data.storage.AppStorage
import com.vinicius741.webnovelarchiver.data.storage.ChapterEvidenceStore
import com.vinicius741.webnovelarchiver.data.storage.StoredChapterCompaction
import com.vinicius741.webnovelarchiver.domain.metrics.PatreonCreatorPlanning
import com.vinicius741.webnovelarchiver.download.DownloadRequestPacer
import com.vinicius741.webnovelarchiver.epub.EpubEngine
import com.vinicius741.webnovelarchiver.feature.ai.AiAttention
import com.vinicius741.webnovelarchiver.narration.NarrationCoordinator
import com.vinicius741.webnovelarchiver.narration.NarrationForegroundService
import com.vinicius741.webnovelarchiver.operations.AppListenStartData
import com.vinicius741.webnovelarchiver.operations.ListenStarts
import com.vinicius741.webnovelarchiver.source.SourceRegistry
import com.vinicius741.webnovelarchiver.source.local.AndroidPdfPages
import com.vinicius741.webnovelarchiver.source.local.LOCAL_OCR_DIR
import com.vinicius741.webnovelarchiver.source.local.LocalNovelReader
import com.vinicius741.webnovelarchiver.source.local.LocalSourceProvider
import com.vinicius741.webnovelarchiver.source.local.PdfOcrCache
import com.vinicius741.webnovelarchiver.source.local.SafLocalLibraryFiles
import com.vinicius741.webnovelarchiver.source.network.NetworkClient
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityCoordinator
import com.vinicius741.webnovelarchiver.source.network.SourceReliabilityStore
import com.vinicius741.webnovelarchiver.sync.StorySyncEngine
import com.vinicius741.webnovelarchiver.sync.storyChapterKey
import com.vinicius741.webnovelarchiver.tts.TtsEngine
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInEngines
import com.vinicius741.webnovelarchiver.tts.kokoro.BuiltInModel
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelInstaller
import com.vinicius741.webnovelarchiver.tts.kokoro.KokoroModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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

    init {
        // The local folder (Add story › Local folder): the local source reads novels through it.
        LocalSourceProvider.install(
            LocalNovelReader(
                SafLocalLibraryFiles(appContext),
                AndroidPdfPages,
                PdfOcrCache(java.io.File(appContext.filesDir, LOCAL_OCR_DIR)),
            ),
        )
    }

    val openRouter: OpenRouterClient = OpenRouterClient(client = simulation?.aiClient() ?: OpenRouterClient.defaultClient())
    val chapterEvidence = ChapterEvidenceStore(appContext.cacheDir)
    private val chapterEvidenceSelector = ChapterEvidenceSelector(repository, DecisionClient(openRouter, time::sleep), chapterEvidence)
    val aiDescriptionEngine: AiDescriptionEngine =
        AiDescriptionEngine(repository, openRouter, chapterEvidenceSelector, chapterEvidence)
    val aiCoverArtEngine: AiCoverArtEngine =
        AiCoverArtEngine(repository, openRouter, chapterEvidenceSelector, chapterEvidence)

    private val updateChannel = UpdateChannel.fromBuildConfig(com.vinicius741.webnovelarchiver.BuildConfig.UPDATE_CHANNEL)

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
                        channel = updateChannel,
                    )
                } else {
                    null
                },
            installedVersionName = com.vinicius741.webnovelarchiver.BuildConfig.VERSION_NAME,
            releaseBuild = com.vinicius741.webnovelarchiver.BuildConfig.BUILD_TYPE in PUBLISHED_BUILD_TYPES,
            channel = updateChannel,
            nowMillis = time::nowMillis,
        )

    /** Process scope so jobs survive navigation/exit; drafts persist before listeners are notified. */
    val aiCoverJobCoordinator: AiCoverJobCoordinator = AiCoverJobCoordinator(applicationScope, repository, aiCoverArtEngine)

    /** AI description drafts, on the same process scope as cover jobs. */
    val aiDescriptionJobs: AiDescriptionJobCoordinator = AiDescriptionJobCoordinator(applicationScope, aiDescriptionEngine::draft)

    /** AI results and failures waiting for the user; the AI tab, the story page and the job chip read them. */
    internal val aiAttention = AiAttention()

    /** Characters tab: character list and portraits, on the same process scope as cover jobs. */
    val characterJobCoordinator: CharacterJobCoordinator =
        CharacterJobCoordinator(applicationScope, CharacterEngine(repository, openRouter, chapterEvidenceSelector, chapterEvidence))
    private val repositoryStartup =
        RepositoryStartup {
            // One storage monitor guards the whole load/recovery/hydration transaction; concurrent file APIs wait on it.
            // R20: the library is read exactly once — default coercion, Patreon reconciliation, and
            // hydration all operate on that one pass's list, and only changed story documents are
            // written back. Phase timings below are the R30 startup evidence.
            synchronized(storage) {
                val startedAt = System.currentTimeMillis()
                val load = storage.loadLibraryForStartup()
                val stories = load.stories
                val loadFinishedAt = System.currentTimeMillis()

                // Stats fetched through a link naming no creator belong to another page; drop them.
                val changedByCreatorless = mutableSetOf<String>()
                PatreonCreatorPlanning
                    .clearCreatorlessStats(stories, changedByCreatorless)
                    .forEach(storage::stripPatreonHistory)
                val changedByPatreonCreator = PatreonCreatorPlanning.reconcileLibrary(stories)
                val changedIds = load.changedStoryIds + changedByCreatorless + changedByPatreonCreator
                if (changedIds.isNotEmpty()) {
                    storage.persistStartupStories(stories.filter { it.id in changedIds })
                }
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
                val ready = ActivityLogLibraryStats.of(stories)
                ActivityLog.recordAll(
                    ActivityCategory.APP,
                    "startup_library_loaded",
                    listOf(
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
     * Downloads and installs each built-in model on demand; the files stay on the device. The offline
     * simulation's guarded client denies the download, so QA sessions never fetch one.
     */
    internal val modelInstallers: Map<BuiltInModel, KokoroModelInstaller> =
        BuiltInModel.entries.associateWith { model ->
            simulation?.aiClient()?.let { KokoroModelInstaller(appContext, applicationScope, it, model) }
                ?: KokoroModelInstaller(appContext, applicationScope, builtIn = model)
        }

    /** The built-in voice models, each loaded once while in use and shared by playback, narration and the settings sample. */
    val builtIns: BuiltInEngines = BuiltInEngines(modelInstallers)

    /** Generated chapter audio: the queue, the background generator and the packs chapters play from. */
    val narration: NarrationCoordinator =
        NarrationCoordinator(
            context = appContext,
            repository = repository,
            builtIns = builtIns,
            startService = { NarrationForegroundService.start(appContext) },
        )

    val ttsEngine: TtsEngine =
        TtsEngine(
            repository = repository,
            awaitRepositoryReady = {
                repositoryStartup.awaitReady()
                narration.awaitLoaded()
            },
            narration = narration,
        )

    /** Every "listen" request: plays generated audio, or queues the chapter and plays it once ready. */
    val listenStarts: ListenStarts = ListenStarts(AppListenStartData(narration, ttsEngine, repository))

    /** Starts load/recovery/hydration on the process IO scope without blocking Application. */
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
        narration.init(repositoryStartup::awaitReady)
        applicationScope.launch { appUpdates.restoreCachedRelease() }
        applicationScope.launch { compactStoredChapters() }
    }

    /** Once per library, after startup settles: drops source markup noise from chapters saved before it was stripped. */
    private suspend fun compactStoredChapters() {
        runCatching {
            repositoryStartup.awaitReady()
            delay(CHAPTER_COMPACTION_DELAY_MS)
            StoredChapterCompaction(
                root = storage.root,
                lock = storage,
                compactFile = storage::compactChapterFile,
                stories = repository::library,
                compactorFor = { story -> SourceRegistry.getProvider(story.sourceId, story.sourceUrl)?.chapterMarkupCompactor },
                libraryGeneration = repository::libraryGeneration,
            ).runIfPending()
        }.onFailure { if (it is CancellationException) throw it else Timber.w(it, "Chapter compaction did not run") }
    }

    suspend fun awaitRepositoryReady() = repositoryStartup.awaitReady()

    private companion object {
        /** Build types published through GitHub Releases; each follows its own update channel. */
        val PUBLISHED_BUILD_TYPES = setOf("release", "beta")

        /** Keeps the compaction's file writes out of the first screens and the startup work. */
        const val CHAPTER_COMPACTION_DELAY_MS = 30_000L
    }
}
